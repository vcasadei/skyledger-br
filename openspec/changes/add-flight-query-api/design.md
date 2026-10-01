# Design

## Context

- A aplicação já tem as tabelas `flight` e `airport` populadas pela ingestão
  (`flight-data-ingestion`), acesso a dados via `JdbcClient`/`JdbcTemplate`
  (sem JPA), `ProblemDetail` com `spring.mvc.problemdetails.enabled`, springdoc
  3.1 e testes de integração no schema `skyledger_test`, com a proteção de
  `TRUNCATE` (`IngestionEndpointTest.truncateAll`). Não há front-end.
- Colunas relevantes de `flight`: `airline_icao`, `flight_number`,
  `origin_icao`, `destination_icao`, os 4 horários (`timestamp` sem fuso),
  `status` e `id`. Não há índice em origem, destino nem horários, só a
  `UNIQUE` da chave natural, que começa por `airline_icao`.
- Medido no dev com os dados reais (252.725 voos; `SBGR` com 75.062 em
  `BOTH`): a página com filtro de período e `ORDER BY` pela partida de
  referência leva 87 a 128 ms por *parallel seq scan*, inclusive no offset de
  70.000, e o `count` leva 25 a 36 ms.
- Ambiente: Node 24.21 / npm 11.19 instalados localmente. Versões atuais
  aprovadas: React 19.3, Vite 8.3, `@vitejs/plugin-react` 6.1, TypeScript 7.0,
  Vitest 5.0, Testing Library 16.3 e `frontend-maven-plugin` 2.0.2.
- Requisitos em `specs/flight-query-api/spec.md` e
  `specs/flight-search-ui/spec.md`, motivação em `proposal.md`.

## Goals / Non-Goals

**Goals:**
- Contrato da consulta estável e testado: filtros, paginação sem repetição,
  ordenação por lista branca e erros 400/404 que citam o parâmetro.
- Um só artefato: `./mvnw verify` compila e testa o back-end e o front-end, e
  empacota a tela no JAR.

**Non-Goals:**
- Códigos IATA (sem fonte disponível; fica para uma change futura).
- Índices ou qualquer alteração de esquema, e cache em Redis da consulta.
- Conversão de fuso horário.
- Autenticação, roteamento no cliente (há uma tela só), estado na URL,
  internacionalização, biblioteca de componentes e ESLint (não aprovados).
- Exportação (CSV) e gráficos.

## Decisions

### D1. Parâmetros lidos como texto e validados por uma classe própria
O controller recebe todos os parâmetros como `String` opcional
(`@RequestParam(required = false)`) e chama `FlightQueryParameters.parse(...)`.
Essa classe devolve um record validado (`FlightQuery`) ou lança
`InvalidQueryParameterException(parametro, mensagem)`. Isso garante as
mensagens do spec (citar o parâmetro, os valores permitidos, o máximo de
`size`, o formato ICAO e o formato `AAAA-MM-DD`), que os conversores do
Spring (`MethodArgumentTypeMismatchException`) não dão de forma uniforme, e
permite testar a validação sem HTTP.
- `airport`: `^[A-Z0-9]{4}$`, sem maiúsculas automáticas na API (quem converte
  é a tela). Com 3 caracteres, a mensagem explica que o esperado é ICAO.
- Datas: `LocalDate.parse` com `ResolverStyle.STRICT`, para recusar
  `2026-02-30`.
- `sort`: `campo[,asc|desc]`, com o campo validado contra o enum `SortField`.

*Alternativa:* `@RequestParam` tipado com enums e Bean Validation
(`@Min`/`@Max`). Faltaria `spring-boot-starter-validation`, que é uma
dependência nova, e as mensagens viriam do framework.

### D2. SQL dinâmico seguro com `JdbcClient`
`FlightQueryRepository` monta a consulta a partir de fragmentos fixos, com
todos os valores por parâmetro nomeado:
```sql
-- base, sem nenhum texto do usuário concatenado:
FROM flight f
WHERE <sentido>                     -- f.origin_icao = :airport | f.destination_icao = :airport
                                    -- | (f.origin_icao = :airport OR f.destination_icao = :airport)
  [AND COALESCE(f.scheduled_departure, f.actual_departure) >= :fromStart]   -- from 00:00
  [AND COALESCE(f.scheduled_departure, f.actual_departure) <  :toEnd]       -- to + 1 dia, 00:00
  [AND f.status = :status]
ORDER BY <expressão do SortField> <ASC|DESC> NULLS LAST, f.id ASC
LIMIT :size OFFSET :offset
```
- Expressões da lista branca: `departure` = `COALESCE(scheduled_departure,
  actual_departure)`, `arrival` = `COALESCE(scheduled_arrival,
  actual_arrival)`, `airline` = `airline_icao`, `flightNumber` =
  `flight_number`, e `delay` = a expressão do atraso abaixo. A direção vem do
  enum, nunca do texto recebido.
