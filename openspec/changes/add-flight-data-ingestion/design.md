# Design

## Context

- A aplicação (`application-runtime`) tem Spring Boot 4.1.1, JDBC/Hikari sem
  JPA, Actuator, springdoc e nenhuma tabela. A configuração vem de
  `application.yaml`, com `${VAR:padrão}` e `.env` importado. Os testes de
  integração usam o `compose.yaml` via `spring-boot-docker-compose`, contra o
  banco `skyledger` de dev.
- Os dados estão em `../anac-data` (sibling do repositório). Medições feitas
  sobre os arquivos reais, que fundamentam as decisões abaixo:

| Arquivo | Linhas | Formato |
|---------|--------|---------|
| `VRA_20265/6/7.csv` | 82.120 / 81.119 / 89.517 | UTF-8 com BOM, CRLF, 12 campos sempre entre aspas, sem `;` nem aspas internas |
| `AerodromosPublicos.csv` | 496 | ISO-8859-1 (nenhum byte 0x80-0x9F, então não é cp1252), CRLF, 18 campos **sem** aspas, aspa literal na latitude DMS |

- Fatos do VRA: os `null` ocorrem sempre em pares (os dois previstos em 7.127
  REALIZADO, os dois reais em 8.004 CANCELADO). A fração `.100000000` aparece
  em 39.792 horários previstos, e todos os segundos são `:00`. A chave B não
  tem colisões entre linhas diferentes, só 29 duplicatas exatas dentro dos
  arquivos e 2 entre maio e junho. O cadastro tem latitude com até 9 casas
  decimais e altitude máxima de 1.426,0 m. Dos 289 aeroportos do VRA, 153
  estão no cadastro, e o resto são 130 estrangeiros e 6 brasileiros.
- Motivação em `proposal.md`, contratos em
  `specs/flight-data-ingestion/spec.md` e `specs/application-runtime/spec.md`.

## Goals / Non-Goals

**Goals:**
- Contagens exatas no relatório (`inserted`, `duplicates`, `updated`,
  `rejected`), com idempotência garantida pelo banco e não só pela aplicação.
- Uma transação por arquivo.
- Testes de integração que não tocam os dados de dev.

**Non-Goals:**
- Conversão de fuso horário, cálculo de atraso e qualquer analytics.
- Ingestão do Dados Estatísticos.
- Ingestão assíncrona, agendada ou distribuída (várias instâncias). O
  bloqueio de concorrência é dentro da JVM.
- Nome de companhia aérea: o VRA não traz, e não há fonte aprovada.
- Download dos dados da ANAC, que é papel do `anac-data`.
- Cold storage dos arquivos brutos no SeaweedFS (mudança futura; o SHA-256 do
  lote já prepara a chave).

## Decisions

### D1. Flyway com rollback fora do caminho de migração
`spring-boot-starter-flyway` e `flyway-database-postgresql` (aprovados, versão
gerenciada pelo Boot: Flyway 12.4). Migrações em
`src/main/resources/db/migration/V1__create_ingestion_schema.sql`. A edição
livre do Flyway não executa migrações `U` (undo), então o rollback fica em
`src/main/resources/db/rollback/V1__down.sql`, fora de `db/migration`, para
que o Flyway não o carregue. Um teste aplica V1, roda o down e verifica que o
esquema volta ao estado anterior (D9). As migrações rodam na inicialização,
que é o comportamento padrão do Boot e a decisão registrada na proposta.
*Alternativa:* Liquibase, com rollback declarativo. Foi descartado na escolha
de dependência.

