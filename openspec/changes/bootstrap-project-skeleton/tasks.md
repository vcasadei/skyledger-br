# Tasks

## 1. Projeto Maven e classe principal

- [x] 1.1 Confirmar o JDK 21 instalado (`java -version` informa 21), pré-requisito registrado na proposta
- [x] 1.2 Criar `pom.xml` com parent `spring-boot-starter-parent` 4.1.1, `br.com.skyledger:skyledger:0.1.0-SNAPSHOT`, `java.version` 21, os starters da D2, `maven-enforcer-plugin` com `requireJavaVersion [21,)` (D1) e compilador com `-Xlint:all,-processing -Werror` (D8). Verificar com `./mvnw -q validate`
- [x] 1.3 Gerar o Maven Wrapper 3.3.4 apontando para a versão 3.9.x mais recente do Maven (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`), e verificar que `./mvnw -v` funciona sem Maven instalado
- [x] 1.4 Criar `br.com.skyledger.SkyledgerApplication` com Javadoc, e verificar com `./mvnw -q -DskipTests package` gerando o JAR executável em `target/` (cenário "Build com JDK 21", parte de empacotamento)
- [x] 1.5 Verificar manualmente o cenário "Build com JDK antigo" com `docker run --rm -v "$PWD":/w -w /w eclipse-temurin:17 ./mvnw -q validate`: o build falha citando Java 21 antes de compilar
- [x] 1.6 Criar `docs/aplicacao.md` (PT-BR) com pré-requisitos (JDK 21, Docker) e a seção de build (`./mvnw verify`, JAR em `target/`), e adicionar o link no `README.md`. Verificar que o link resolve

## 2. Configuração de conexão com PostgreSQL e Redis

- [x] 2.1 Criar `src/main/resources/application.yaml` com o import de `.env`, datasource, Redis e timeouts da D3, sem nenhum valor de senha. Verificar com `grep -n password src/main/resources/application.yaml`, que deve mostrar só `${POSTGRES_PASSWORD}`
- [x] 2.2 Verificar se o Spring Boot 4.1 falha sozinho, citando `POSTGRES_PASSWORD`, quando a variável falta. Se não falhar, criar `br.com.skyledger.config.DatabaseCredentialsCheck` com Javadoc, validando o `JdbcConnectionDetails` efetivo (D4). Registrar o resultado na D4 do `design.md`
- [x] 2.3 Teste de integração para o cenário "Senha ausente": contexto iniciado sem `POSTGRES_PASSWORD` e com o Compose desligado falha, e a mensagem cita `POSTGRES_PASSWORD`
- [x] 2.4 Teste de integração para o cenário "Log de inicialização": com senha aleatória gerada no teste, `OutputCaptureExtension` confirma que o valor não aparece na saída
- [x] 2.5 Configurar `spring-boot-docker-compose` em escopo de teste com `skip.in-tests=false` e `lifecycle-management=start-only` (D6), e verificar que um teste de contexto sobe conectado ao `compose.yaml` existente sem credencial no código de teste
- [x] 2.6 Atualizar só o comentário de `POSTGRES_PASSWORD` em `.env.example` (a aplicação também lê) e documentar em `docs/aplicacao.md` a tabela de variáveis (`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `SERVER_PORT`), com padrões e precedência ambiente > `.env`

## 3. Health-check

