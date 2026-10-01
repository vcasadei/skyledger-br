# Tasks

## 1. Parâmetros da consulta

- [x] 1.1 Criar em `br.com.skyledger.flight` o enum `Direction`, o enum `SortField` com as expressões SQL da lista branca (D2), o record `FlightQuery` e `InvalidQueryParameterException(parametro, mensagem)`, com Javadoc. Verificar com `./mvnw -q -DskipTests compile`
- [x] 1.2 Implementar `FlightQueryParameters.parse(...)` (D1): `airport` ICAO obrigatório (mensagem específica para 3 caracteres), `direction` e `status` por enum, `from`/`to` com `ResolverStyle.STRICT` e `from` ≤ `to`, `page` ≥ 0, `size` de 1 a 100 (padrão 20), e `sort` como `campo[,asc|desc]` (padrão `departure,asc`)
- [x] 1.3 Testes unitários de `FlightQueryParameters`: padrões, "Código IATA recusado", "Aeroporto ausente", "Período invertido", "Data mal formada" (incluindo `2026-02-30`), "Valor de enum inválido", "Tamanho acima do máximo", `page` negativa, "Campo de ordenação desconhecido" e `sort` sem direção resultando em `asc`. Todos verificando que a mensagem cita o parâmetro

## 2. Consulta no banco e endpoint REST

- [x] 2.1 `FlightQueryRepository` com `JdbcClient` (D2): verificação de existência do aeroporto, `count` e página com `WHERE` por fragmentos fixos e parâmetros nomeados, `ORDER BY` da lista branca com `NULLS LAST` e desempate por `id`, e atraso calculado no SQL
- [x] 2.2 Records `FlightPage`/`FlightView` (D4), `AirportNotFoundException`, `FlightQueryController` (`GET /flights`) e o advice com 400, 404 e 503 (D5)
- [x] 2.3 Massa de teste por SQL no `skyledger_test`, depois do `truncateAll` (com a proteção de schema): aeroportos `SBGR`, `SBRJ` e `SBSP`, um voo com atraso positivo, um adiantado, um cancelado, um realizado sem previsto, um com origem igual ao destino e 250 voos gerados com `generate_series` para paginação
- [x] 2.4 Testes de integração de "Voos que saem de um aeroporto", "Sentido padrão" (incluindo a soma com o voo de origem igual ao destino), "Período inclusivo", "Realizado sem horário previsto entra pelo horário real" e "Filtro de situação"
- [x] 2.5 Testes de integração de "Primeira página padrão", "Páginas sem repetição" (percorrendo todas as páginas com `size=100`), "Ordenação por atraso decrescente" (nulos por último) e "Página além da última"
- [x] 2.6 Testes de integração de "Voo realizado com atraso", "Partida adiantada" e "Sem atraso calculável": formato `AAAA-MM-DDTHH:MM:SS` sem offset no JSON e `departureDelayMinutes` 8, -8 e `null`. Se o Jackson não serializar assim, aplicar o ajuste do D4 e registrá-lo
- [x] 2.7 Testes de integração dos erros: 400 para "Código IATA recusado", "Aeroporto ausente" e "Tamanho acima do máximo"; 404 para "Aeroporto inexistente"; 405 para "Método não suportado"; e 503 com `@MockitoSpyBean` no repositório. Todos com `application/problem+json` e o `detail` esperado

## 3. Documentação OpenAPI e da consulta

- [x] 3.1 Anotar `GET /flights` (D6): `@Operation` com o aviso de fuso e de ICAO, `@Parameter` com obrigatoriedade, padrões, valores permitidos e limites, e `@ApiResponse` 200 (`FlightPage`), 400, 404 e 503 (`ProblemDetail`)
- [x] 3.2 Teste do cenário "Operação documentada" em `/v3/api-docs`: os 8 parâmetros, `airport` obrigatório, `size` com máximo 100, `direction`/`status`/`sort` com valores permitidos, as 4 respostas e os schemas `FlightPage` e `FlightView` na especificação gerada
- [x] 3.3 Criar `docs/consulta-voos.md` (PT-BR): parâmetros em tabela, exemplos de requisição e resposta, regras de período, ordenação e atraso, tabela de erros, aviso de fuso e de ICAO (com `SBGR` = Guarulhos) e o caminho `/flights` sem `/api` (D10). Mais o link no `README.md`

## 4. Build do front-end integrado ao Maven