### D2. Esquema (V1)
```sql
CREATE TABLE airline (
  icao        varchar(3) PRIMARY KEY,
  created_at  timestamptz NOT NULL DEFAULT now());

CREATE TABLE airport (
  icao             varchar(4) PRIMARY KEY,
  ciad             varchar(10),
  name             text,
  municipality     text,
  uf               text,              -- como publicada, por extenso
  latitude         numeric(12,9),     -- até 9 casas medidas
  longitude        numeric(12,9),
  altitude_m       numeric(7,1),
  registry_status  text CHECK (registry_status IN ('Cadastrado','Interditado')),
  in_public_registry boolean NOT NULL DEFAULT false,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now());

CREATE TABLE ingestion_batch (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  dataset           text NOT NULL CHECK (dataset IN ('AERODROMOS','VRA')),
  file_name         text NOT NULL,
  sha256            char(64) NOT NULL,
  source_updated_at date,
  status            text NOT NULL CHECK (status IN ('COMPLETED','REJECTED')),
  rows_read  integer NOT NULL, inserted integer NOT NULL, updated integer NOT NULL,
  duplicates integer NOT NULL, rejected integer NOT NULL,
  error             text,
  started_at        timestamptz NOT NULL,
  finished_at       timestamptz NOT NULL);

CREATE TABLE ingestion_rejection (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  batch_id    bigint NOT NULL REFERENCES ingestion_batch(id),
  line_number integer NOT NULL,
  reason      text NOT NULL);

CREATE TABLE flight (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  airline_icao        varchar(3) NOT NULL REFERENCES airline(icao),
  flight_number       varchar(10) NOT NULL,
  authorization_code  text NOT NULL,
  line_type           text NOT NULL,
  origin_icao         varchar(4) NOT NULL REFERENCES airport(icao),
  destination_icao    varchar(4) NOT NULL REFERENCES airport(icao),
  scheduled_departure timestamp, actual_departure timestamp,
  scheduled_arrival   timestamp, actual_arrival   timestamp,
  status              text NOT NULL,
  justification_code  text NOT NULL,
  batch_id            bigint NOT NULL REFERENCES ingestion_batch(id),
  CONSTRAINT flight_natural_key UNIQUE NULLS NOT DISTINCT
    (airline_icao, flight_number, origin_icao, scheduled_departure, actual_departure),
  CONSTRAINT flight_status_times CHECK (
    (status = 'REALIZADO' AND actual_departure IS NOT NULL AND actual_arrival IS NOT NULL
       AND (scheduled_departure IS NULL) = (scheduled_arrival IS NULL))
    OR (status = 'CANCELADO' AND actual_departure IS NULL AND actual_arrival IS NULL
       AND scheduled_departure IS NOT NULL AND scheduled_arrival IS NOT NULL)));
```
- `UNIQUE NULLS NOT DISTINCT` (PostgreSQL 15+) é o que torna idempotentes os
  cancelados (sem partida real) e os realizados sem partida prevista. Com
  `UNIQUE` comum, cada reprocessamento duplicaria 15 mil linhas.
- A `CHECK` espelha a tabela de combinações do spec. O parser valida antes,
  e o banco garante a regra mesmo contra um bug no parser.
- `timestamp` sem fuso: os horários são gravados como publicados (Non-Goal).
- `flight_number varchar(10)`: o maior valor medido tem 5 caracteres
  (`Z9610`), e a folga cobre mudanças de formato sem migração.

### D3. Pipeline por arquivo, com staging e contagem pelo banco
Cada arquivo roda em **uma transação**:
1. Calcula o SHA-256 (leitura em streaming).
2. Valida o metadado (linha 1) e o cabeçalho (linha 2). Se falharem, grava o
   lote `REJECTED` com `error` e encerra o arquivo.
3. Lê as linhas em streaming. Cada linha é validada (D5/D6): uma inválida vira
   `ingestion_rejection`, e uma válida vai para uma tabela temporária
   (`CREATE TEMP TABLE ... ON COMMIT DROP`) em lotes JDBC de 1.000.
4. `INSERT INTO airline/airport (icao) SELECT DISTINCT ... ON CONFLICT DO NOTHING`.
5. `INSERT INTO flight SELECT ... FROM staging ON CONFLICT DO NOTHING`, cuja
   contagem de linhas afetadas é o `inserted`. O `duplicates` é o número de
   linhas válidas menos o `inserted`. Duplicatas dentro do próprio arquivo
   caem no mesmo `ON CONFLICT`, porque com `DO NOTHING` uma linha conflita
   também com outra inserida antes no mesmo comando.
