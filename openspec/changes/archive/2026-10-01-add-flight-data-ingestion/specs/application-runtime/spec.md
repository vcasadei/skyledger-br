# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Inicialização independente da disponibilidade das dependências`
- TO: `### Requirement: Inicialização com esquema migrado e Redis opcional`

## MODIFIED Requirements

### Requirement: Conexão configurável com o PostgreSQL
A aplicação SHALL se conectar ao PostgreSQL usando host, porta, banco e
usuário cujos padrões são os do ambiente local (`localhost`, `5432`,
`skyledger`, `skyledger`), cada um sobrescrevível pelas variáveis de ambiente
`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB` e `POSTGRES_USER`. A senha MUST
vir exclusivamente de `POSTGRES_PASSWORD`, lida do ambiente ou do arquivo
`.env` da raiz do repositório. O ambiente tem precedência sobre o `.env`.
Nenhum valor de senha MUST estar no código, nos arquivos de configuração ou
nos testes. Sem `POSTGRES_PASSWORD`, a aplicação MUST falhar na
inicialização, com uma mensagem que cite `POSTGRES_PASSWORD`.

#### Scenario: Senha lida do `.env` local
- **AS A** desenvolvedor que copiou `.env.example` para `.env` e subiu o ambiente local
- **WHEN** inicio a aplicação sem exportar nenhuma variável
- **THEN** a aplicação conecta no banco `skyledger` do ambiente local
- **AND** o componente `db` do health-check reporta `UP`

#### Scenario: Variável de ambiente sobrescreve o `.env`
- **AS A** desenvolvedor com `.env` contendo `POSTGRES_PASSWORD=skyledger`
- **WHEN** inicio a aplicação com `POSTGRES_PASSWORD` exportada com outro valor
- **THEN** a aplicação usa o valor exportado
- **AND** se ele não for a senha do banco, a inicialização falha com código de saída diferente de zero e erro de autenticação do PostgreSQL

#### Scenario: Senha ausente
- **AS A** desenvolvedor sem `.env` e sem `POSTGRES_PASSWORD` no ambiente
- **WHEN** inicio a aplicação
- **THEN** a inicialização falha com código de saída diferente de zero
- **AND** a mensagem de erro cita `POSTGRES_PASSWORD`

### Requirement: Inicialização com esquema migrado e Redis opcional
Ao iniciar, a aplicação SHALL aplicar ao PostgreSQL as migrações de esquema
pendentes, em ordem de versão, antes de aceitar requisições. Se o PostgreSQL
estiver indisponível, ou se uma migração falhar, a inicialização MUST falhar
com código de saída diferente de zero, sem servir requisições com esquema
incompleto. O Redis MUST continuar opcional: com ele indisponível, a aplicação
inicia normalmente e a indisponibilidade aparece apenas no health-check. Com
a aplicação em execução, a queda e a recuperação do PostgreSQL ou do Redis
MUST ser refletidas no health-check sem reiniciar a aplicação.

#### Scenario: PostgreSQL fora na inicialização e depois recuperado
- **AS A** desenvolvedor que inicia a aplicação com o PostgreSQL parado
- **WHEN** a inicialização tenta aplicar as migrações, e depois subo o PostgreSQL e inicio a aplicação de novo
- **THEN** a primeira inicialização falha com código de saída diferente de zero, sem nenhuma porta HTTP aceitando requisições
- **AND** a segunda inicializa normalmente, e o health-check retorna HTTP 200 com `db` em `UP`

#### Scenario: Primeira inicialização com banco vazio
- **AS A** desenvolvedor com o banco `skyledger` vazio
- **WHEN** inicio a aplicação
- **THEN** todas as migrações são aplicadas e registradas no histórico de migrações
- **AND** uma segunda inicialização não reaplica nenhuma migração

#### Scenario: Redis fora na inicialização
- **AS A** desenvolvedor que inicia a aplicação com o Redis parado e o PostgreSQL de pé
- **WHEN** consulto o health-check
- **THEN** a aplicação está no ar e responde HTTP 200 com `redis` em `DEGRADED`

#### Scenario: PostgreSQL cai e volta com a aplicação em execução
- **AS A** desenvolvedor com a aplicação no ar
- **WHEN** paro o PostgreSQL, consulto o health-check, subo o PostgreSQL e consulto de novo
- **THEN** a primeira consulta retorna HTTP 503 com `db` em `DOWN`
- **AND** a segunda retorna HTTP 200 com `db` em `UP`, sem reiniciar a aplicação