- Atraso no SQL: `(EXTRACT(EPOCH FROM actual_departure -
  scheduled_departure) / 60)::integer`. Os segundos são sempre `:00` (medido
  na ingestão), então a divisão é exata. O resultado é `NULL` quando um dos
  lados é nulo, e negativo quando o voo parte adiantado.
- `NULLS LAST` vale em todas as direções (o spec exige para `delay`, e é
  inócuo nos demais). O `f.id` como desempate dá páginas estáveis.
- Duas consultas por requisição: `count(*)` com o mesmo `WHERE`, e a página.
  Antes delas, `SELECT EXISTS (SELECT 1 FROM airport WHERE icao = :airport)`
  decide o 404.
- Em `BOTH`, o `OR` numa única linha por voo garante que um voo com origem
  igual ao destino apareça uma vez só.

*Alternativa:* paginação por cursor (*keyset*), que é mais eficiente em
offsets grandes. Mas o spec pede `page` e `totalPages`, e a medição mostra o
offset de 70.000 abaixo de 130 ms.

### D3. Sem índice nesta mudança
A consulta é só leitura e o pedido proíbe alterar tabelas. Os tempos medidos
(acima) atendem a meta de menos de 500 ms por requisição no ambiente local,
que é verificada na task de aceite. Índices em
`(origin_icao, COALESCE(scheduled_departure, actual_departure))` e no
equivalente de destino ficam como evolução, numa mudança com migração, se o
volume crescer.

### D4. Modelo de resposta
Records em `br.com.skyledger.flight`: `FlightPage(List<FlightView> content,
int page, int size, long totalElements, int totalPages, String sort)` e
`FlightView(String airline, String flightNumber, String origin, String
destination, LocalDateTime scheduledDeparture, LocalDateTime actualDeparture,
LocalDateTime scheduledArrival, LocalDateTime actualArrival, String status,
Integer departureDelayMinutes)`. O `LocalDateTime` é serializado pelo Jackson 3
como `2026-05-11T08:30:00`, sem offset. Isso é verificado no teste do contrato
e, se o padrão divergir, ajustado com
`spring.jackson.datatype.datetime.write-dates-as-timestamps=false`. O
`totalPages` é `ceil(totalElements / size)`.

### D5. Erros
Um `@RestControllerAdvice(assignableTypes = FlightQueryController.class)`
próprio, no mesmo padrão do `IngestionExceptionHandler`:
- `InvalidQueryParameterException` → 400, com título "Parâmetro inválido" e o
  `detail` da exceção;
- `AirportNotFoundException` → 404, com o `detail` citando o código;
- `CannotGetJdbcConnectionException`/`DataAccessResourceFailureException` →
  503, sem detalhes da conexão.

O 405 vem do Spring MVC, já com problem details.
*Alternativa:* um advice global compartilhado com a ingestão. Foi descartado
porque as mensagens de 503 são diferentes por contexto e o
`assignableTypes` evita interferir no Actuator.

### D6. OpenAPI
`@Operation` (resumo e descrição com o aviso de fuso e de ICAO) e um
`@Parameter` por parâmetro, com `required`, `example`, `schema(allowableValues,
defaultValue, minimum, maximum, pattern)`. Mais `@ApiResponse` para 200
(`FlightPage`), 400, 404 e 503 (`ProblemDetail`). Como os parâmetros são
`String` (D1), os enums e limites são declarados explicitamente nas anotações.
O teste verifica isso na especificação gerada.

### D7. Front-end em `frontend/` (React + Vite + TypeScript)
```
frontend/
  package.json, package-lock.json, tsconfig.json, vite.config.ts, index.html
  src/
    api.ts             -- buildQuery(filtros, página, ordenação) e fetchFlights(): Promise<FlightPage>; lança ApiError(detail)
    types.ts           -- FlightPage, FlightView, Filters, Sort (espelham o contrato)
    App.tsx            -- estado: filtros aplicados, página, ordenação, resultado, erro, carregando
    SearchForm.tsx     -- campos e validação de aeroporto obrigatório (converte para maiúsculas)
    FlightTable.tsx    -- colunas, "—" para nulos, cabeçalhos ordenáveis com indicador
    Pagination.tsx     -- "N voos", "Página X de Y", Anterior/Próxima com limites
    format.ts          -- formata LocalDateTime sem fuso (só troca o "T" por espaço, sem new Date())
    *.test.tsx         -- Vitest + Testing Library + jsdom, com fetch mockado
```
- Estado com `useState` no `App`. Não há biblioteca de estado nem de tabela.
- Horários: exibidos como texto, **sem** `new Date(...)`, porque o `Date`
  interpretaria o valor no fuso do navegador e faria uma conversão que o spec
  proíbe.