6. Grava o lote `COMPLETED` com as contagens e confirma a transação.

O `ON CONFLICT DO NOTHING` vai sem alvo: a única outra unicidade é a PK
gerada, então o efeito é o mesmo, e evita depender da inferência do índice
`NULLS NOT DISTINCT`.
*Alternativas:* `INSERT` linha a linha (cerca de 250 mil idas ao banco); lote
JDBC direto na `flight`, em que `reWriteBatchedInserts` faz o driver devolver
`SUCCESS_NO_INFO` e as contagens se perdem; `COPY` com `CopyManager`, mais
rápido, mas mais código para o volume atual.

### D4. Cadastro de aeródromos: upsert com contagem de inserido e atualizado
O mesmo esqueleto do D3, com staging deduplicada por `icao` (vale a última
linha, e as repetidas contam como `duplicates`) e
`INSERT ... ON CONFLICT (icao) DO UPDATE SET ..., in_public_registry = true,
updated_at = now() RETURNING (xmax = 0) AS inserted`. As linhas com `true`
somam em `inserted` e as com `false`, em `updated`. Isso atende a
"Aeroporto criado pelo VRA e enriquecido depois" e a "Reprocessamento do
cadastro". O cadastro é processado antes do VRA, então os aeroportos
brasileiros já chegam completos. Os criados pelo VRA ficam com
`in_public_registry = false`.

### D5. Parsers próprios e estritos, sem biblioteca
- `VraFileParser`: lê UTF-8, remove o `﻿` inicial se existir e aceita
  linhas CRLF ou LF (`BufferedReader.readLine`). Cada linha tem que casar com
  `^"[^";]*"(;"[^";]*"){11}$`, que é exatamente o formato medido. Fora disso,
  rejeita com "esperados 12 campos entre aspas" e o número de campos achados.
- `AerodromeFileParser`: lê ISO-8859-1 e usa `split(";", -1)` com exatamente
  18 campos, tratando a aspa como caractere comum.
- Comum aos dois (`SourceFileHeader`, pequeno): regex do metadado
  `^Atualizado em: (\d{4}-\d{2}-\d{2})$` e mapeamento nome→índice do
  cabeçalho, exigindo o conjunto exato de colunas. A abstração existe porque
  há dois usos concretos nesta própria mudança.
- O número da linha física (a primeira linha de dados é a 3) acompanha cada
  rejeição.

### D6. Regras de valor
- Horário: `^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})(\.\d+)?$`. O grupo 2 é
  descartado e o resultado vira `LocalDateTime`. `null` vira ausência de
  valor. Qualquer outro texto rejeita a linha, citando a coluna.
- A combinação situação × nulos segue a tabela do spec. A situação fora de
  `REALIZADO`/`CANCELADO` rejeita a linha.
- Empresa `^[A-Z0-9]{3}$`, aeroportos `^[A-Z0-9]{4}$` (medidos `AA99` e
  `AAA9`). Origem igual ao destino é aceita: há 1.026 casos nos dados, e
  rejeitar seria inventar uma regra.
- Cadastro: latitude e longitude com `new BigDecimal(valor)` (ponto decimal),
  altitude com `,` trocada por `.` e `new BigDecimal`. Nunca `double`.
- *Visto no apply, com os dados reais:* o `SBMI` (Maricá) tem `Altitude` `,0`,
  único caso. Interpretar como `0,0` m seria inventar dado, porque o
  aeroporto fica acima do nível do mar. Por decisão do usuário, altitude
  vazia ou sem parte inteira vira ausente (`NULL`) e a linha é aceita, o que
  mantém os 496 aeródromos. Coordenadas: até 3 dígitos inteiros e 9 decimais,
  validados para caber exatos em `numeric(12,9)`.