- [x] 3.1 Configurar em `application.yaml` a exposição apenas de `health`, `show-components: always`, `show-details: never`, `status.order` com `DEGRADED` e `health.redis.enabled: false` (D5)
- [x] 3.2 Confirmar a classe do indicador de Redis no Boot 4.1 e criar `br.com.skyledger.health.RedisDegradableHealthIndicator` (bean `redis`, com Javadoc), que delega a ela e mapeia `DOWN` para `DEGRADED`. Registrar o nome da classe na D5
- [x] 3.3 Teste unitário do indicador: delegate `UP` resulta em `UP`, e delegate `DOWN` ou com exceção resulta em `DEGRADED`
- [x] 3.4 Teste de integração para o cenário "Todas as dependências disponíveis": 200, `status` `UP`, `db` e `redis` `UP`
- [x] 3.5 Teste de integração para o cenário "Redis indisponível": `RedisConnectionFactory` com `@MockitoBean` falhando resulta em 200, `UP` e `redis` `DEGRADED`
- [x] 3.6 Teste de integração para o cenário "PostgreSQL indisponível": URL para porta sem serviço e senha aleatória resultam em 503, `DOWN` e `db` `DOWN`, com resposta em menos de 10s (timeouts da D3)
- [x] 3.7 Testes de integração para os cenários "Resposta sem detalhes sensíveis" (corpo sem `localhost`, `5432`, `6379`, `skyledger`, `version`, `details`), "Outros endpoints do Actuator não expostos" (404 em `/actuator/env` e `/actuator/beans`) e "Método não suportado" (405 em `POST /actuator/health`)
- [x] 3.8 Documentar em `docs/aplicacao.md` o contrato do health-check: tabela `db`/`redis` → status → HTTP, exemplo de resposta e o significado de `DEGRADED`

## 4. Documentação OpenAPI

- [x] 4.1 Configurar o springdoc com `springdoc.show-actuator: true` e verificar em `GET /v3/api-docs` que `/actuator/health` aparece
- [x] 4.2 Documentar a operação `GET /actuator/health` na OpenAPI: resumo, respostas 200 e 503 e schema do corpo. Se o springdoc não gerar a 503, criar `OpenApiCustomizer` com Javadoc em `br.com.skyledger.config` (D7)
- [x] 4.3 Teste de integração para o cenário "Health-check documentado": `GET /v3/api-docs` retorna 200, `openapi` começa com `3.`, e `paths./actuator/health.get.responses` tem `200` e `503`
- [x] 4.4 Teste de integração para a parte automatizável do cenário "Interface da documentação": `GET /swagger-ui.html` retorna 200 ou redireciona para a UI, e o carregamento no navegador fica para a 5.4
- [x] 4.5 Documentar em `docs/aplicacao.md` os endereços `/v3/api-docs` e `/swagger-ui.html` e a regra de que todo endpoint público novo precisa aparecer na especificação gerada

## 5. Verificação integrada manual

- [x] 5.1 Cenário "Senha lida do `.env` local": com `cp .env.example .env`, `docker compose up -d --wait` e `./mvnw spring-boot:run`, o health-check retorna 200 com `db` `UP`
- [x] 5.2 Cenário "Variável de ambiente sobrescreve o `.env`": `POSTGRES_PASSWORD=errada ./mvnw spring-boot:run` resulta em health-check 503 com `db` `DOWN`
- [x] 5.3 Cenários "PostgreSQL fora na inicialização e depois recuperado" e "Redis apontado para endereço sem serviço": com `docker compose stop postgres`, subir a aplicação resulta em 503; `docker compose start postgres` leva a 200 sem reiniciar. Com `REDIS_PORT=6390`, a aplicação sobe com `redis` `DEGRADED`
- [ ] 5.4 Cenário "Interface da documentação": abrir `http://localhost:8080/swagger-ui.html` no navegador e ver `GET /actuator/health` listado
- [x] 5.5 Seguir `docs/aplicacao.md` do zero, exatamente como escrito, e confirmar que todos os comandos funcionam

## 6. Quality Gates

- [x] 6.1 Build completo, com compilação sem warnings (`-Werror`) e todos os testes, com Docker em execução: `./mvnw verify`
- [x] 6.2 Registrar que não há ferramenta de cobertura nem linter configurados (seriam dependências novas não aprovadas): o gate de análise estática é o `-Xlint:all -Werror` da 6.1
- [x] 6.3 Nenhum segredo versionado: `git ls-files | grep -x '.env'` não retorna nada, e `git grep -nE 'password\s*[:=]\s*[^$ ]' -- src` não retorna valores literais
- [x] 6.4 O ambiente local continua intacto: `git diff --stat feat/setup-local-infra/3-archive -- compose.yaml openspec/specs/local-dev-environment` vazio, e `./scripts/verify-local-env.sh` passa
