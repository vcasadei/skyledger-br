# Design

## Context

O repositório tem o ambiente local (`compose.yaml`, spec
`local-dev-environment`): PostgreSQL 16 em `127.0.0.1:5432` (banco e usuário
`skyledger`, senha em `POSTGRES_PASSWORD`, padrão trivial de dev), Redis 8 em
`127.0.0.1:6379` sem senha, e `.env.example` com as variáveis. Ainda não há
código Java, build nem convenções de aplicação, então esta mudança estabelece
a primeira. A motivação está em `proposal.md` e os requisitos em
`specs/application-runtime/spec.md`. A máquina atual não tem JDK: quem
desenvolve instala um JDK 21 antes do apply (decisão registrada na proposta).

## Goals / Non-Goals

**Goals:**
- `./mvnw verify` é o gate único: compila sem warnings, roda testes unitários
  e de integração e empacota.
- A aplicação usa o ambiente local como ele é: nenhum arquivo de
  infraestrutura muda.
- O comportamento em caso de indisponibilidade do Redis (fail-open) fica
  definido desde a base, antes de o cache existir.

**Non-Goals:**
- JPA/ORM, migrações (Flyway/Liquibase) e esquema de banco. Ficam para a
  primeira mudança que criar tabelas (ingestão).
- Uso do SeaweedFS pela aplicação.
- Spring Security, autenticação e perfis de ambiente (dev/prod). O
  health-check e a documentação ficam abertos, como hoje é o próprio ambiente.
- Probes separadas de liveness/readiness e métricas.
- Propriedade do caminho do `anac-data`, que nasce com a ingestão.
- Ferramentas de cobertura e lint (JaCoCo, Checkstyle etc.). Seriam
  dependências novas não aprovadas.

## Decisions

### D1. Projeto Maven único na raiz, com parent do Spring Boot
`pom.xml` na raiz com `spring-boot-starter-parent` 4.1.1, `groupId`
`br.com.skyledger`, `artifactId` `skyledger`, versão `0.1.0-SNAPSHOT` e
`java.version` 21. O Maven Wrapper 3.3.4 aponta para a versão 3.9.x mais
recente do Maven, escolhida no apply. `maven-enforcer-plugin`
(`requireJavaVersion [21,)`), gerenciado pelo parent, garante o cenário
"Build com JDK antigo" com mensagem clara, antes da compilação.
*Alternativas:* multi-módulo (desnecessário com um único deployable) ou
Gradle (a stack fixa Maven).

### D2. Starters mínimos
- `spring-boot-starter-webmvc`: o `spring-boot-starter-web` está depreciado no
  Boot 4.1 em favor deste.
- `spring-boot-starter-actuator`.
- `spring-boot-starter-jdbc`: só o pool Hikari. JPA não entra, porque a
  escolha de acesso a dados é da ingestão.
- `spring-boot-starter-data-redis`, com Lettuce.
- `org.postgresql:postgresql` (runtime).
- `springdoc-openapi-starter-webmvc-ui` 3.1.1, aprovado na proposta. Verifiquei
  que ele é compilado contra o Boot 4.1.0.
- Teste: `spring-boot-starter-test`, os starters de teste por tecnologia do
  Boot 4 que se mostrarem necessários, e `spring-boot-docker-compose` em
  escopo de teste (D6).

### D3. Configuração em `application.yaml`, com placeholders e `.env` importado
```yaml
spring:
  config:
    import: optional:file:.env[.properties]
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:skyledger}
    username: ${POSTGRES_USER:skyledger}
    password: ${POSTGRES_PASSWORD}
    hikari:
      connection-timeout: 3000   # ms
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2s
      connect-timeout: 2s
```
- O mesmo `.env` do Compose é importado como arquivo de propriedades, porque
  linhas `CHAVE=valor` e `#` são sintaxe válida. Assim, uma só fonte serve à
  infraestrutura e à aplicação. O caminho é relativo ao diretório de trabalho,
  e a documentação manda rodar da raiz. Variáveis de ambiente do SO têm
  precedência sobre arquivos de configuração no Spring Boot, o que atende ao
  cenário "Variável de ambiente sobrescreve o `.env`".
- A senha não tem padrão: nenhum valor de senha fica no código.
- `hikari.connection-timeout` é ligado a `long` em milissegundos: `3s` falha
  na inicialização (verificado no apply). Por isso o valor é `3000`.
