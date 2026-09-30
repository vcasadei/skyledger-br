# Proposal

## Why

O ambiente local (PostgreSQL, Redis e SeaweedFS, capability
`local-dev-environment`) já existe, mas ainda não há aplicação que o use. As
próximas mudanças (ingestão da ANAC, analytics com cache) precisam de um
ponto de partida comum: build Maven, aplicação Spring Boot que sobe, conexão
já configurada com o PostgreSQL e o Redis do ambiente local e um health-check
que diga se a aplicação está pronta. Sem isso, cada mudança teria que montar a
base por conta própria.

## What Changes

- Cria o projeto Maven na raiz do repositório (groupId `br.com.skyledger`,
  artifactId `skyledger`, versão `0.1.0-SNAPSHOT`), com Java 21, Spring Boot
  4.1 e Maven Wrapper (`./mvnw`), para que o build não dependa de um Maven
  instalado.
- Adiciona a classe principal `br.com.skyledger.SkyledgerApplication`.
- Configura a conexão com o PostgreSQL e o Redis **do ambiente local já
  existente**, sem redefinir nenhuma infraestrutura:
  - os padrões de host, porta, banco e usuário apontam para os serviços do
    `compose.yaml` e podem ser sobrescritos por variável de ambiente;
  - a senha do PostgreSQL não tem valor padrão no código. Ela vem de
    `POSTGRES_PASSWORD`, a mesma variável que o `compose.yaml` usa, lida do
    ambiente ou do `.env` local.
- Expõe o health-check `GET /actuator/health`:
  - PostgreSQL indisponível → status `DOWN` e HTTP 503;
  - Redis indisponível → o componente `redis` aparece como degradado, mas o
    status geral continua `UP`, porque o Redis é só cache e a aplicação deve
    funcionar sem ele.
- Expõe a documentação OpenAPI (`/v3/api-docs` e Swagger UI) incluindo o
  health-check, para que a convenção de OpenAPI valha desde o primeiro
  endpoint.
- Adiciona documentação de como compilar, rodar e verificar a aplicação.

Nenhuma mudança é **BREAKING**: ainda não existe aplicação nem consumidores.

## Capabilities

### New Capabilities
- `application-runtime`: aplicação Spring Boot executável, com configuração
  externa de conexão ao PostgreSQL e ao Redis, health-check e documentação
  OpenAPI.

### Modified Capabilities
<!-- Nenhuma. `local-dev-environment` é apenas consumido: a aplicação usa os
     serviços, portas e credenciais que ele já define, sem alterar seus
     requisitos. -->

## Impact

- **Arquivos novos**: `pom.xml`, Maven Wrapper (`mvnw`, `mvnw.cmd`,
  `.mvn/wrapper/`), código em `src/main/java/br/com/skyledger/`,
  `src/main/resources/application.yaml`, testes em `src/test/`, e
  documentação em `docs/`. O `compose.yaml` e o spec `local-dev-environment`
  **não mudam**.
- **Endpoints REST públicos** (cada um com documentação OpenAPI):
  - `GET /actuator/health`: health-check.
  - `GET /v3/api-docs`: especificação OpenAPI gerada.
  - `GET /swagger-ui.html`: interface da documentação.
- **Dependências**:
  - Spring Boot 4.1 (stack já definida): starters `webmvc`, `actuator`,
    `jdbc` e `data-redis`, driver PostgreSQL (`org.postgresql:postgresql`),
    e em escopo de teste os starters de teste e `spring-boot-docker-compose`.
    Este último é um módulo do próprio Spring Boot, que reutiliza o
    `compose.yaml` existente nos testes de integração.
  - **Nova de terceiros, aprovada:** `springdoc-openapi-starter-webmvc-ui`
    3.1.x, para gerar a documentação OpenAPI.
  - Pré-requisito para quem desenvolve: JDK 21 instalado. O Maven é baixado
    pelo wrapper.
- **Segredos**: nenhum novo. A aplicação passa a ler o `POSTGRES_PASSWORD`
  que já existe (ambiente ou `.env` local, gitignorado). Não há senha no
  código, nos arquivos de configuração nem nos testes.
- **Redis**: a aplicação só abre a conexão. Nada é armazenado (sem chaves)
  nesta mudança. Muda o comportamento em caso de indisponibilidade: com o Redis
  fora, a aplicação sobe e o health-check segue `UP`, marcando só o
  componente `redis` como degradado.
- **PostgreSQL / esquema de banco**: só a conexão (pool). Nenhuma tabela nem
  migração é criada.
- **Dados pessoais (LGPD), filas, valores monetários, anac-data**: não se
  aplica.
