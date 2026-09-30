# local-dev-environment Specification

## Purpose

Fornece a quem desenvolve o Skyledger BR um ambiente local reproduzível, com
PostgreSQL, Redis e armazenamento de objetos compatível com S3, subido por um
único comando Docker Compose e destinado exclusivamente a desenvolvimento.

## Requirements

### Requirement: Subida do ambiente com um único comando
O repositório SHALL conter, na raiz, um arquivo Docker Compose que sobe
PostgreSQL, Redis e SeaweedFS com um único comando, sem nenhum arquivo de
variáveis de ambiente. Cada serviço MUST expor um healthcheck, de modo que
`docker compose up --wait` só retorne com sucesso quando os três serviços
estiverem prontos para aceitar conexões e o bucket S3 estiver provisionado.

#### Scenario: Primeira subida em máquina limpa
- **AS A** desenvolvedor com Docker e Docker Compose v2 instalados
- **WHEN** executo `docker compose up -d --wait` na raiz do repositório sem nenhum `.env`
- **THEN** o comando termina com sucesso
- **AND** PostgreSQL, Redis e SeaweedFS aparecem como `healthy` em `docker compose ps`

#### Scenario: Porta do host já ocupada
- **AS A** desenvolvedor que já tem outro serviço escutando na porta 5432, 6379 ou 8333
- **WHEN** executo `docker compose up -d --wait`
- **THEN** o comando falha informando qual porta está em conflito
- **AND** os volumes nomeados já existentes não são removidos nem alterados

### Requirement: PostgreSQL de desenvolvimento
O ambiente SHALL fornecer PostgreSQL na versão major 16, com o banco
`skyledger` criado, acessível pelo usuário `skyledger` com a senha de
desenvolvimento `skyledger`, na porta 5432 do host.

#### Scenario: Conexão com as credenciais padrão
- **AS A** desenvolvedor com o ambiente de pé
- **WHEN** conecto em `localhost:5432`, banco `skyledger`, usuário `skyledger`, senha `skyledger`
- **THEN** a conexão é aceita
- **AND** `SELECT version()` informa PostgreSQL 16

#### Scenario: Senha incorreta
- **AS A** desenvolvedor com o ambiente de pé
- **WHEN** conecto como usuário `skyledger` com uma senha diferente da configurada
- **THEN** a conexão é recusada com erro de autenticação

### Requirement: Persistência dos dados do PostgreSQL
Os dados do PostgreSQL SHALL ficar em um volume Docker nomeado, e MUST
sobreviver a parar, remover e recriar o container. Os dados MUST ser
removidos somente quando os volumes forem removidos explicitamente.

#### Scenario: Dados sobrevivem ao reinício
- **AS A** desenvolvedor que criou uma tabela com linhas no banco `skyledger`
- **WHEN** executo `docker compose down` seguido de `docker compose up -d --wait`
- **THEN** a tabela e suas linhas continuam presentes

#### Scenario: Reset explícito do ambiente
- **AS A** desenvolvedor que quer começar do zero
- **WHEN** executo `docker compose down -v` seguido de `docker compose up -d --wait`
- **THEN** o banco `skyledger` existe e não contém as tabelas criadas antes

### Requirement: Redis de desenvolvimento
O ambiente SHALL fornecer Redis na versão major 8, na porta 6379 do host,
sem autenticação.

#### Scenario: Comando sem credenciais
- **AS A** desenvolvedor com o ambiente de pé
- **WHEN** envio `PING` para `localhost:6379` sem senha
- **THEN** o Redis responde `PONG`
- **AND** `INFO server` informa `redis_version` com major 8

### Requirement: Armazenamento de objetos compatível com S3
O ambiente SHALL fornecer SeaweedFS na versão 4.40 ou superior, expondo a API
S3 na porta 8333 do host. A API S3 MUST exigir credenciais: requisições
assinadas com o par de credenciais de desenvolvimento configurado são aceitas,
e requisições sem credenciais ou com credenciais inválidas são recusadas. Os
dados do SeaweedFS MUST ficar em um volume Docker nomeado e sobreviver à
recriação do container.

