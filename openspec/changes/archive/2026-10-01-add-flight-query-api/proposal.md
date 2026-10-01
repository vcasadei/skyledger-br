# Proposal

## Why

Os 252.725 voos do VRA já estão no banco (capability `flight-data-ingestion`),
mas só dá para vê-los com SQL. Analistas e as próximas etapas (pontualidade,
risco) precisam consultar os voos de um aeroporto, filtrar por período e
situação e ver o atraso de partida, tanto por uma API pública quanto por uma
tela simples.

## What Changes

- **Endpoint `GET /flights`** (somente leitura), com estes parâmetros:
  - `airport` (obrigatório): código **ICAO** de 4 caracteres, como está no
    banco (ex.: `SBGR` para Guarulhos). Códigos IATA (`GRU`) **não** são
    aceitos nesta mudança, porque nenhuma fonte disponível traz IATA. Um
    código de 3 letras recebe 400 com uma mensagem que explica que o esperado
    é ICAO.
  - `direction`: `DEPARTURE`, `ARRIVAL` ou `BOTH` (padrão).
  - `from` e `to` (opcionais, `AAAA-MM-DD`, inclusivos): aplicados à partida
    prevista, ou à partida real quando a prevista não existir.
  - `status` (opcional): `REALIZADO` ou `CANCELADO`.
  - Paginação com `page` (padrão 0) e `size` (padrão 20, máximo 100).
  - Ordenação com `sort` (`campo,direção`), por partida, chegada, companhia,
    número do voo ou atraso. O padrão é a partida, crescente.
- **Cada voo traz:** companhia, número do voo, origem, destino, partida e
  chegada previstas e reais, situação, e o atraso de partida em minutos.
  O atraso só existe com horário previsto e real; caso contrário é nulo. Pode
  ser negativo, quando o voo parte adiantado. Os horários saem como estão no
  banco, **sem fuso**, e isso é documentado na API e na tela.
- **Erros** em `application/problem+json`: 400 para parâmetro inválido e 404
  para aeroporto ICAO bem formado que não existe na tabela `airport`.
- **Tela de busca** (SPA React + TypeScript, servida pela própria aplicação
  em `/`): filtros (aeroporto, sentido, período e situação), tabela de
  resultados com paginação e ordenação pelas colunas, e a mensagem de erro da
  API quando a busca é inválida.
- Documentação OpenAPI completa do endpoint e testes contra o schema isolado
  de testes.

Nenhuma mudança é **BREAKING**: o endpoint e a tela são novos, e nada que já
existe muda de comportamento.

## Capabilities

### New Capabilities
- `flight-query-api`: consulta paginada, filtrável e ordenável dos voos
  ingeridos por aeroporto (`GET /flights`), com atraso de partida calculado.
- `flight-search-ui`: tela de busca de voos servida pela aplicação, que
  consome o `GET /flights`.

### Modified Capabilities
<!-- Nenhuma. flight-data-ingestion só é lido (tabelas flight e airport);
     application-runtime não muda: o build continua exigindo só o JDK 21,
     porque o plugin baixa o Node. -->

## Impact

- **Endpoints REST públicos** (com documentação OpenAPI):
  - `GET /flights`: consulta de voos por aeroporto.
  - `GET /`: a tela de busca. São arquivos estáticos, não uma operação REST,
    e por isso ficam fora da OpenAPI.
- **Esquema de banco:** nenhuma alteração. Não há migração nem índice novo, e
  as tabelas `flight` e `airport` são só lidas. Medi sem índice, com os dados
  reais: de 87 a 128 ms por página no `SBGR` (75.062 voos), inclusive com
  deslocamento de 70.000.
- **Ingestão:** não é tocada.
- **Dependências novas, aprovadas:**
  - Front-end: `react` e `react-dom` 19.3, `vite` 8.3 com
    `@vitejs/plugin-react` 6.1, `typescript` 7.0, e `vitest` 5.0 com
    `@testing-library/react` 16.3 e `jsdom` em dev.
  - Build: `frontend-maven-plugin` 2.0.2, que baixa o Node e roda `npm` dentro
    do `./mvnw verify`. O build passa a precisar de rede para o Node e para o
    registro npm na primeira execução.
- **Redis e SeaweedFS:** não são tocados. Não há cache nesta mudança, e o
  comportamento em caso de indisponibilidade não muda.
- **Dados pessoais (LGPD), valores monetários, segredos, filas e anac-data:**
  não se aplica. A consulta lê o banco, não os arquivos.