- Erro da API: `ApiError` com o `detail` do problem; ao ocorrer, o resultado
  anterior é limpo. Falha de rede (`fetch` rejeitado) mostra "A API não
  respondeu".
- Dev: `npm run dev` (Vite em `:5173`) com `server.proxy` de `/flights` para
  `http://localhost:8080`. Assim o desenvolvimento roda sem CORS.
- TypeScript 7.0 é o compilador nativo novo. Se o `tsc -b` dele não for
  compatível com o template do Vite 8, a versão cai para a última 6.x e isso
  é registrado aqui (Riscos).

### D8. Build integrado ao Maven
`frontend-maven-plugin` 2.0.2 no `pom.xml`, com
`workingDirectory=frontend`, Node `v24.21.0` instalado em `frontend/node/`
(gitignorado) e estas execuções:

| Execução | Fase | Comando |
|----------|------|---------|
| `install-node-and-npm` | `initialize` | baixa o Node/npm, uma vez |
| `npm ci` | `initialize` | instala pelo `package-lock.json` |
| `npm run build` | `generate-resources` | `tsc -b && vite build`, com `outDir` em `../target/classes/static` |
| `npm test` | `test` | `vitest run` |

O build vai direto para `target/classes/static`, então o Spring Boot serve
`index.html` em `/` (welcome page de recursos estáticos) e os testes Java da
fase `test` já encontram a tela. Para desenvolver só o back-end,
`-Dskip.npm -Dskip.installnodenpm` pula o front, e isso fica documentado.
*Alternativa:* copiar `frontend/dist` com o `maven-resources-plugin` no
`prepare-package`, o que chegaria tarde demais para os testes da fase `test`.

### D9. Testes
| Cobertura | Tipo |
|-----------|------|
| Validação de parâmetros (D1) | Unitário de `FlightQueryParameters` |
| Contrato HTTP, filtros, paginação, ordenação, atraso, 400/404/405 | Integração (`RANDOM_PORT`), com voos inseridos por SQL no `skyledger_test` depois do `truncateAll`. A massa para paginação é gerada com `generate_series` (por exemplo, 250 voos) |
| 503 | Integração com `@MockitoSpyBean` no repositório lançando `DataAccessResourceFailureException` |
| OpenAPI | Teste na `/v3/api-docs` (parâmetros, respostas e schema) |
| Tela servida em `/` | Integração: `GET /` retorna HTML com o `<div id="root">` |
| Comportamento da tela | Vitest/Testing Library, com um cenário do spec por teste e fetch mockado |
| Aceite com os dados reais | Manual: totais e tempos do `SBGR` no dev, e a tela no navegador |

### D10. Pacote, caminho e documentação
- Pacote `br.com.skyledger.flight`: controller, parâmetros, repositório,
  records e advice.
- O caminho `/flights`, sem o prefixo `/api`, vem do pedido. A inconsistência
  com `/api/ingestions` fica registrada, e uma padronização futura seria
  BREAKING.
- Documentação: `docs/consulta-voos.md` (contrato com exemplos, aviso de fuso
  e ICAO, uso da tela). `docs/aplicacao.md` ganha o build do front, o download
  do Node, os *skips* e o servidor de dev. Mais o link no `README.md` e
  `frontend/node/` e `frontend/node_modules/` no `.gitignore`.

## Security & Observability

- **Injeção:** nenhum valor do usuário é concatenado no SQL. Filtros vão por
  parâmetro nomeado, e campo e direção do `sort` passam por enums.
- **XSS:** o React escapa o conteúdo renderizado e a tela não usa
  `dangerouslySetInnerHTML`. Os dados vêm do banco, não de outro usuário.
- **CORS:** não é necessário (mesma origem). Em dev, o proxy do Vite cuida
  disso.
- **Exposição:** dados públicos da ANAC, sem dados pessoais. O endpoint e a
  tela ficam abertos, como o resto da aplicação (sem autenticação, Non-Goal).
- **Abuso:** `size` limitado a 100. Não há rate limit (aplicação local).
- **Logs:** nada novo. Os erros 400 e 404 não são logados, e o 503 loga um
  `WARN` sem credenciais.

## Risks / Trade-offs

- [Consulta sem índice: o tempo cresce linearmente com o volume de voos] →
  medido em menos de 130 ms com 252 mil voos. A meta de 500 ms é verificada
  no aceite, e o índice fica como evolução (D3).
- [Build mais lento e dependente de rede (Node e npm) na primeira vez] → o
  Node é cacheado em `frontend/node/` e os pacotes em `~/.npm`. Há os *skips*
  para quem só mexe no back-end.
- [TypeScript 7 é recente] → plano B com 6.x (D7).
- [Front e API podem divergir no contrato] → `types.ts` espelha os records,
  o teste Java verifica o JSON e os testes do front usam respostas no mesmo
  formato.
- [Prefixo de caminho inconsistente (`/flights` e `/api/ingestions`)] →
  registrado no D10.
