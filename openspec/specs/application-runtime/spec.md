# application-runtime Specification

## Purpose

Define a aplicação Spring Boot do Skyledger BR como processo executável: como
é construída, como se conecta ao PostgreSQL e ao Redis, como reporta a própria
saúde e como publica a documentação OpenAPI dos seus endpoints.

## Requirements

### Requirement: Build reproduzível com o wrapper do projeto
O repositório SHALL conter um projeto Maven na raiz que compila, testa e
empacota a aplicação com `./mvnw verify`, usando apenas um JDK 21 instalado,
sem Maven instalado na máquina. O build MUST falhar logo no início, com uma
mensagem que cite Java 21, quando executado com um JDK de versão menor.

#### Scenario: Build com JDK 21
- **AS A** desenvolvedor com JDK 21 e sem Maven instalado
- **WHEN** executo `./mvnw verify` na raiz do repositório
- **THEN** o build termina com sucesso
- **AND** um JAR executável da aplicação é gerado em `target/`

#### Scenario: Build com JDK antigo
- **AS A** desenvolvedor cujo `java` padrão é um JDK 17
- **WHEN** executo `./mvnw verify`
- **THEN** o build falha antes de compilar
- **AND** a mensagem de erro informa que é necessário Java 21

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
- **AND** se ele não for a senha do banco, o componente `db` reporta `DOWN`

#### Scenario: Senha ausente
- **AS A** desenvolvedor sem `.env` e sem `POSTGRES_PASSWORD` no ambiente
- **WHEN** inicio a aplicação
- **THEN** a inicialização falha com código de saída diferente de zero
- **AND** a mensagem de erro cita `POSTGRES_PASSWORD`

### Requirement: Conexão configurável com o Redis
A aplicação SHALL se conectar ao Redis em `localhost:6379`, sem senha, por
padrão, com host e porta sobrescrevíveis pelas variáveis de ambiente
`REDIS_HOST` e `REDIS_PORT`.

#### Scenario: Redis do ambiente local
- **AS A** desenvolvedor com o ambiente local de pé
- **WHEN** inicio a aplicação sem variáveis de Redis
- **THEN** o componente `redis` do health-check reporta `UP`

#### Scenario: Redis apontado para endereço sem serviço
- **AS A** desenvolvedor que exporta `REDIS_PORT` para uma porta sem Redis
- **WHEN** inicio a aplicação
- **THEN** a aplicação sobe normalmente
- **AND** o componente `redis` do health-check reporta `DEGRADED`

### Requirement: Inicialização independente da disponibilidade das dependências
A aplicação SHALL iniciar e responder ao health-check mesmo quando o
PostgreSQL ou o Redis estiverem indisponíveis. A indisponibilidade MUST
aparecer apenas no health-check, e a recuperação de uma dependência MUST ser
refletida no health-check sem reiniciar a aplicação.

#### Scenario: PostgreSQL fora na inicialização e depois recuperado
- **AS A** desenvolvedor que inicia a aplicação com o PostgreSQL parado
- **WHEN** consulto o health-check, subo o PostgreSQL e consulto de novo
- **THEN** a primeira consulta retorna HTTP 503 com `db` em `DOWN`
- **AND** a segunda retorna HTTP 200 com `db` em `UP`, sem reiniciar a aplicação

### Requirement: Health-check
A aplicação SHALL expor `GET /actuator/health` na porta HTTP da aplicação
(padrão `8080`, sobrescrevível por `SERVER_PORT`). A resposta MUST ser um JSON
com `status` e com `components`, que contém ao menos `db` e `redis`, cada um
com seu próprio `status`. As regras do status geral são:

| `db` | `redis` | `status` geral | HTTP |
|------|---------|----------------|------|
| `UP` | `UP` | `UP` | 200 |
| `UP` | `DEGRADED` | `UP` | 200 |
| `DOWN` | qualquer | `DOWN` | 503 |

O componente `redis` MUST reportar `DEGRADED`, e nunca `DOWN`, quando o Redis
estiver indisponível. A resposta MUST NOT conter detalhes das dependências
(hosts, portas, versões, nomes de banco ou credenciais). Nenhum outro endpoint
do Actuator MUST ficar exposto via HTTP. O endpoint aceita só `GET`.

#### Scenario: Todas as dependências disponíveis
- **AS A** orquestrador ou desenvolvedor
- **WHEN** envio `GET /actuator/health` com PostgreSQL e Redis de pé
- **THEN** recebo HTTP 200 com `status` `UP`
- **AND** `components.db.status` e `components.redis.status` são `UP`

#### Scenario: Redis indisponível
- **AS A** orquestrador
- **WHEN** envio `GET /actuator/health` com o PostgreSQL de pé e o Redis parado
- **THEN** recebo HTTP 200 com `status` `UP`
- **AND** `components.redis.status` é `DEGRADED`

#### Scenario: PostgreSQL indisponível
- **AS A** orquestrador
- **WHEN** envio `GET /actuator/health` com o PostgreSQL parado
- **THEN** recebo HTTP 503 com `status` `DOWN`
- **AND** `components.db.status` é `DOWN`

#### Scenario: Resposta sem detalhes sensíveis
- **AS A** cliente qualquer
- **WHEN** envio `GET /actuator/health`
- **THEN** o corpo não contém host, porta, versão, nome de banco nem credencial de nenhuma dependência

#### Scenario: Outros endpoints do Actuator não expostos
- **AS A** cliente qualquer
- **WHEN** envio `GET /actuator/env` ou `GET /actuator/beans`
- **THEN** recebo HTTP 404

#### Scenario: Método não suportado
- **AS A** cliente qualquer
- **WHEN** envio `POST /actuator/health`
- **THEN** recebo HTTP 405

### Requirement: Documentação OpenAPI
A aplicação SHALL publicar a especificação OpenAPI 3 dos seus endpoints REST
públicos em `GET /v3/api-docs`, em JSON, e uma interface de consulta em
`GET /swagger-ui.html`. A especificação MUST incluir a operação
`GET /actuator/health` com as respostas 200 e 503.

#### Scenario: Health-check documentado
- **AS A** consumidor da API
- **WHEN** envio `GET /v3/api-docs`
- **THEN** recebo HTTP 200 com um documento cujo campo `openapi` começa com `3.`
- **AND** `paths` contém `/actuator/health` com a operação `get` e as respostas `200` e `503`

#### Scenario: Interface da documentação
- **AS A** desenvolvedor no navegador
- **WHEN** abro `/swagger-ui.html`
- **THEN** a interface carrega e lista a operação `GET /actuator/health`

### Requirement: Credenciais fora dos logs
A aplicação SHALL NOT escrever o valor de `POSTGRES_PASSWORD`, nem nenhuma
URL de conexão que contenha credenciais, em logs, em nenhum nível configurado
por padrão.

#### Scenario: Log de inicialização
- **AS A** desenvolvedor que inicia a aplicação com `POSTGRES_PASSWORD` definida
- **WHEN** leio a saída completa da inicialização
- **THEN** o valor da senha não aparece em nenhuma linha