- Os timeouts curtos existem porque o health-check abre conexão. Com os
  padrões (Hikari 30s, Lettuce 60s), `GET /actuator/health` travaria por
  dezenas de segundos com uma dependência fora.

*Alternativas:* `spring-boot-docker-compose` também em runtime, que subiria o
Compose e injetaria as conexões sozinho. Foi descartado para execução normal
porque esconde a configuração que a proposta pediu explícita e acopla a
aplicação ao Docker. Um perfil `local` com senha padrão foi descartado por
colocar credencial em arquivo versionado.

### D4. Falha rápida explícita quando a senha falta
O binder do Spring Boot pode manter `${POSTGRES_PASSWORD}` não resolvido como
texto literal, em vez de falhar. Nesse caso, a aplicação só erraria na
primeira conexão, com uma mensagem enganosa. Por isso, um bean de validação
(`DatabaseCredentialsCheck`, em `br.com.skyledger.config`) lê o
`JdbcConnectionDetails` efetivo na inicialização. Se a senha for nula, vazia ou
ainda um placeholder, ele lança uma exceção com a mensagem "POSTGRES_PASSWORD
não definida: exporte a variável ou crie o .env a partir de .env.example".
Validar o `JdbcConnectionDetails`, e não a propriedade, faz a checagem
funcionar também quando os testes recebem a conexão pelo Docker Compose (D6).
*Verificado no apply (Boot 4.1.1):* sem `POSTGRES_PASSWORD`, a aplicação
**inicia normalmente**, com o placeholder mantido como texto literal, então o
bean é necessário. Com ele, `java -jar` sem `.env` e sem a variável termina
com código 1 e a mensagem acima.

### D5. Health: só `health` exposto, e Redis como `DEGRADED`
```yaml
management:
  endpoints.web.exposure.include: health
  endpoints.web.discovery.enabled: false
  endpoint.health:
    show-components: always
    show-details: never
    probes.enabled: false
    status.order: DOWN, OUT_OF_SERVICE, UP, DEGRADED, UNKNOWN
  health.redis.enabled: false
```
- O indicador automático do Redis é desligado e substituído por um bean
  `redis` (`RedisDegradableHealthIndicator`, em `br.com.skyledger.health`).
  Ele delega ao indicador de Redis do próprio Spring Boot e troca `DOWN` pelo
  status customizado `DEGRADED`.
- Com `DEGRADED` depois de `UP` na ordem, a agregação resulta em `UP`. Como
  `DEGRADED` não está no mapeamento HTTP de 503 (que cobre só `DOWN` e
  `OUT_OF_SERVICE`), a resposta é 200.
- `discovery.enabled: false`: sem isso, `GET /actuator` responde com a página
  de links, que é outro endpoint do Actuator exposto (visto no apply, na
  especificação gerada). Com a propriedade, ela responde 404.
- `probes.enabled: false`: no Boot 4.1 os grupos `liveness` e `readiness`
  vêm ativos por padrão (verificado no apply) e exporiam
  `/actuator/health/liveness` e `/readiness`, além de listar `groups` na
  resposta. Isso contraria o requisito de expor só `/actuator/health`.
- `show-details: never` atende a "Resposta sem detalhes sensíveis". O
  indicador `db` de fábrica já mapeia banco fora para `DOWN` e 503.

*Alternativas:* um grupo de health sem Redis, que tiraria o `redis` da
resposta raiz e violaria o spec; ou um `StatusAggregator` customizado, que só
enxerga status e não nomes, então não consegue tratar o Redis de forma
diferente. O indicador é uma classe pequena e concreta, sem abstração genérica.
*Verificado no apply:* a classe é
`org.springframework.boot.data.redis.health.DataRedisHealthIndicator`, e o
indicador automático é condicionado a
`@ConditionalOnEnabledHealthIndicator("redis")`, desligado pela propriedade
acima. O construtor de injeção leva `@Autowired`, porque há um segundo
construtor (pacote) usado nos testes unitários.

