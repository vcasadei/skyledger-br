# Spec Delta

## Purpose

Oferece uma tela web simples, servida pela própria aplicação, para buscar
voos de um aeroporto com filtros e navegar pelos resultados em tabela
paginada e ordenável, consumindo a API `GET /flights`.

## ADDED Requirements

### Requirement: Tela servida pela aplicação
A aplicação SHALL servir a tela de busca em `GET /`, a partir de arquivos
estáticos empacotados no próprio JAR, na mesma origem da API: nenhum
servidor ou processo adicional MUST ser necessário para usá-la depois de
`./mvnw verify` ou `./mvnw spring-boot:run`. A tela MUST obter dados
exclusivamente pela API `GET /flights`.

#### Scenario: Abrir a tela
- **AS A** usuário no navegador, com a aplicação no ar
- **WHEN** abro `http://localhost:8080/`
- **THEN** vejo o formulário de busca, sem nenhuma consulta feita ainda

### Requirement: Filtros de busca
A tela SHALL ter os campos: aeroporto (código ICAO, obrigatório), sentido
(`Saídas`, `Chegadas` ou `Ambos`, padrão `Ambos`), data inicial e data final
(opcionais) e situação (`Todas`, `Realizado` ou `Cancelado`, padrão `Todas`).
Buscar MUST enviar à API os filtros preenchidos e voltar para a primeira
página. O campo de aeroporto MUST converter o texto digitado para maiúsculas
e informar que o código esperado é ICAO (ex.: `SBGR`), não IATA.

#### Scenario: Busca com filtros
- **AS A** usuário
- **WHEN** preencho aeroporto `sbgr`, sentido `Saídas`, situação `Cancelado` e clico em Buscar
- **THEN** a tela consulta a API com `airport=SBGR`, `direction=DEPARTURE`, `status=CANCELADO` e `page=0`
- **AND** exibe os voos retornados na tabela

#### Scenario: Busca sem aeroporto
- **AS A** usuário
- **WHEN** clico em Buscar com o campo de aeroporto vazio
- **THEN** a tela indica que o aeroporto é obrigatório e não consulta a API

### Requirement: Tabela de resultados
A tela SHALL exibir os voos numa tabela com as colunas: companhia, voo,
origem, destino, partida prevista, partida real, chegada prevista, chegada
real, situação e atraso de partida (em minutos). Horários ausentes e atraso
nulo MUST aparecer como `—`. A tela MUST informar, junto da tabela, que os
horários são os publicados pela ANAC, sem conversão de fuso. Uma busca sem
resultados MUST mostrar a mensagem "Nenhum voo encontrado".

#### Scenario: Voo cancelado na tabela
- **AS A** usuário
- **WHEN** a busca retorna um voo `CANCELADO`
- **THEN** a linha mostra as partidas e chegadas previstas, `—` nos horários reais e no atraso, e a situação Cancelado

#### Scenario: Busca sem resultados
- **AS A** usuário
- **WHEN** a API retorna `content` vazio na primeira página
- **THEN** a tela mostra "Nenhum voo encontrado" no lugar da tabela

### Requirement: Paginação na tela
A tela SHALL mostrar o total de voos encontrados, a página atual e o total de
páginas, com controles de página anterior e próxima. O controle de anterior
MUST ficar desabilitado na primeira página, e o de próxima, na última. Mudar
de página MUST manter os filtros e a ordenação.

#### Scenario: Navegar para a próxima página
- **AS A** usuário com uma busca de 45 voos e 20 por página
- **WHEN** clico em Próxima
- **THEN** a tela consulta a API com `page=1` e os mesmos filtros e ordenação
- **AND** mostra "Página 2 de 3" e "45 voos"

#### Scenario: Limites da paginação
- **AS A** usuário na primeira página de uma busca
- **WHEN** olho os controles de paginação
- **THEN** Anterior está desabilitado, e na última página Próxima está desabilitado

### Requirement: Ordenação pelas colunas
Os cabeçalhos de companhia, voo, partida prevista, chegada prevista e atraso
SHALL ser clicáveis e ordenar o resultado inteiro pela API (`sort`), e não
só a página carregada. O primeiro clique numa coluna ordena de forma
crescente, e o segundo clique na mesma coluna inverte a direção. A coluna e a
direção ativas MUST ser indicadas visualmente, e ordenar MUST voltar para a
primeira página.

#### Scenario: Ordenar por atraso
- **AS A** usuário com resultados na tabela
- **WHEN** clico duas vezes no cabeçalho Atraso
- **THEN** a tela consulta a API com `sort=delay,asc` e depois com `sort=delay,desc`, ambas com `page=0`
- **AND** o cabeçalho Atraso indica a ordem decrescente

### Requirement: Erros da API na tela
Quando a API responder com erro, a tela SHALL mostrar o `detail` do corpo
`application/problem+json` numa mensagem visível e não MUST exibir resultados
de uma busca anterior como se fossem da atual. Uma falha de rede MUST mostrar
uma mensagem de que a API não respondeu.

#### Scenario: Aeroporto inexistente
- **AS A** usuário
- **WHEN** busco o aeroporto `ZZZZ` e a API responde 404
- **THEN** a tela mostra o `detail` da resposta e nenhuma tabela de resultados

#### Scenario: Código IATA
- **AS A** usuário
- **WHEN** busco `GRU` e a API responde 400
- **THEN** a tela mostra a mensagem da API, que explica que o esperado é um código ICAO