#### Scenario: Acesso com as credenciais de desenvolvimento
- **AS A** cliente S3 configurado com o endpoint `http://localhost:8333` e as credenciais de desenvolvimento
- **WHEN** listo os buckets
- **THEN** a requisição é aceita e a resposta inclui o bucket `skyledger-raw`

#### Scenario: Credenciais inválidas ou ausentes
- **AS A** cliente S3 apontando para `http://localhost:8333`
- **WHEN** envio uma requisição anônima ou assinada com uma secret key incorreta
- **THEN** a requisição é recusada com erro de acesso negado (HTTP 403)

#### Scenario: Objetos sobrevivem ao reinício
- **AS A** desenvolvedor que gravou um objeto no bucket `skyledger-raw`
- **WHEN** executo `docker compose down` seguido de `docker compose up -d --wait`
- **THEN** o objeto continua disponível com o mesmo conteúdo

### Requirement: Bucket provisionado na subida
O bucket `skyledger-raw` SHALL existir assim que a subida do ambiente
terminar, sem nenhum passo manual. O provisionamento MUST ser idempotente:
subir de novo um ambiente em que o bucket já existe não falha e não altera os
objetos que ele contém.

#### Scenario: Bucket criado na primeira subida
- **AS A** desenvolvedor em uma máquina sem volumes do projeto
- **WHEN** a subida com `docker compose up -d --wait` termina
- **THEN** o bucket `skyledger-raw` existe e está vazio

#### Scenario: Subida com bucket já existente
- **AS A** desenvolvedor cujo bucket `skyledger-raw` já existe e contém objetos
- **WHEN** executo `docker compose up -d --wait` novamente
- **THEN** o comando termina com sucesso
- **AND** o bucket e seus objetos permanecem inalterados

### Requirement: Credenciais de desenvolvimento sobrescrevíveis
A senha do PostgreSQL e o par de credenciais S3 SHALL ter valores padrão
triviais de desenvolvimento, usados quando nada é configurado, e MUST poder
ser sobrescritos por variáveis de ambiente (ou por um arquivo `.env` local não
versionado) sem editar o arquivo Compose. O repositório MUST conter um arquivo
de exemplo com todas essas variáveis e seus valores padrão, e MUST NOT
versionar um `.env` real.

#### Scenario: Sobrescrita por variável de ambiente
- **AS A** desenvolvedor que define `POSTGRES_PASSWORD` com outro valor antes da primeira subida
- **WHEN** subo o ambiente e conecto no PostgreSQL
- **THEN** a senha aceita é a definida na variável, e a senha padrão é recusada

#### Scenario: Sobrescrita com volume já inicializado
- **AS A** desenvolvedor que altera `POSTGRES_PASSWORD` depois que o volume do PostgreSQL já foi criado
- **WHEN** subo o ambiente novamente
- **THEN** a senha anterior continua valendo, porque o PostgreSQL só aplica a variável ao inicializar um volume vazio
- **AND** a documentação do ambiente explica que é preciso remover o volume para aplicar a nova senha

### Requirement: Acesso restrito à máquina local
As portas publicadas pelos serviços SHALL aceitar conexões apenas pela
interface de loopback do host (`127.0.0.1`), para que o Redis sem senha e as
credenciais triviais não fiquem acessíveis a outras máquinas da rede.

#### Scenario: Conexão a partir de outra máquina da rede
- **AS A** outra máquina na mesma rede do desenvolvedor
- **WHEN** tento conectar no IP de rede do host nas portas 5432, 6379 ou 8333
- **THEN** a conexão é recusada

### Requirement: Identificação como configuração exclusiva de desenvolvimento
O arquivo Compose e a documentação do ambiente local SHALL declarar, de forma
visível, que a configuração é exclusiva para desenvolvimento local e MUST NOT
ser usada como base para produção. A documentação MUST listar portas,
credenciais padrão, como subir, parar e zerar os dados.

#### Scenario: Leitura do arquivo Compose
- **AS A** pessoa que abre o arquivo Compose pela primeira vez
- **WHEN** lê o início do arquivo
- **THEN** encontra um aviso de que a configuração é só para desenvolvimento local e não serve para produção