### D6. Estratégia de testes
| Cenário | Tipo | Como |
|---------|------|------|
| Mapeamento `DOWN` → `DEGRADED` | Unitário | Delegate mockado |
| Todas as dependências `UP`, 404 em `/actuator/env` e `/actuator/beans`, 405 em POST, corpo sem detalhes, OpenAPI | Integração | `@SpringBootTest(webEnvironment = RANDOM_PORT)` com `spring-boot-docker-compose` (`spring.docker.compose.skip.in-tests=false`, `lifecycle-management=start-only`), que usa o `compose.yaml` existente e fornece as conexões sem credencial no teste |
| Redis indisponível | Integração | Mesmo contexto, com `LettuceConnectionFactory` substituída por `@MockitoBean` que lança exceção de conexão. Não pode ser `RedisConnectionFactory`, porque a auto-configuração reativa do Redis exige que o mesmo bean seja também `ReactiveRedisConnectionFactory` (verificado no apply) |
| PostgreSQL indisponível | Integração | Compose desligado (`spring.docker.compose.enabled=false`), URL para porta local sem serviço e senha gerada aleatoriamente no teste |
| Senha ausente | Integração | Contexto iniciado sem `POSTGRES_PASSWORD`, com Compose desligado. A falha precisa citar a variável |
| Credenciais fora dos logs | Integração | `OutputCaptureExtension` na inicialização, com senha aleatória, verificando que ela não aparece |
| Senha via `.env`, env sobre `.env`, PostgreSQL recuperado sem restart, build com JDK 17, Swagger UI no navegador | Manual documentado | Exigem manipular arquivos da raiz, parar serviços ou trocar de JDK |

O `start-only` não derruba o ambiente de dev ao fim dos testes. Os testes só
leem, sem criar dados. Consequência: `./mvnw verify` exige Docker em execução.

### D7. OpenAPI com o health-check documentado
`springdoc.show-actuator: true` inclui `/actuator/health` na especificação. Se
a especificação gerada não trouxer a resposta 503, um `OpenApiCustomizer`
(bean em `br.com.skyledger.config`) a acrescenta à operação. Isso é conferido
no apply e coberto pelo teste de OpenAPI.
*Verificado no apply:* o springdoc 3.1.1 gera só a resposta 200, com corpo sem
schema. Por isso existe o `HealthOpenApiCustomizer`, que acrescenta a 503, o
schema `HealthResponse` (`status` e `components.*.status`) e o resumo da
operação.

### D8. Compilação estrita
`maven-compiler-plugin` com `-Xlint:all,-processing` e `-Werror`: é a análise
estática disponível sem nova dependência. Javadoc em toda classe e método
público, conforme o padrão do projeto.

### D9. Documentação
`docs/aplicacao.md` (PT-BR) cobre pré-requisitos (JDK 21, Docker), build,
execução (`./mvnw spring-boot:run` a partir da raiz), variáveis de ambiente,
health-check e OpenAPI, além das verificações manuais do D6. O `README.md`
ganha um link. Em `.env.example`, só o comentário de `POSTGRES_PASSWORD` muda,
para dizer que a aplicação também lê a variável. Valores e variáveis não mudam.

## Security & Observability

- **Segredos:** nenhum novo. `POSTGRES_PASSWORD` já existe e vem do ambiente
  ou do `.env` local (gitignorado, criado a partir de `.env.example`). Não há
  senha em `application.yaml`, em código ou em testes: os testes usam a
  conexão do Compose ou uma senha aleatória gerada em tempo de execução.
- **Exposição:** apenas `health` no Actuator, sem detalhes. `/v3/api-docs` e o
  Swagger UI são públicos, o que é aceitável enquanto não há autenticação
  (Non-Goal) nem endpoints de negócio.
- **Logs:** Hikari e Lettuce não logam senha nos níveis padrão, e o teste de
  log da D6 garante isso. Nenhuma configuração de log de SQL ou de debug é
  ligada.
- **Dados pessoais e rastreamento:** não se aplica.

## Risks / Trade-offs

- [`./mvnw verify` depende do Docker para os testes de integração] → está
  documentado. É o mesmo pré-requisito do ambiente local.
- [Os testes de integração reutilizam o projeto Compose `skyledger` de dev] →
  eles só leem e não param o ambiente (`start-only`).
- [O caminho `.env` é relativo ao diretório de trabalho: rodar a aplicação de
  outro diretório não encontra o arquivo] → a inicialização falha com a
  mensagem da D4, que aponta a solução. A documentação manda rodar da raiz.
- [Classe do indicador de Redis e comportamento do placeholder não resolvido
  no Boot 4.1 ainda não verificados] → conferidos no apply. D4 e D5 já
  registram a alternativa para cada caso.
- [Health-check e OpenAPI abertos, sem autenticação] → aceitável no esqueleto.
  A segurança entra em mudança própria antes de qualquer exposição fora da
  máquina local.
