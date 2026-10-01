# Spec Delta

## Purpose

Carrega no banco do Skyledger BR os voos do VRA (Voo Regular Ativo) e o
cadastro de Aeródromos Públicos da ANAC, a partir do repositório `anac-data`,
de forma validada, rastreável e idempotente, como base para analytics de
pontualidade e simulação de risco.

## ADDED Requirements

### Requirement: Localização configurável da fonte de dados
A ingestão SHALL ler os arquivos a partir da raiz do repositório `anac-data`
configurada na propriedade `skyledger.anac-data.path`, sobrescrevível pela
variável `ANAC_DATA_PATH` (ambiente ou `.env`). O VRA fica em
`data/raw/anac/vra/` e o cadastro em
`data/raw/anac/aerodromos/AerodromosPublicos.csv`. Nenhum caminho de dados
MUST estar fixo no código, nos testes ou em fixtures. Quando a fonte está
inutilizável (propriedade ausente ou vazia, diretório inexistente, cadastro
ausente ou nenhum arquivo do VRA), a ingestão MUST recusar a execução sem
gravar nada.

#### Scenario: Caminho não configurado
- **AS A** operador com a aplicação no ar e `ANAC_DATA_PATH` não definida
- **WHEN** envio `POST /api/ingestions`
- **THEN** recebo HTTP 503 com corpo `application/problem+json` cujo `detail` cita `skyledger.anac-data.path`
- **AND** nenhum registro é gravado em nenhuma tabela

#### Scenario: Caminho inexistente
- **AS A** operador com `ANAC_DATA_PATH` apontando para um diretório que não existe
- **WHEN** envio `POST /api/ingestions`
- **THEN** recebo HTTP 503 com `detail` citando o caminho configurado
- **AND** nenhum registro é gravado

#### Scenario: Fonte sem arquivos do VRA
- **AS A** operador cuja raiz configurada tem o cadastro, mas nenhum `VRA_*.csv`
- **WHEN** envio `POST /api/ingestions`
- **THEN** recebo HTTP 503 com `detail` informando que não há arquivos do VRA
- **AND** nenhum registro é gravado

### Requirement: Endpoint de ingestão
A aplicação SHALL expor `POST /api/ingestions`, sem corpo de requisição, que
processa primeiro o cadastro de Aeródromos Públicos e depois cada arquivo
`VRA_<AAAA><M ou MM>.csv`, em ordem cronológica de ano e mês lidos do nome. O
mês sem zero à esquerda MUST ser interpretado numericamente: `VRA_20269`
vem antes de `VRA_202610`. Arquivos fora desse padrão de nome MUST ser
ignorados. A resposta de sucesso é HTTP 200 com `application/json`:

| Campo | Tipo | Conteúdo |
|-------|------|----------|
| `startedAt`, `finishedAt` | string (data-hora ISO-8601) | Início e fim da execução |
| `files[]` | array | Um item por arquivo processado, na ordem de processamento |
| `files[].dataset` | string | `AERODROMOS` ou `VRA` |
| `files[].file` | string | Nome do arquivo |
| `files[].status` | string | `COMPLETED` ou `REJECTED` |
| `files[].sourceUpdatedAt` | string (data) ou null | Data de "Atualizado em" da linha 1 |
| `files[].sha256` | string | Hash SHA-256 do conteúdo do arquivo |
| `files[].rowsRead` | inteiro | Linhas de dados lidas (sem metadado e cabeçalho) |
| `files[].inserted` | inteiro | Registros novos |
| `files[].updated` | inteiro | Registros existentes atualizados (só `AERODROMOS`; `0` no VRA) |
| `files[].duplicates` | inteiro | Linhas válidas que já existiam pela chave natural |
| `files[].rejected` | inteiro | Linhas inválidas |
| `files[].rejections[]` | array | Até 100 rejeições: `line` (número da linha física no arquivo) e `reason` |
| `files[].error` | string ou null | Motivo, quando o arquivo inteiro foi rejeitado |

Respostas de erro usam `application/problem+json`: 409 quando já há uma
ingestão em andamento, 503 quando a fonte está inutilizável (requisito
anterior) ou o PostgreSQL está indisponível, e 405 para métodos diferentes de
`POST`.

#### Scenario: Ingestão completa dos arquivos do anac-data
- **AS A** operador com o `anac-data` configurado e o banco vazio
- **WHEN** envio `POST /api/ingestions`
- **THEN** recebo HTTP 200 com 4 itens em `files`: `AerodromosPublicos.csv`, `VRA_20265.csv`, `VRA_20266.csv` e `VRA_20267.csv`, nessa ordem
- **AND** todos com `status` `COMPLETED` e `rejected` `0`
- **AND** os itens do VRA reportam `inserted`/`duplicates` de 82102/18, 81117/2 e 89506/11

