# Proposal

## Why

Analytics de pontualidade e o simulador de risco dependem dos voos da ANAC no
banco, e hoje a aplicação não tem nenhuma tabela. Os arquivos do VRA (Voo
Regular Ativo) e o cadastro de Aeródromos Públicos já estão no repositório
`anac-data`. Esta mudança cria o esquema e a ingestão desses arquivos, de modo
idempotente: reprocessar um arquivo, ou receber de novo o mesmo voo em
arquivos de meses vizinhos, não duplica registros.

## What Changes

- Cria o esquema inicial do banco com migrações versionadas (Flyway):
  - `airline`: companhias aéreas por código ICAO;
  - `airport`: aeroportos por código ICAO;
  - `flight`: voos, um por linha do VRA;
  - `ingestion_batch` e `ingestion_rejection`: proveniência e linhas
    rejeitadas.
- **Ingestão do cadastro de Aeródromos Públicos**
  (`AerodromosPublicos.csv`, ISO-8859-1, campos sem aspas). Preenche nome,
  município, UF, latitude, longitude, altitude e situação de registro dos
  aeródromos cadastrados. Os aeroportos que só aparecem no VRA (estrangeiros
  e brasileiros fora do cadastro) entram só com o código.
- **Ingestão dos arquivos mensais do VRA** (`VRA_<ano><mês>.csv`, UTF-8 com
  BOM, `;`, metadado na linha 1 e cabeçalho na linha 2), com:
  - colunas mapeadas pelo nome do cabeçalho, não pela posição;
  - o literal `null` nos horários convertido em ausência de valor, conforme a
    situação. Um REALIZADO sem horário previsto continua sendo um voo
    realizado, nunca um cancelamento;
  - fração `.100000000` dos horários previstos descartada;
  - número do voo e código DI tratados como texto (`Z9610`, `0073`);
  - linhas inválidas rejeitadas e registradas, sem interromper o arquivo.
- **Chave natural do voo:** (empresa, número do voo, aeroporto de origem,
  partida prevista, partida real), com valores ausentes tratados como iguais
  entre si. Duplicatas exatas dentro do arquivo, reprocessamentos e a
  sobreposição entre meses não geram registro novo.
- **Endpoint `POST /api/ingestions`**: processa o cadastro e depois os
  arquivos do VRA do `anac-data`, e devolve um relatório por arquivo (linhas
  lidas, inseridas, duplicadas e rejeitadas).
- **BREAKING** (comportamento de inicialização, capability
  `application-runtime`): a aplicação passa a aplicar as migrações ao iniciar e
  **não inicia mais com o PostgreSQL indisponível**, nem com senha errada,
  porque falha rápido em vez de subir sem esquema. O Redis continua opcional
  na subida e a regra do health-check não muda.
  *Caminho de migração:* não há consumidores além do ambiente local, então
  não há período de depreciação. Quem roda a aplicação passa a precisar do
  PostgreSQL de pé antes (`docker compose up -d --wait`), como já indica a
  documentação. O health-check segue reportando `db` `DOWN`/503 quando o
  banco cai com a aplicação em execução.

## Capabilities

### New Capabilities
- `flight-data-ingestion`: leitura e validação dos arquivos do VRA e do
  cadastro de Aeródromos Públicos, persistência idempotente de companhias,
  aeroportos e voos, registro de proveniência e rejeições, e o endpoint que
  dispara a ingestão.

### Modified Capabilities
- `application-runtime`: a inicialização passa a exigir o PostgreSQL
  disponível (migrações na subida). Mudam o requisito "Inicialização
  independente da disponibilidade das dependências" (renomeado) e o cenário
  de senha errada em "Conexão configurável com o PostgreSQL".

## Impact

- **Endpoints REST públicos** (com documentação OpenAPI):
  - `POST /api/ingestions`: dispara a ingestão e devolve o relatório.
- **Dados do `anac-data`**:
  - Conjuntos lidos: VRA (`data/raw/anac/vra/VRA_*.csv`) e Aeródromos Públicos
    (`data/raw/anac/aerodromos/AerodromosPublicos.csv`). O Dados Estatísticos
    não é lido.
  - Propriedade de configuração **nova**: `skyledger.anac-data.path`, raiz do
    repositório `anac-data`, sobrescrevível pela variável `ANAC_DATA_PATH`
    (ambiente ou `.env`). Não tem valor padrão no código.
- **Esquema de banco:** tabelas novas `airline`, `airport`, `flight`,
  `ingestion_batch` e `ingestion_rejection`, além do histórico do Flyway
  (`flyway_schema_history`). Nenhuma tabela existente é alterada.
- **Dependências novas, aprovadas:** Flyway (`spring-boot-starter-flyway` e
  `flyway-database-postgresql`, versão gerenciada pelo Spring Boot 4.1). O
  parser de CSV é próprio, sem biblioteca nova.
- **Comportamento de inicialização:** a mudança **BREAKING** acima. Os testes
  existentes que sobem a aplicação apontando para um banco inexistente
  precisam desligar as migrações.
- **Redis e SeaweedFS:** não são tocados. Nada é armazenado neles, e o
  comportamento em caso de indisponibilidade não muda.
- **Dados pessoais (LGPD):** não se aplica. Os arquivos trazem só voos,
  empresas e aeroportos, sem passageiros ou tripulação.
- **Valores monetários, filas e segredos:** não se aplica. Nenhum segredo
  novo: o `ANAC_DATA_PATH` é um caminho, não uma credencial.