### D7. Endpoint, erros e concorrência
- `IngestionController` com `POST /api/ingestions` chama o `IngestionService`
  e devolve um `IngestionReport` (records Java com os campos do spec). A lista
  `rejections` é limitada a 100 por arquivo.
- Os erros usam o `ProblemDetail` do Spring, num `@RestControllerAdvice`:
  `AnacDataUnavailableException` → 503,
  `IngestionAlreadyRunningException` → 409, e
  `CannotGetJdbcConnectionException`/`DataAccessResourceFailureException` →
  503.
- Concorrência: um `ReentrantLock.tryLock()` no serviço. Sem o lock, responde
  409 (Non-Goal: várias instâncias).
- A ingestão é síncrona: medimos cerca de 250 mil linhas e a meta é
  processar tudo em menos de 2 minutos no ambiente local (verificada na task
  de aceite). Clientes HTTP precisam de timeout compatível, e isso está
  documentado.

### D8. Configuração do caminho
`@ConfigurationProperties("skyledger.anac-data") record AnacDataProperties(String path)`
e, no `application.yaml`, `skyledger.anac-data.path: ${ANAC_DATA_PATH:}`. Um
caminho relativo é resolvido a partir do diretório de trabalho, que é a raiz
do repositório, como no `.env`. O `.env.example` ganha
`ANAC_DATA_PATH=../anac-data`. O valor é validado **na requisição**, e não na
inicialização, para que a aplicação suba mesmo sem a fonte configurada, como o
spec exige com o 503. A variável é `ANAC_DATA_PATH`, e não
`SKYLEDGER_ANAC_DATA_PATH`, porque o `.env` importado é uma fonte de
propriedades comum, sem o *relaxed binding* das variáveis de ambiente do SO.
O placeholder explícito funciona igual nas duas origens.

### D9. Testes isolados dos dados de dev
- **Schema separado:** em `src/test/resources/config/application.yaml`,
  `spring.flyway.default-schema=skyledger_test`,
  `spring.flyway.create-schemas=true` e
  `spring.datasource.hikari.schema=skyledger_test` (o `search_path` das
  conexões). As tabelas de teste ficam fora do schema `public` do banco de
  dev, e cada teste de ingestão faz `TRUNCATE` antes. Se a propriedade do
  Hikari não pegar com a conexão do Docker Compose, a alternativa é
  `currentSchema` na URL via `JdbcConnectionDetails` de teste.
  *Verificado no apply:* o `hikari.schema` funciona com a conexão do Docker
  Compose (`current_schema()` = `skyledger_test` no teste), e o `public` de
  dev ficou com 0 tabelas.
  *Achado no apply:* na suíte completa, um `SET search_path` + `RESET` via SQL
  no teste de rollback devolvia ao pool uma conexão em `public` (o `RESET`
  volta ao padrão do servidor, e o Hikari só restaura o schema quando a troca
  é feita pela API JDBC). Correção: `Connection.setSchema(...)` no teste e uma
  proteção em toda rotina de `TRUNCATE` de teste, que falha se o
  `current_schema()` não for `skyledger_test`.
- **Fixtures:** strings de CSV em arquivos de `src/test/resources/fixtures/`,
  copiadas em cada teste para um `@TempDir` com o layout
  `data/raw/anac/{vra,aerodromos}/`. O `skyledger.anac-data.path` aponta para
  esse diretório via `@DynamicPropertySource`. Nenhum caminho real no teste.
- **Por nível:**

| Cobertura | Tipo |
|-----------|------|
| Regras de valor e layout (D5, D6) | Unitário dos parsers, sobre `Reader` em memória |
| Endpoint, idempotência, ordem, enriquecimento, proveniência | Integração (`@SpringBootTest` com porta aleatória) |
| Falha de banco no meio do arquivo | Integração, com `@MockitoSpyBean` no repositório de voos lançando `DataAccessResourceFailureException` na inserção, verificando 503 e zero voos |
| Rollback V1 | Integração em schema descartável: `migrate`, `V1__down.sql`, nenhuma tabela, `migrate` de novo |
| PostgreSQL fora na inicialização | Integração: subir com o banco em porta sem serviço e esperar falha |
| Aceite com os arquivos reais (82102/18 etc.) | Manual, via endpoint com `ANAC_DATA_PATH=../anac-data`, porque não pode haver caminho real em teste |

