# Ingestão dos dados da ANAC

`POST /api/ingestions` carrega no PostgreSQL o cadastro de Aeródromos Públicos
e os arquivos mensais do VRA (Voo Regular Ativo) do repositório
`anac-data`. A ingestão é **idempotente**: reprocessar os
mesmos arquivos não duplica registros.

## Fonte

| Item | Valor |
|------|-------|
| Variável | `ANAC_DATA_PATH` (ambiente ou `.env`), raiz do repositório `anac-data` |
| Propriedade | `skyledger.anac-data.path`, sem valor padrão |
| Caminho relativo | Resolvido a partir do diretório de trabalho (rode a app da raiz deste repositório) |
| Exemplo | `ANAC_DATA_PATH=../anac-data`, já no `.env.example` |
| Cadastro | `<raiz>/data/raw/anac/aerodromos/AerodromosPublicos.csv` |
| VRA | `<raiz>/data/raw/anac/vra/VRA_<AAAA><M>.csv`, ordenados por ano e mês numéricos (`VRA_20269` antes de `VRA_202610`). Outros nomes são ignorados |

Ordem de processamento: o cadastro primeiro, depois cada VRA. Cada arquivo roda
em **uma transação**: uma falha de banco desfaz o arquivo inteiro.

## Layout dos arquivos

| | VRA | Aeródromos Públicos |
|-|-----|---------------------|
| Encoding | UTF-8, BOM opcional | ISO-8859-1 |
| Linha 1 | `Atualizado em: AAAA-MM-DD` (vira `sourceUpdatedAt`) | idem |
| Linha 2 | Cabeçalho com as 12 colunas, em qualquer ordem | Cabeçalho com as 18 colunas |
| Dados | 12 campos **entre aspas**, separados por `;`, sem `;` ou aspas internas | 18 campos **sem aspas**, separados por `;`. A aspa é literal (`09°52'06"S`) |
| Fora do layout | Metadado ou cabeçalho inválido: arquivo `REJECTED`, os demais seguem. Linha inválida: rejeitada, o arquivo segue | idem |

## Regras de valor do VRA

| `Situação Voo` | Prevista (partida e chegada) | Real (partida e chegada) | Gravado como |
|----------------|------------------------------|--------------------------|--------------|
| `REALIZADO` | presentes | presentes | Voo realizado |
| `REALIZADO` | `null` e `null` | presentes | Voo realizado **sem horário previsto** (não é cancelamento) |
| `CANCELADO` | presentes | `null` e `null` | Voo cancelado |
| qualquer outra combinação | | | Linha rejeitada |

- O literal `null` vira ausência de valor (`NULL`), nunca uma data ou zero.
- Os horários são gravados **como publicados**, sem conversão de fuso.
- A fração de segundo (`2026-05-21 00:00:00.100000000`) é descartada.
- `Número Voo`, DI, tipo de linha e justificativa são texto: `Z9610` e `0073`
  ficam exatamente assim.
- Empresa: 3 caracteres `[A-Z0-9]`. Aeroportos: 4 caracteres `[A-Z0-9]`.
  Origem igual ao destino é aceita.

## Regras do cadastro

- Cria ou atualiza o aeroporto do `Código OACI` com nome, município, UF (por
  extenso), latitude e longitude (`LATGEOPOINT`/`LONGEOPOINT`, ponto decimal),
  altitude (vírgula decimal) e situação (`Cadastrado`/`Interditado`).
- Coordenadas e altitude são decimais exatos (`numeric`), nunca ponto
  flutuante.
- Município e UF vazios ficam ausentes. A altitude vazia ou sem parte inteira
  (`,0`, caso do `SBMI`) fica **ausente**, e não zero.
- Os aeroportos que só aparecem no VRA (estrangeiros e brasileiros fora do
  cadastro) existem só com o código. Se o cadastro trouxer um deles depois, o
  aeroporto é completado e contado em `updated`.

## Chave natural e reprocessamento

Um voo é identificado por **(empresa, número do voo, origem, partida prevista,
partida real)**, com valores ausentes considerados iguais
(`UNIQUE NULLS NOT DISTINCT`).

| Situação | Resultado |
|----------|-----------|
| Mesmo arquivo processado de novo | `inserted` 0, `duplicates` = linhas válidas |
| Linha repetida no mesmo arquivo | 1 voo, contado em `duplicates` |
| Voo de transbordo presente em dois meses | 1 voo, atribuído ao primeiro arquivo |
| Mesmo voo previsto com partidas reais diferentes | 2 voos |
| Correção retroativa da partida real pela ANAC | Entra como voo novo (limitação aceita da chave) |

## Contrato do endpoint

`POST /api/ingestions`, sem corpo. A operação é síncrona: espere cerca de 1 a 2
minutos com os dados reais e configure o timeout do cliente HTTP para isso.

| HTTP | Quando | Corpo |
|------|--------|-------|
| 200 | Ingestão executada (mesmo com linhas ou arquivos rejeitados) | `application/json`, relatório abaixo |
| 409 | Já há uma ingestão em andamento | `application/problem+json` |
| 503 | Fonte não configurada, inexistente, sem cadastro ou sem VRA (**nada é gravado**), ou PostgreSQL indisponível | `application/problem+json`, com o motivo em `detail` |
| 405 | Método diferente de `POST` | `application/problem+json` |

Exemplo de relatório (resumido):

```json
{
  "startedAt": "2026-10-01T11:30:00.123-03:00",
  "finishedAt": "2026-10-01T11:31:10.456-03:00",
  "files": [
    { "dataset": "AERODROMOS", "file": "AerodromosPublicos.csv", "status": "COMPLETED",
      "sourceUpdatedAt": "2026-09-15", "sha256": "…", "rowsRead": 496, "inserted": 496,
      "updated": 0, "duplicates": 0, "rejected": 0, "rejections": [], "error": null },
    { "dataset": "VRA", "file": "VRA_20265.csv", "status": "COMPLETED",
      "sourceUpdatedAt": "2026-08-28", "sha256": "…", "rowsRead": 82120, "inserted": 82102,
      "updated": 0, "duplicates": 18, "rejected": 0, "rejections": [], "error": null }
  ]
}
```

`rejections` traz até 100 itens (`line`, o número da linha física, em que a
primeira de dados é a 3, e `reason`). Todas as rejeições ficam no banco.

## Uso

```bash
docker compose up -d --wait                 # PostgreSQL de pé (a app migra o esquema ao subir)
grep ANAC_DATA_PATH .env || echo 'ANAC_DATA_PATH=../anac-data' >> .env
./mvnw spring-boot:run                      # em outro terminal, a partir da raiz
curl -s -X POST http://localhost:8080/api/ingestions | python3 -m json.tool
```

## Consultas úteis

```sql
-- Lotes (um por arquivo processado), do mais recente
SELECT id, dataset, file_name, status, rows_read, inserted, updated, duplicates, rejected, started_at
FROM ingestion_batch ORDER BY id DESC;

-- Rejeições de um lote
SELECT line_number, reason FROM ingestion_rejection WHERE batch_id = :id ORDER BY line_number;

-- Origem de um voo
SELECT f.*, b.file_name, b.source_updated_at
FROM flight f JOIN ingestion_batch b ON b.id = f.batch_id
WHERE f.airline_icao = 'AZU' AND f.flight_number = '4840';
```