#### Scenario: Ordem cronológica com mês sem zero à esquerda
- **AS A** operador cuja fonte tem `VRA_202610.csv` e `VRA_20269.csv`
- **WHEN** envio `POST /api/ingestions`
- **THEN** `VRA_20269.csv` aparece em `files` antes de `VRA_202610.csv`

#### Scenario: Ingestão concorrente
- **AS A** operador
- **WHEN** envio `POST /api/ingestions` enquanto outra ingestão ainda está em andamento
- **THEN** recebo HTTP 409 e a ingestão em andamento continua sem interferência

#### Scenario: Método não suportado
- **AS A** cliente qualquer
- **WHEN** envio `GET /api/ingestions`
- **THEN** recebo HTTP 405

### Requirement: Layout dos arquivos do VRA
Cada arquivo do VRA SHALL ser lido como UTF-8, com o BOM inicial removido
quando presente, e com `;` como delimitador. A linha 1 MUST ser o metadado
`Atualizado em: AAAA-MM-DD`, cuja data é registrada como `sourceUpdatedAt`. A
linha 2 MUST ser o cabeçalho com exatamente estas 12 colunas, em qualquer
ordem: `ICAO Empresa Aérea`, `Número Voo`, `Código Autorização (DI)`,
`Código Tipo Linha`, `ICAO Aeródromo Origem`, `ICAO Aeródromo Destino`,
`Partida Prevista`, `Partida Real`, `Chegada Prevista`, `Chegada Real`,
`Situação Voo`, `Código Justificativa`. Os valores MUST ser associados às
colunas pelo nome do cabeçalho. Um arquivo com metadado ou cabeçalho fora
desse formato MUST ser rejeitado inteiro (`status` `REJECTED`, nenhuma linha
gravada), e os demais arquivos MUST continuar sendo processados.

#### Scenario: Colunas em outra ordem
- **AS A** operador com um arquivo VRA válido cujas colunas estão em ordem diferente da original
- **WHEN** a ingestão processa o arquivo
- **THEN** cada valor é gravado no campo da coluna de mesmo nome
- **AND** o arquivo termina `COMPLETED`

#### Scenario: Metadado ausente
- **AS A** operador com um arquivo VRA cuja linha 1 já é o cabeçalho
- **WHEN** a ingestão processa a fonte
- **THEN** o arquivo termina `REJECTED` com `error` citando a linha 1
- **AND** nenhum voo dele é gravado, e os arquivos seguintes são processados normalmente

#### Scenario: Coluna ausente no cabeçalho
- **AS A** operador com um arquivo VRA sem a coluna `Partida Real`
- **WHEN** a ingestão processa o arquivo
- **THEN** o arquivo termina `REJECTED` com `error` citando `Partida Real`

#### Scenario: Arquivo sem BOM
- **AS A** operador com um arquivo VRA válido salvo em UTF-8 sem BOM
- **WHEN** a ingestão processa o arquivo
- **THEN** o arquivo é lido normalmente e termina `COMPLETED`

### Requirement: Interpretação dos valores do VRA
Os valores de cada linha do VRA SHALL ser interpretados assim:

- **Horários** (`Partida Prevista`, `Partida Real`, `Chegada Prevista`,
  `Chegada Real`): formato `AAAA-MM-DD HH:MM:SS`, com fração de segundo
  opcional que MUST ser descartada. São gravados exatamente como publicados,
  sem conversão de fuso. O literal `null` MUST virar ausência de valor e
  MUST NOT virar uma data, zero ou cancelamento.
- **Combinações válidas de situação e horários**:

  | `Situação Voo` | Prevista (partida e chegada) | Real (partida e chegada) | Significado |
  |----------------|------------------------------|--------------------------|-------------|
  | `REALIZADO` | presentes | presentes | Voo realizado |
  | `REALIZADO` | ambas `null` | presentes | Voo realizado sem horário previsto registrado |
  | `CANCELADO` | presentes | ambas `null` | Voo cancelado |

- **Códigos**: empresa com 3 caracteres `[A-Z0-9]`; aeroportos de origem e
  destino com 4 caracteres `[A-Z0-9]`. Número do voo, código DI, tipo de linha
  e código de justificativa MUST ser gravados como texto, exatamente como
  publicados (zeros à esquerda e letras preservados).

