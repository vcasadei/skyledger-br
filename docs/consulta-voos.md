# Consulta de voos

`GET /flights` consulta, **somente leitura**, os voos do VRA já ingeridos (ver
[ingestão](ingestao.md)) de um aeroporto, com paginação, ordenação e o atraso
de partida calculado. A [tela de busca](#tela-de-busca) usa este mesmo
endpoint.

> **Aeroporto em ICAO, não IATA.** `airport=SBGR` (Guarulhos), e não `GRU`.
> Nenhuma fonte de dados disponível traz o código IATA. Um código de 3 letras
> recebe 400.
>
> **Horários sem fuso.** Os horários são os publicados pela ANAC, sem
> conversão, no formato ISO-8601 local sem offset (`2026-05-11T08:30:00`).

O caminho é `/flights`, sem o prefixo `/api` usado em `/api/ingestions`.

## Parâmetros

| Parâmetro | Obrigatório | Padrão | Valores |
|-----------|-------------|--------|---------|
| `airport` | sim | | Código ICAO, 4 caracteres `[A-Z0-9]` (ex.: `SBGR`, `SBRJ`, `SBSP`) |
| `direction` | não | `BOTH` | `DEPARTURE` (sai do aeroporto), `ARRIVAL` (chega), `BOTH` (sai ou chega; um voo com origem igual ao destino aparece uma vez) |
| `from` | não | | `AAAA-MM-DD`, inclusivo |
| `to` | não | | `AAAA-MM-DD`, inclusivo. `from` posterior a `to` dá 400 |
| `status` | não | todas | `REALIZADO`, `CANCELADO` |
| `page` | não | `0` | Inteiro ≥ 0. Além da última página: 200 com `content` vazio |
| `size` | não | `20` | 1 a **100** |
| `sort` | não | `departure,asc` | `campo[,asc\|desc]`, com os campos abaixo |

**Partida de referência:** a partida prevista ou, quando ela não existe
(voos realizados sem horário previsto), a partida real. É usada no período
(`from`/`to`) e na ordenação `departure`. A **chegada de referência**, usada
em `arrival`, segue a mesma regra.

| Campo de `sort` | Ordena por |
|-----------------|------------|
| `departure` | Partida de referência |
| `arrival` | Chegada de referência |
| `airline` | Código da companhia |
| `flightNumber` | Número do voo, como texto |
| `delay` | Atraso de partida. Voos sem atraso calculado ficam **sempre por último** |

Empates são desfeitos de forma estável, então as páginas não repetem nem
pulam voos.

## Resposta

Resposta real, com os dados ingeridos do VRA de maio de 2026 (o voo saiu 10 minutos adiantado):

```bash
curl -s 'http://localhost:8080/flights?airport=SBGR&direction=DEPARTURE&from=2026-05-11&to=2026-05-11&sort=airline&size=1'
```

```json
{
  "content": [
    {
      "airline": "AAL",
      "flightNumber": "0962",
      "origin": "SBGR",
      "destination": "KDFW",
      "scheduledDeparture": "2026-05-11T22:00:00",
      "actualDeparture": "2026-05-11T21:50:00",
      "scheduledArrival": "2026-05-12T08:30:00",
      "actualArrival": "2026-05-12T09:15:00",
      "status": "REALIZADO",
      "departureDelayMinutes": -10
    }
  ],
  "page": 0, "size": 1, "totalElements": 394, "totalPages": 394, "sort": "airline,asc"
}
```

| Campo do voo | Conteúdo |
|--------------|----------|
| `airline`, `flightNumber` | Companhia (ICAO) e número do voo, como publicados |
| `origin`, `destination` | Aeroportos ICAO |
| `scheduledDeparture`, `actualDeparture`, `scheduledArrival`, `actualArrival` | Horários sem fuso, ou `null` quando ausentes (reais nos cancelados, previstos nos realizados sem horário previsto) |
| `status` | `REALIZADO` ou `CANCELADO` |
| `departureDelayMinutes` | Partida real menos prevista, em minutos. **Negativo** quando o voo saiu adiantado. `null` sem os dois horários |

## Erros

Os erros vêm em `application/problem+json`, com `detail` citando o parâmetro.
Nos 400, o campo extra `parameter` traz o nome do parâmetro.

| HTTP | Quando | Exemplo de `detail` |
|------|--------|---------------------|
| 400 | Parâmetro inválido | `Parâmetro airport deve ser um código ICAO de 4 caracteres (ex.: SBGR para Guarulhos); códigos IATA como "GRU" não são aceitos` |
| 404 | ICAO bem formado sem aeroporto na base | `Aeroporto ZZZZ não encontrado` |
| 405 | Método diferente de `GET` | |
| 503 | PostgreSQL indisponível | `PostgreSQL indisponível; tente novamente` |

## Tela de busca

Abra `http://localhost:8080/` com a aplicação no ar. A tela vem empacotada no
JAR e consome o `GET /flights`.

| Elemento | Comportamento |
|----------|---------------|
| Aeroporto | Obrigatório, código ICAO convertido para maiúsculas (`sbgr` vira `SBGR`). Vazio: aviso, sem consulta |
| Sentido / Situação | `Ambos`/`Saídas`/`Chegadas` e `Todas`/`Realizado`/`Cancelado` |
| Data inicial / final | Opcionais, inclusivas (partida de referência) |
| Buscar | Consulta a API e volta para a primeira página |
| Tabela | 10 colunas. `—` para horário ou atraso ausentes. Aviso de que os horários estão **sem fuso** |
| Ordenação | Clique em Companhia, Voo, Partida prevista, Chegada prevista ou Atraso. O 1º clique ordena crescente e o 2º inverte. A **API ordena o resultado inteiro**, e a tela volta para a página 1 |
| Paginação | "N voos", "Página X de Y", Anterior/Próxima (desabilitados nos limites), 20 por página, mantendo filtros e ordenação |
| Erros | Mostra o `detail` da API (ex.: aeroporto inexistente, código IATA). Sem resposta: "A API não respondeu". O resultado anterior é limpo |

## Desempenho

A consulta não usa índice dedicado. Medido com os dados reais (252.725
voos; `SBGR` com 75.062 em `BOTH`): cerca de 90 a 130 ms por página, mesmo em
páginas profundas.
