# flight-query-api Specification

## Purpose

Permite consultar, por uma API REST pública e somente leitura, os voos
ingeridos do VRA de um aeroporto, com filtros de sentido, período e situação,
paginação, ordenação e o atraso de partida calculado.

## Requirements

### Requirement: Consulta de voos por aeroporto e sentido
A aplicação SHALL expor `GET /flights` com o parâmetro obrigatório `airport`,
um código ICAO de exatamente 4 caracteres `[A-Z0-9]` (ex.: `SBGR`), e o
parâmetro opcional `direction`, que define o conjunto de voos:

| `direction` | Voos retornados |
|-------------|-----------------|
| `DEPARTURE` | Origem igual a `airport` |
| `ARRIVAL` | Destino igual a `airport` |
| `BOTH` (padrão) | Origem **ou** destino igual a `airport`. Um voo com origem igual ao destino aparece uma única vez |

O endpoint MUST ser somente leitura: nenhuma tabela é alterada por ele.

#### Scenario: Voos que saem de um aeroporto
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&direction=DEPARTURE`
- **THEN** recebo HTTP 200 e todos os voos de `content` têm `origin` igual a `SBGR`

#### Scenario: Sentido padrão
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR` sem `direction`
- **THEN** `content` traz voos com `origin` ou `destination` igual a `SBGR`
- **AND** `totalElements` é igual à soma dos totais de `DEPARTURE` e `ARRIVAL`, menos os voos com origem igual ao destino

#### Scenario: Código IATA recusado
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=GRU`
- **THEN** recebo HTTP 400 com `application/problem+json` cujo `detail` cita `airport` e informa que o esperado é um código ICAO de 4 caracteres

### Requirement: Filtros de período e situação
A consulta SHALL aceitar `from` e `to` opcionais, no formato `AAAA-MM-DD`,
ambos inclusivos. Eles são aplicados à data da **partida de referência**: a
partida prevista, ou a partida real quando a prevista não existir. Só `from`
traz os voos a partir dessa data, e só `to`, os voos até ela. A consulta SHALL
aceitar `status` opcional, `REALIZADO` ou `CANCELADO`. Os filtros se combinam
com E. `from` posterior a `to` MUST resultar em 400.

#### Scenario: Período inclusivo
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&from=2026-05-11&to=2026-05-11`
- **THEN** todos os voos retornados têm partida de referência em 11/05/2026, entre 00:00 e 23:59 inclusive

#### Scenario: Realizado sem horário previsto entra pelo horário real
- **AS A** consumidor da API
- **WHEN** consulto um período que contém a partida real de um voo `REALIZADO` sem partida prevista
- **THEN** esse voo é retornado

#### Scenario: Filtro de situação
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&status=CANCELADO`
- **THEN** todos os voos retornados têm `status` `CANCELADO`

#### Scenario: Período invertido
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&from=2026-06-01&to=2026-05-01`
- **THEN** recebo HTTP 400 com `detail` citando `from` e `to`

### Requirement: Paginação e ordenação
A consulta SHALL ser sempre paginada, com `page` (a partir de 0, padrão 0) e
`size` (de 1 a 100, padrão 20). A ordenação vem de `sort` no formato
`campo,direção`, com direção `asc` ou `desc`; sem direção, vale `asc`. O
padrão é `departure,asc`. Campos permitidos:

| Campo | Ordena por |
|-------|------------|
| `departure` | Partida de referência (prevista, ou real quando não houver prevista) |
| `arrival` | Chegada de referência (prevista, ou real quando não houver prevista) |
| `airline` | Código da companhia |
| `flightNumber` | Número do voo, como texto |
| `delay` | Atraso de partida em minutos; voos sem atraso calculado ficam sempre por último |

Empates MUST ser desfeitos de forma estável, para que as páginas não repitam
nem pulem voos entre requisições. A resposta SHALL ser um JSON com `content`
(os voos da página), `page`, `size`, `totalElements`, `totalPages` e `sort` (a
ordenação efetivamente aplicada). Uma página além da última MUST retornar 200
com `content` vazio.

#### Scenario: Primeira página padrão
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR`
- **THEN** recebo no máximo 20 voos, `page` `0`, `size` `20` e `sort` `departure,asc`
- **AND** os voos estão em ordem crescente de partida de referência

#### Scenario: Páginas sem repetição
- **AS A** consumidor da API
- **WHEN** percorro todas as páginas de uma consulta com `size=100` e `sort=airline,asc`
- **THEN** a união das páginas tem exatamente `totalElements` voos, sem repetidos

#### Scenario: Ordenação por atraso decrescente
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&sort=delay,desc`
- **THEN** os voos vêm do maior atraso para o menor, e os que têm `departureDelayMinutes` nulo aparecem depois de todos os outros