- [x] 4.1 Criar `frontend/` com `package.json` (dependências aprovadas, scripts `build` = `tsc -b && vite build` e `test` = `vitest run`), `package-lock.json`, `tsconfig.json`, `vite.config.ts` (`outDir` em `../target/classes/static`, `emptyOutDir`, proxy de `/flights` para `:8080` e ambiente de teste `jsdom`) e `index.html`. Verificar com `npm ci && npm run build` dentro de `frontend/`. Se o TypeScript 7 não funcionar, aplicar o plano B do D7 e registrá-lo
- [x] 4.2 Adicionar `frontend/node_modules/` ao `.gitignore` (o Node ficou em `~/.cache/skyledger/node`, fora do projeto; ver D8) e verificar com `git check-ignore frontend/node_modules`
- [x] 4.3 Configurar o `frontend-maven-plugin` 2.0.2 no `pom.xml` com as 4 execuções do D8 e Node `v24.21.0`. Verificar que `./mvnw -q -DskipTests package` gera `target/classes/static/index.html` dentro do JAR, e que `-Dskip.npm -Dskip.installnodenpm` pula o front
- [x] 4.4 Teste de integração do cenário "Abrir a tela": `GET /` responde 200 com `text/html` e o `<div id="root">`
- [x] 4.5 Documentar em `docs/aplicacao.md` o build do front (download do Node, cache, *skips*) e o desenvolvimento com `npm run dev` e o proxy

## 5. Tela de busca

- [x] 5.1 `types.ts`, `format.ts` (horário como texto, sem `Date`, e `—` para nulos) e `api.ts` (`buildQuery`, `fetchFlights`, `ApiError` com o `detail`, e falha de rede), com testes unitários de `buildQuery` (só os filtros preenchidos), de `format` e da conversão de erro
- [x] 5.2 `SearchForm.tsx` com os campos e padrões do spec, maiúsculas no aeroporto e a dica ICAO. Testes de "Busca com filtros" (parâmetros enviados e `page=0`) e "Busca sem aeroporto" (sem chamar a API)
- [x] 5.3 `FlightTable.tsx` com as 10 colunas, o aviso de fuso e "Nenhum voo encontrado". Testes de "Voo cancelado na tabela" e "Busca sem resultados"
- [x] 5.4 `Pagination.tsx` e a integração no `App.tsx`. Testes de "Navegar para a próxima página" (`page=1` com os mesmos filtros e ordenação, "Página 2 de 3" e "45 voos") e "Limites da paginação"
- [x] 5.5 Cabeçalhos ordenáveis com indicador de coluna e direção. Teste de "Ordenar por atraso" (`sort=delay,asc` e depois `sort=delay,desc`, ambos com `page=0`)
- [x] 5.6 Exibição de erros da API e de rede, limpando o resultado anterior. Testes de "Aeroporto inexistente", "Código IATA" e falha de rede
- [x] 5.7 Acrescentar a `docs/consulta-voos.md` a seção da tela: como abrir, filtros, ordenação, paginação e o aviso de fuso

## 6. Aceite com os dados reais

- [x] 6.1 Com o banco de dev já ingerido (252.725 voos) e a aplicação no ar: `GET /flights?airport=SBGR` retorna `totalElements` 75062, e `DEPARTURE` + `ARRIVAL` − (voos com origem igual ao destino em `SBGR`) = 75062, conferido por SQL. Cada requisição, inclusive `page` próxima da última com `size=100`, responde em menos de 500 ms
- [x] 6.2 Conferir por SQL que um voo de `SBGR` retornado pela API tem o mesmo atraso calculado, e que `sort=delay,desc` começa pelo maior atraso de `SBGR`
- [ ] 6.3 Abrir `http://localhost:8080/` no navegador, buscar `SBGR` e exercitar os filtros, a paginação, a ordenação por atraso e a mensagem de erro com `GRU`, e seguir `docs/consulta-voos.md` do zero, exatamente como escrito (verificação no navegador feita pelo desenvolvedor)

## 7. Quality Gates

- [x] 7.1 Build completo, com back-end (compilação sem warnings e testes) e front-end (`tsc` e Vitest pelo plugin), com Docker em execução: `./mvnw verify`
- [x] 7.2 Registrar que não há ferramenta de cobertura nem linter configurados (seriam dependências novas não aprovadas): os gates estáticos são o `-Xlint:all -Werror` do Java e o `tsc -b` do TypeScript, ambos na 7.1
- [x] 7.3 Nenhuma vulnerabilidade alta ou crítica nas dependências de produção do front: `npm audit --omit=dev --audit-level=high` em `frontend/`
- [x] 7.4 Somente leitura confirmado: `git diff --stat feat/add-flight-data-ingestion/3-archive -- src/main/resources/db src/main/java/br/com/skyledger/ingestion` vazio. E nenhum segredo nem `.env` rastreado: `git ls-files | grep -x '.env'` vazio
