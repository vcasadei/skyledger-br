# Proposal

## Why

O Skyledger BR ainda não tem código nem infraestrutura, e as próximas etapas
(esqueleto Spring Boot, ingestão da ANAC, analytics com cache, cold storage)
dependem de PostgreSQL, Redis e um armazenamento de objetos compatível com S3.
Um ambiente local reproduzível com um único comando evita que cada etapa
reinvente a própria infraestrutura e garante que todos desenvolvam contra as
mesmas versões de serviço.

## What Changes

- Adiciona um arquivo Docker Compose na raiz do repositório que sobe, com
  `docker compose up`, os três serviços de apoio ao desenvolvimento:
  - **PostgreSQL 16** com banco `skyledger`, usuário `skyledger` e senha de
    desenvolvimento `skyledger`, na porta 5432, com volume nomeado para
    persistir dados entre reinícios do container.
  - **Redis 8** na porta 6379, sem senha.
  - **SeaweedFS (4.40 ou superior)** expondo a API S3, com credenciais S3
    fixas de desenvolvimento, volume nomeado, e o bucket `skyledger-raw`
    criado automaticamente na subida.
- Adiciona documentação de uso do ambiente local (como subir, parar, zerar os
  dados e quais portas e credenciais de desenvolvimento são usadas).
- Deixa explícito, no próprio arquivo Compose e na documentação, que essa
  configuração é exclusivamente para desenvolvimento local e não deve ser
  usada como base de produção.

Nenhuma mudança é **BREAKING**: o repositório ainda não tem código nem
consumidores.

## Capabilities

### New Capabilities
- `local-dev-environment`: ambiente de desenvolvimento local via Docker
  Compose - serviços disponíveis, portas, persistência, credenciais de
  desenvolvimento e provisionamento do bucket S3 na subida.

### Modified Capabilities
<!-- Nenhuma: não existem specs no projeto ainda. -->

## Impact

- **Arquivos novos**: arquivo Compose na raiz, configuração de apoio do
  SeaweedFS (credenciais S3 e provisionamento do bucket), exemplo de variáveis
  de ambiente, `.gitignore`, script de smoke test do ambiente e documentação
  em `docs/`. Nenhum código Java é criado nesta mudança.
- **Dependências**: imagens Docker oficiais de PostgreSQL 16, Redis 8 e
  SeaweedFS, todas já previstas na stack do projeto. O provisionamento do
  bucket usa a própria imagem do SeaweedFS, sem adicionar imagens ou
  ferramentas de terceiros (por exemplo AWS CLI ou MinIO Client). Pré-requisito
  para quem desenvolve: Docker com Docker Compose v2.
- **Segredos (somente desenvolvimento)**: senha do PostgreSQL
  (`POSTGRES_PASSWORD`) e o par de credenciais S3 do SeaweedFS
  (`S3_ACCESS_KEY` / `S3_SECRET_KEY`). Todos têm valores padrão
  deliberadamente triviais, aceitáveis apenas localmente, e podem ser
  sobrescritos por variável de ambiente. Nenhum segredo real é introduzido.
- **Redis**: nada é armazenado nesta mudança. O serviço só fica disponível
  para o cache que virá na etapa de analytics. Ainda não existe aplicação,
  então não há comportamento de indisponibilidade a definir. Isso fica para a
  mudança que introduzir o cache.
- **SeaweedFS**: cria o bucket `skyledger-raw`, reservado aos arquivos brutos
  da ANAC (cold storage futuro). Nenhum objeto é gravado nesta mudança, e o
  comportamento em caso de indisponibilidade fica para a mudança que o usar.
- **Esquema de banco**: nenhum. Só o banco vazio `skyledger` é criado. As
  tabelas virão por migrações versionadas no esqueleto da aplicação.
- **Portas do host**: 5432, 6379 e a porta S3 do SeaweedFS ficam ocupadas
  enquanto o ambiente está de pé. Pode haver conflito com serviços que já
  rodem localmente nessas portas.
- **Dados pessoais (LGPD), filas, API pública**: não se aplica.