Uma linha fora dessas regras (combinação inválida, horário mal formado,
situação desconhecida, código inválido, ou número de campos diferente de 12)
MUST ser rejeitada e registrada com o número da linha e o motivo, sem
interromper o processamento das demais linhas do arquivo.

#### Scenario: Voo realizado sem horário previsto
- **AS A** operador
- **WHEN** a ingestão processa uma linha `REALIZADO` com `Partida Prevista` e `Chegada Prevista` iguais a `null`
- **THEN** o voo é gravado com situação `REALIZADO`, horários previstos ausentes e horários reais preenchidos

#### Scenario: Voo cancelado
- **AS A** operador
- **WHEN** a ingestão processa uma linha `CANCELADO` com `Partida Real` e `Chegada Real` iguais a `null`
- **THEN** o voo é gravado com situação `CANCELADO`, horários previstos preenchidos e horários reais ausentes

#### Scenario: Fração de segundo no horário
- **AS A** operador
- **WHEN** a ingestão processa `Partida Prevista` igual a `2026-05-21 00:00:00.100000000`
- **THEN** o horário gravado é `2026-05-21 00:00:00`

#### Scenario: Número do voo alfanumérico e com zero à esquerda
- **AS A** operador
- **WHEN** a ingestão processa linhas com `Número Voo` igual a `Z9610` e `0073`
- **THEN** os números são gravados exatamente como `Z9610` e `0073`

#### Scenario: Combinação inválida de situação e horários
- **AS A** operador
- **WHEN** a ingestão processa uma linha `CANCELADO` com `Partida Real` preenchida
- **THEN** a linha é rejeitada com seu número de linha e um motivo que cita a situação e o horário
- **AND** as outras linhas do arquivo são gravadas normalmente, e o arquivo termina `COMPLETED`

#### Scenario: Horário mal formado
- **AS A** operador
- **WHEN** a ingestão processa uma linha com `Partida Real` igual a `21/05/2026 10:00`
- **THEN** a linha é rejeitada com motivo citando `Partida Real`

#### Scenario: Número de campos incorreto
- **AS A** operador
- **WHEN** a ingestão processa uma linha com 11 campos
- **THEN** a linha é rejeitada com motivo citando o número de campos

### Requirement: Voo sem duplicação pela chave natural
Cada voo SHALL ser identificado pela chave natural (empresa, número do voo,
aeroporto de origem, partida prevista, partida real), em que valores ausentes
são considerados iguais entre si. Uma linha válida cuja chave já existe MUST
ser contada em `duplicates` e MUST NOT criar nem alterar registro. Isso vale
para duplicatas no mesmo arquivo, para o reprocessamento de um arquivo e para
o mesmo voo em arquivos de meses diferentes. Linhas que diferem em qualquer
campo da chave são voos distintos.

#### Scenario: Reprocessamento do mesmo arquivo
- **AS A** operador que já ingeriu todos os arquivos
- **WHEN** envio `POST /api/ingestions` de novo
- **THEN** cada arquivo do VRA reporta `inserted` `0` e `duplicates` igual a `rowsRead`
- **AND** o número de voos no banco continua 252725

#### Scenario: Linha duplicada dentro do arquivo
- **AS A** operador
- **WHEN** a ingestão processa um arquivo com duas linhas idênticas
- **THEN** um único voo é gravado e o arquivo reporta `duplicates` `1`

#### Scenario: Mesmo voo em dois meses
- **AS A** operador
- **WHEN** a ingestão processa um voo de 1º de junho presente nos arquivos de maio e de junho
- **THEN** um único voo é gravado, e o segundo arquivo conta a linha em `duplicates`

#### Scenario: Mesmo voo previsto com partidas reais diferentes
- **AS A** operador
- **WHEN** a ingestão processa duas linhas iguais em empresa, número, origem e partida prevista, mas com partidas reais diferentes
- **THEN** dois voos distintos são gravados

#### Scenario: Cancelado reprocessado
- **AS A** operador que já ingeriu um voo `CANCELADO`, cuja partida real é ausente
- **WHEN** o mesmo arquivo é processado de novo
- **THEN** o voo não é duplicado

### Requirement: Companhias aéreas e aeroportos a partir do VRA
Cada código de empresa e cada código de aeroporto (origem e destino) que
aparece numa linha válida do VRA SHALL existir nas tabelas de companhias e
aeroportos antes de o voo ser gravado. Um código novo MUST ser criado só com o
código. Um código existente MUST NOT ser alterado pela ingestão do VRA.

#### Scenario: Aeroporto estrangeiro
- **AS A** operador
- **WHEN** a ingestão processa um voo com destino `CYYZ`, que não está no cadastro de Aeródromos Públicos
- **THEN** o aeroporto `CYYZ` existe só com o código, sem nome, município, UF ou coordenadas

