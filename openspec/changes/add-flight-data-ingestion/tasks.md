# Tasks

## 1. Migração do esquema e inicialização com Flyway

- [ ] 1.1 Adicionar `spring-boot-starter-flyway` e `flyway-database-postgresql` ao `pom.xml`, com versão gerenciada pelo Boot (D1). Verificar com `./mvnw -q -DskipTests package`
- [ ] 1.2 Isolar os testes em `src/test/resources/config/application.yaml` com `spring.flyway.default-schema`/`create-schemas` e `spring.datasource.hikari.schema` = `skyledger_test` (D9). Verificar que um teste de contexto cria as tabelas em `skyledger_test` e nada em `public`, consultando `information_schema.tables`. Se o Hikari não aplicar o schema com a conexão do Docker Compose, aplicar a alternativa do D9 e registrá-la no `design.md`
- [ ] 1.3 Expand: criar `db/migration/V1__create_ingestion_schema.sql` com as 5 tabelas, a `UNIQUE NULLS NOT DISTINCT` e a `CHECK` de situação×horários (D2). Verificar com teste de integração que as tabelas e as constraints existem após a subida
- [ ] 1.4 Teste do cenário "Primeira inicialização com banco vazio": V1 aplicada e registrada no histórico do Flyway, e uma segunda subida do contexto não reaplica nenhuma migração
- [ ] 1.5 Down: criar `db/rollback/V1__down.sql` (D1 e Migration Plan) e um teste que, num schema descartável, roda `migrate`, executa o down, verifica que nenhuma das 5 tabelas existe e que um novo `migrate` reaplica V1
- [ ] 1.6 Teste de constraint: inserir diretamente uma linha `CANCELADO` com `actual_departure` preenchida falha na `flight_status_times`, e duas linhas com a mesma chave e `actual_departure` nula falham na `flight_natural_key`
- [ ] 1.7 Ajustar `PostgresUnavailableHealthTest` e `DatabaseCredentialsCheckTest` com `spring.flyway.enabled=false` (D9), e verificar que continuam passando
- [ ] 1.8 Teste do cenário "PostgreSQL fora na inicialização e depois recuperado" (parte automatizável): com o banco em porta sem serviço e o Flyway ligado, a inicialização falha. Adicionar à documentação a parte manual (subir o banco e iniciar de novo)
- [ ] 1.9 Atualizar `docs/aplicacao.md`: a inicialização exige o PostgreSQL de pé e aplica as migrações; o Redis continua opcional; a verificação manual "PostgreSQL fora na inicialização" muda para o comportamento novo

## 2. Configuração e descoberta da fonte de dados

- [ ] 2.1 Criar `AnacDataProperties` (`skyledger.anac-data.path`) e, no `application.yaml`, `skyledger.anac-data.path: ${ANAC_DATA_PATH:}` (D8), sem caminho padrão. Verificar com `grep -rn anac-data src/main` mostrando só o placeholder
- [ ] 2.2 Registrar `ANAC_DATA_PATH=../anac-data` com comentário no `.env.example` (D8)
- [ ] 2.3 Implementar a resolução da fonte: raiz configurada, cadastro em `data/raw/anac/aerodromos/AerodromosPublicos.csv` e VRA em `data/raw/anac/vra/`, com `VRA_<AAAA><M>.csv` ordenados por ano e mês numéricos, arquivos fora do padrão ignorados e `AnacDataUnavailableException` para propriedade vazia, raiz inexistente, cadastro ausente ou nenhum VRA
- [ ] 2.4 Testes unitários da resolução, com a raiz apontada para um `@TempDir`: ordem de `VRA_20269.csv` antes de `VRA_202610.csv`, arquivo `outro.csv` ignorado, e exceção com mensagem citando a propriedade, o caminho ou a ausência de VRA para cada caso de fonte inutilizável

## 3. Parser do VRA

- [ ] 3.1 Criar `SourceFileHeader` (metadado e mapeamento do cabeçalho por nome, D5) e `VraFileParser` (UTF-8, BOM opcional, regex de 12 campos entre aspas), e o modelo de linha válida ou rejeitada, com número da linha física
- [ ] 3.2 Implementar as regras de valor do D6: horários com fração descartada e `null` como ausência, tabela situação×nulos, códigos de empresa e aeroporto, e textos preservados
- [ ] 3.3 Testes unitários de layout: "Colunas em outra ordem", "Metadado ausente", "Coluna ausente no cabeçalho" e "Arquivo sem BOM"
- [ ] 3.4 Testes unitários de valor: "Voo realizado sem horário previsto", "Voo cancelado", "Fração de segundo no horário", "Número do voo alfanumérico e com zero à esquerda", "Combinação inválida de situação e horários", "Horário mal formado" e "Número de campos incorreto", além de situação desconhecida e código de aeroporto inválido

## 4. Parser do cadastro de Aeródromos Públicos

- [ ] 4.1 Criar `AerodromeFileParser` (ISO-8859-1, `split(";", -1)` com 18 campos, aspa literal, reaproveitando o `SourceFileHeader`), com coordenadas e altitude em `BigDecimal` (D6)
- [ ] 4.2 Testes unitários: linha de `SBRB` com os valores exatos do cenário "Aeródromo cadastrado" (incluindo a latitude DMS com aspa), "Município ausente", "Altitude mal formada", cabeçalho com 17 colunas rejeitando o arquivo, e escala de 9 casas preservada (`0.050555556`)