- **Testes existentes:** `PostgresUnavailableHealthTest` e
  `DatabaseCredentialsCheckTest` sobem com o banco em `127.0.0.1:1`. Eles
  passam a usar `spring.flyway.enabled=false`, porque testam o health e a
  checagem de senha, não a migração.

### D10. Pacote e documentação
- Pacote `br.com.skyledger.ingestion`: controller, serviço, relatório,
  exceções e advice. Subpacote `ingestion.parser`: os dois parsers,
  `SourceFileHeader` e os modelos de linha. Subpacote `ingestion.persistence`:
  repositórios JDBC (`JdbcClient`).
- `docs/ingestao.md` (PT-BR): fonte e layout, regras do `null`, chave natural,
  contrato do endpoint com exemplo de relatório, rejeições e reprocessamento.
  `docs/aplicacao.md`: inicialização exige PostgreSQL, e a nova variável
  `ANAC_DATA_PATH`. Mais o link no `README.md`.

## Security & Observability

- **Segredos:** nenhum novo. `ANAC_DATA_PATH` é um caminho, não uma
  credencial, e vem do ambiente ou do `.env`.
- **Entrada não confiável:** o endpoint não recebe caminho nem corpo. Só lê a
  raiz configurada, então não há *path traversal* vindo do cliente. Arquivos
  são lidos em streaming, sem carregar tudo em memória.
- **Logs:** um resumo por arquivo (nome, contagens, duração) em `INFO`. Não
  há dados pessoais. As rejeições vão para o banco, não para o log.
- **Rastreamento:** não se aplica (sem filas nem chamadas entre serviços).
  `ingestion_batch` é o rastro de auditoria.

## Migration Plan

Primeira migração do projeto. Não há dados nem forma anterior, então só
existe a fase *expand*: não há dual-write, migração de dados nem *contract*.

| Passo | Artefato | Verificação |
|-------|----------|-------------|
| Expand | `V1__create_ingestion_schema.sql`: cria as 5 tabelas, constraints e FKs | Teste de inicialização: Flyway aplica V1 e uma segunda subida não reaplica |
| Down | `db/rollback/V1__down.sql`: `DROP TABLE` em ordem reversa de dependência (`flight`, `ingestion_rejection`, `ingestion_batch`, `airport`, `airline`) e remoção da linha da versão 1 em `flyway_schema_history` | Teste de rollback (D9): depois do down, nenhuma das 5 tabelas existe e um novo `migrate` reaplica V1 |

**Feature flag:** nenhuma. A ingestão só roda quando alguém chama o endpoint,
então não há comportamento novo disparado pelo deploy. O único efeito
automático do deploy são as migrações, que são o próprio objetivo da mudança
e têm rollback testado.

## Risks / Trade-offs

- [Uma correção retroativa da ANAC na partida real entra como voo novo (opção
  B)] → isso foi aceito na escolha da chave. O lote e o SHA-256 permitem
  identificar e tratar o caso se ele aparecer.
- [Ingestão síncrona de cerca de 250 mil linhas numa requisição HTTP] →
  streaming e staging. A meta de menos de 2 minutos é verificada no aceite, e
  a assíncrona fica como evolução.
- [Os testes reutilizam o PostgreSQL de dev] → schema `skyledger_test`
  separado, com `TRUNCATE` por teste. O `public` de dev nunca é tocado pelos
  testes.
- [Formato do VRA mudar (aspas escapadas, campos sem aspas)] → o parser
  estrito rejeita as linhas com motivo claro, em vez de gravar dado errado. A
  troca por uma biblioteca fica como evolução com aprovação.
- [Comportamento BREAKING da inicialização] → documentado na proposta e em
  `docs/aplicacao.md`. O health-check segue reportando a queda do banco
  depois da subida.