#### Scenario: Companhia nova
- **AS A** operador
- **WHEN** a ingestão processa o primeiro voo da empresa `AZU`
- **THEN** a companhia `AZU` é criada uma única vez, mesmo com milhares de voos dela no arquivo

### Requirement: Cadastro de Aeródromos Públicos
O arquivo `AerodromosPublicos.csv` SHALL ser lido como ISO-8859-1, com `;`
como delimitador e campos sem aspas. Uma aspa dupla dentro de um valor é um
caractere literal (`09°52'06"S`). A linha 1 MUST ser o metadado
`Atualizado em: AAAA-MM-DD`, e a linha 2, o cabeçalho com 18 colunas,
mapeadas pelo nome. Para cada linha válida, o aeroporto do `Código OACI` é
criado, ou, se já existir, atualizado com: nome, município, UF (como
publicada, por extenso), latitude (`LATGEOPOINT`) e longitude (`LONGEOPOINT`)
em graus decimais com ponto, altitude em metros com vírgula decimal, e
situação (`Cadastrado` ou `Interditado`). Coordenadas e altitude MUST ser
gravadas como decimais exatos, nunca como ponto flutuante binário. Município e
UF vazios viram ausência de valor. Linhas com código inválido, número de
campos diferente de 18 ou número mal formado MUST ser rejeitadas e
registradas. Metadado ou cabeçalho inválido MUST rejeitar o arquivo inteiro,
e a ingestão do VRA MUST continuar mesmo assim.

#### Scenario: Aeródromo cadastrado
- **AS A** operador
- **WHEN** a ingestão processa a linha de `SBRB`, com `LATGEOPOINT` `-9.8683333`, `LONGEOPOINT` `-67.898056` e `Altitude` `193,0`
- **THEN** o aeroporto `SBRB` tem nome `Plácido de Castro`, município `RIO BRANCO`, UF `Acre`, latitude `-9.8683333`, longitude `-67.898056` e altitude `193.0`

#### Scenario: Aeroporto criado pelo VRA e enriquecido depois
- **AS A** operador cujo banco tem o aeroporto `SBXX` só com o código, criado por um voo
- **WHEN** a ingestão processa um cadastro que contém `SBXX`
- **THEN** o aeroporto `SBXX` passa a ter os dados do cadastro, e o arquivo o conta em `updated`

#### Scenario: Reprocessamento do cadastro
- **AS A** operador que já ingeriu o cadastro
- **WHEN** a ingestão processa o mesmo cadastro de novo
- **THEN** nenhum aeroporto é duplicado e os 496 aeródromos continuam com os mesmos dados

#### Scenario: Município ausente
- **AS A** operador
- **WHEN** a ingestão processa um aeródromo com `Município` e `UF` vazios
- **THEN** o aeroporto é gravado com município e UF ausentes, e os demais campos preenchidos

#### Scenario: Altitude mal formada
- **AS A** operador
- **WHEN** a ingestão processa um aeródromo com `Altitude` igual a `abc`
- **THEN** a linha é rejeitada com motivo citando `Altitude`, e as demais linhas são gravadas

### Requirement: Proveniência e atomicidade por arquivo
Cada arquivo processado SHALL gerar um registro de lote persistente com:
dataset, nome do arquivo, SHA-256, `sourceUpdatedAt`, contagens do relatório,
status, início e fim. Cada rejeição MUST ser persistida com lote, número da
linha e motivo, mesmo além das 100 devolvidas na resposta. Cada voo MUST
referenciar o lote que o inseriu. As linhas de um arquivo MUST ser gravadas
de forma atômica: uma falha de banco durante o arquivo desfaz todas as
gravações dele, sem deixar voos parciais.

#### Scenario: Histórico de reprocessamentos
- **AS A** operador que executou a ingestão duas vezes
- **WHEN** consulto os lotes no banco
- **THEN** há dois lotes para cada arquivo, com o mesmo SHA-256, e o segundo lote do VRA tem `inserted` `0`

#### Scenario: Voo rastreável até o arquivo
- **AS A** analista
- **WHEN** consulto um voo
- **THEN** consigo chegar ao lote, ao nome do arquivo e à data "Atualizado em" que o originaram

#### Scenario: Falha de banco no meio do arquivo
- **AS A** operador
- **WHEN** o PostgreSQL fica indisponível durante a gravação de um arquivo
- **THEN** recebo HTTP 503
- **AND** nenhum voo daquele arquivo fica gravado
