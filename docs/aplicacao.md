# Aplicação Skyledger BR

Aplicação Spring Boot (Java 21, Maven) do Skyledger BR. Ela usa o PostgreSQL e o
Redis do [ambiente local](ambiente-local.md) e expõe um health-check e a
documentação OpenAPI dos seus endpoints.

## Pré-requisitos

| Item | Versão | Observação |
|------|--------|------------|
| JDK | 21 | `java -version` deve informar 21. Com versão menor, o build falha logo no início pedindo Java 21 |
| Maven | não precisa | O Maven Wrapper (`./mvnw`) baixa o Maven 3.9.16 na primeira execução |
| Docker + Compose | ver [ambiente local](ambiente-local.md) | Necessário para rodar a aplicação e para os testes de integração |

## Build

| Comando | Efeito |
|---------|--------|
| `./mvnw verify` | Compila (warnings viram erro), roda os testes unitários e de integração e empacota |
| `./mvnw -DskipTests package` | Só empacota, sem testes |

O JAR executável é gerado em `target/skyledger-0.1.0-SNAPSHOT.jar`.

## Execução

```bash
cp .env.example .env            # uma vez: a aplicação exige POSTGRES_PASSWORD
docker compose up -d --wait     # ambiente local (PostgreSQL, Redis, SeaweedFS)
./mvnw spring-boot:run          # sempre a partir da raiz do repositório
```

A aplicação escuta em `http://localhost:8080`. Rode sempre a partir da raiz: o
`.env` é lido do diretório de trabalho. Sem `POSTGRES_PASSWORD`, a
inicialização falha com `POSTGRES_PASSWORD não definida: exporte a variável ou
crie o .env a partir de .env.example`.

## Variáveis de ambiente

As variáveis vêm do ambiente do SO ou do `.env` da raiz, que é o mesmo arquivo
do `compose.yaml`. **O ambiente tem precedência sobre o `.env`.**

| Variável | Padrão | Uso |
|----------|--------|-----|
| `POSTGRES_HOST` | `localhost` | Host do PostgreSQL |
| `POSTGRES_PORT` | `5432` | Porta do PostgreSQL |
| `POSTGRES_DB` | `skyledger` | Banco |
| `POSTGRES_USER` | `skyledger` | Usuário |
| `POSTGRES_PASSWORD` | **nenhum (obrigatória)** | Senha do usuário |
| `REDIS_HOST` | `localhost` | Host do Redis (sem senha) |
| `REDIS_PORT` | `6379` | Porta do Redis |
| `SERVER_PORT` | `8080` | Porta HTTP da aplicação |

Timeouts de conexão: 3s no PostgreSQL (pool Hikari) e 2s no Redis. Com uma
dependência fora, o health-check responde nesse prazo em vez de travar.

## Health-check

`GET /actuator/health` é o único endpoint do Actuator exposto. Ele responde só
`GET`: outros métodos recebem `405`, e outros caminhos do Actuator, `404`.

| `db` (PostgreSQL) | `redis` | `status` geral | HTTP |
|-------------------|---------|----------------|------|
| `UP` | `UP` | `UP` | 200 |
| `UP` | `DEGRADED` | `UP` | 200 |
| `DOWN` | qualquer | `DOWN` | 503 |

- `DEGRADED` quer dizer que o Redis está indisponível. Como ele é só cache, a
  aplicação continua atendendo e o status geral fica `UP`. O `redis` nunca
  reporta `DOWN`.
- A resposta não traz detalhes (hosts, portas, versões, banco): só os status.
- A aplicação sobe mesmo com PostgreSQL ou Redis fora. Quando a dependência
  volta, o health-check reflete isso sem reiniciar.

Exemplo, com tudo de pé:

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" },
    "redis": { "status": "UP" },
    "ssl": { "status": "UP" }
  }
}
```

## Documentação OpenAPI

| Endereço | Conteúdo |
|----------|----------|
| `GET /v3/api-docs` | Especificação OpenAPI 3 (JSON) gerada pela aplicação |
| `GET /swagger-ui.html` | Interface de consulta (redireciona para `/swagger-ui/index.html`) |

Hoje a especificação documenta `GET /actuator/health`: respostas `200` e `503`
e o schema `HealthResponse`.

**Regra do projeto:** todo endpoint REST público novo precisa aparecer em
`/v3/api-docs`, com parâmetros, schemas e respostas de erro, e precisa de um
teste que verifique isso na especificação gerada (como o
`OpenApiDocumentationTest`).

## Verificações manuais

Os cenários abaixo não estão nos testes automatizados, porque exigem mexer em
arquivos da raiz, parar serviços, trocar de JDK ou usar um navegador. Todos
rodam da raiz, com `.env` criado e o ambiente local de pé.

| Cenário | Como verificar | Esperado |
|---------|----------------|----------|
| Senha lida do `.env` | `./mvnw spring-boot:run`, sem exportar nada | `/actuator/health` → 200, `db` `UP` |
| Ambiente sobrescreve o `.env` | `POSTGRES_PASSWORD=errada ./mvnw spring-boot:run` | 503, `db` `DOWN` |
| PostgreSQL fora na inicialização | `docker compose stop postgres`, subir a app e depois `docker compose start postgres` | 503, depois 200 sem reiniciar |
| Redis em endereço sem serviço | `REDIS_PORT=6390 ./mvnw spring-boot:run` | 200, `redis` `DEGRADED` |
| Build com JDK antigo | `docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/w -w /w eclipse-temurin:17 ./mvnw -q validate` | Falha antes de compilar, pedindo Java 21 |
| Swagger UI | Abrir `http://localhost:8080/swagger-ui.html` no navegador | Lista `GET /actuator/health` |