## 5. Persistência e serviço de ingestão

- [ ] 5.1 Repositórios JDBC (`JdbcClient`) para staging temporária, `airline`/`airport` com `ON CONFLICT DO NOTHING`, `flight` com `INSERT ... SELECT ... ON CONFLICT DO NOTHING` e contagem, upsert do cadastro com `RETURNING (xmax = 0)`, e `ingestion_batch`/`ingestion_rejection` (D3, D4)
- [ ] 5.2 `IngestionService`: cadastro e depois VRA em ordem, uma transação por arquivo, lote `REJECTED` para layout inválido seguindo com os demais, até 100 rejeições no relatório e todas no banco, e log `INFO` de resumo por arquivo
- [ ] 5.3 Testes de integração, com fixtures copiadas para um `@TempDir`, dos cenários "Reprocessamento do mesmo arquivo", "Linha duplicada dentro do arquivo", "Mesmo voo em dois meses", "Mesmo voo previsto com partidas reais diferentes" e "Cancelado reprocessado"
- [ ] 5.4 Testes de integração de "Aeroporto estrangeiro", "Companhia nova", "Aeroporto criado pelo VRA e enriquecido depois" e "Reprocessamento do cadastro" (`updated` e nenhum aeroporto duplicado)
- [ ] 5.5 Testes de integração de "Histórico de reprocessamentos" (dois lotes, mesmo SHA-256, segundo com `inserted` 0) e "Voo rastreável até o arquivo" (voo → lote → arquivo e `sourceUpdatedAt`), mais uma rejeição persistida com linha e motivo
- [ ] 5.6 Teste de integração de "Falha de banco no meio do arquivo": `@MockitoSpyBean` no repositório de voos lançando `DataAccessResourceFailureException` resulta em exceção de banco e zero voos do arquivo gravados (o 503 HTTP é verificado na 6.4)

## 6. Endpoint REST, OpenAPI e documentação

- [ ] 6.1 `IngestionController` (`POST /api/ingestions`), os records do `IngestionReport`, `@RestControllerAdvice` com `ProblemDetail` (409, 503) e o `ReentrantLock` do serviço (D7)
- [ ] 6.2 Teste de integração de "Ingestão completa" com fixtures em `@TempDir` (4 arquivos na ordem, `COMPLETED`, contagens das fixtures) e de "Ordem cronológica com mês sem zero à esquerda" pela resposta HTTP
- [ ] 6.3 Testes de integração de "Caminho não configurado", "Caminho inexistente" e "Fonte sem arquivos do VRA": 503 `application/problem+json` com o `detail` esperado, e nenhuma linha em nenhuma tabela
- [ ] 6.4 Testes de integração de "Ingestão concorrente" (lock tomado no teste resulta em 409), "Método não suportado" (`GET` resulta em 405) e "Falha de banco no meio do arquivo" pelo endpoint (503)
- [ ] 6.5 Documentar `POST /api/ingestions` na OpenAPI: resumo, descrição, schema do `IngestionReport` e respostas 200, 409 e 503 com `ProblemDetail`. Teste em `/v3/api-docs` verificando a operação, as três respostas e o schema na especificação gerada
- [ ] 6.6 Criar `docs/ingestao.md` (PT-BR): fonte e `ANAC_DATA_PATH`, layout dos dois arquivos, tabela do `null`, chave natural, contrato do endpoint com exemplo de relatório, reprocessamento e consulta de lotes e rejeições. Atualizar `docs/aplicacao.md` (variável `ANAC_DATA_PATH`) e o link no `README.md`

## 7. Aceite com os dados reais

- [ ] 7.1 Com `ANAC_DATA_PATH=../anac-data`, banco recriado (`docker compose down -v` e `up`) e a aplicação no ar, `POST /api/ingestions` responde 200 com o cadastro `inserted` 496 e os VRA `inserted`/`duplicates` = 82102/18, 81117/2 e 89506/11, `rejected` 0 em todos, em menos de 2 minutos
- [ ] 7.2 Conferir no banco: 252725 voos, 87 companhias, 632 aeroportos (496 com `in_public_registry`), 7127 voos `REALIZADO` sem partida prevista e 8004 `CANCELADO`
- [ ] 7.3 Segundo `POST /api/ingestions`: VRA com `inserted` 0 e `duplicates` = `rowsRead`, cadastro com `updated` 496, e as contagens do banco iguais às da 7.2
- [ ] 7.4 Seguir `docs/ingestao.md` do zero, exatamente como escrito, e confirmar que todos os comandos funcionam

## 8. Quality Gates

- [ ] 8.1 Build completo com todos os testes e compilação sem warnings, com Docker em execução: `./mvnw verify`
- [ ] 8.2 Registrar que não há ferramenta de cobertura nem linter configurados (seriam dependências novas não aprovadas): o gate de análise estática é o `-Xlint:all -Werror` da 8.1
- [ ] 8.3 Nenhum caminho fixo para o `anac-data`: `git grep -nE 'anac-data|/mnt/|C:\\\\' -- src` mostra só o placeholder do `application.yaml` e nomes de pacote ou documentação. Nenhum `.env` rastreado: `git ls-files | grep -x '.env'` vazio
- [ ] 8.4 Os testes não tocam os dados de dev: com o banco recriado (`docker compose down -v` e `up`), rodar `./mvnw verify` e confirmar que o schema `public` não tem nenhuma das tabelas da V1, e que elas e as linhas das fixtures existem só em `skyledger_test`