#### Scenario: Página além da última
- **AS A** consumidor da API
- **WHEN** envio uma `page` maior ou igual a `totalPages`
- **THEN** recebo HTTP 200 com `content` vazio e o mesmo `totalElements`

#### Scenario: Tamanho acima do máximo
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&size=101`
- **THEN** recebo HTTP 400 com `detail` citando `size` e o máximo `100`

#### Scenario: Campo de ordenação desconhecido
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&sort=passengers,asc`
- **THEN** recebo HTTP 400 com `detail` listando os campos permitidos

### Requirement: Representação do voo
Cada item de `content` SHALL trazer: `airline`, `flightNumber`, `origin`,
`destination`, `scheduledDeparture`, `actualDeparture`, `scheduledArrival`,
`actualArrival`, `status` e `departureDelayMinutes`. Os horários MUST sair no
formato ISO-8601 local (`AAAA-MM-DDTHH:MM:SS`), sem offset nem conversão de
fuso, exatamente como foram gravados pela ingestão. Horários ausentes saem
como `null`. O `departureDelayMinutes` é um inteiro igual a partida real menos
partida prevista, em minutos; MUST ser `null` quando qualquer dos dois faltar,
e MUST ser negativo quando o voo partiu adiantado.

#### Scenario: Voo realizado com atraso
- **AS A** consumidor da API
- **WHEN** a consulta retorna um voo com partida prevista `2026-05-11T08:30:00` e real `2026-05-11T08:38:00`
- **THEN** o item traz esses horários como texto, sem offset, e `departureDelayMinutes` `8`

#### Scenario: Partida adiantada
- **AS A** consumidor da API
- **WHEN** a consulta retorna um voo com partida prevista `2026-05-23T21:35:00` e real `2026-05-23T21:27:00`
- **THEN** `departureDelayMinutes` é `-8`

#### Scenario: Sem atraso calculável
- **AS A** consumidor da API
- **WHEN** a consulta retorna um voo `CANCELADO`, ou um `REALIZADO` sem partida prevista
- **THEN** `departureDelayMinutes` é `null`, e os horários ausentes são `null`

### Requirement: Erros da consulta
Os erros SHALL ser respondidos com `application/problem+json`, com `status`,
`title` e um `detail` que cita o parâmetro envolvido:

| HTTP | Quando |
|------|--------|
| 400 | `airport` ausente ou fora do formato ICAO; `direction`, `status` ou `sort` com valor não permitido; `from` ou `to` fora de `AAAA-MM-DD` ou data inexistente; `from` posterior a `to`; `page` negativa; `size` fora de 1 a 100 |
| 404 | `airport` bem formado que não existe na tabela de aeroportos |
| 405 | Método diferente de `GET` |
| 503 | PostgreSQL indisponível |

#### Scenario: Aeroporto ausente
- **AS A** consumidor da API
- **WHEN** envio `GET /flights` sem `airport`
- **THEN** recebo HTTP 400 com `detail` citando `airport`

#### Scenario: Aeroporto inexistente
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=ZZZZ`, código bem formado que não existe no banco
- **THEN** recebo HTTP 404 com `detail` citando `ZZZZ`

#### Scenario: Data mal formada
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&from=11/05/2026`
- **THEN** recebo HTTP 400 com `detail` citando `from` e o formato `AAAA-MM-DD`

#### Scenario: Valor de enum inválido
- **AS A** consumidor da API
- **WHEN** envio `GET /flights?airport=SBGR&direction=NORTH`
- **THEN** recebo HTTP 400 com `detail` citando `direction` e os valores permitidos

#### Scenario: Método não suportado
- **AS A** consumidor da API
- **WHEN** envio `POST /flights?airport=SBGR`
- **THEN** recebo HTTP 405

### Requirement: Documentação OpenAPI da consulta
A especificação em `/v3/api-docs` SHALL documentar `GET /flights` com todos os
parâmetros (tipo, obrigatoriedade, padrão, valores permitidos e limites), o
schema da resposta paginada e do voo, e as respostas 200, 400, 404 e 503. A
descrição MUST informar que os horários estão sem fuso, como publicados pela
ANAC, e que `airport` é ICAO, não IATA.

#### Scenario: Operação documentada
- **AS A** consumidor da API
- **WHEN** envio `GET /v3/api-docs`
- **THEN** `paths./flights.get` lista os parâmetros `airport`, `direction`, `from`, `to`, `status`, `page`, `size` e `sort`, com `airport` obrigatório
- **AND** as respostas `200`, `400`, `404` e `503` estão presentes, a de 200 referenciando o schema da página de voos
