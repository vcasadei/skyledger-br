# Tasks

## 1. Base do repositório e segredos de desenvolvimento

- [ ] 1.1 Criar `.gitignore` na raiz listando `.env`, e verificar com `git check-ignore .env`, que deve imprimir `.env`
- [ ] 1.2 Criar `.env.example` com `POSTGRES_PASSWORD`, `S3_ACCESS_KEY` e `S3_SECRET_KEY` e seus valores padrão triviais de desenvolvimento, e verificar que `git status` mostra `.env.example` como rastreável
- [ ] 1.3 Criar `compose.yaml` com `name: skyledger` e, no topo, o aviso de uso exclusivo em desenvolvimento local e proibição de uso em produção (D1). Verificar com `docker compose config --quiet`, que deve passar, e cobrir o cenário "Leitura do arquivo Compose" inspecionando as primeiras linhas
- [ ] 1.4 Criar `docs/ambiente-local.md` (PT-BR) com pré-requisitos (Docker, Compose v2.23.1+), o aviso de uso exclusivo em desenvolvimento e a tabela de variáveis sobrescrevíveis, e criar `README.md` apontando para ele. Verificar que o link do README resolve para o arquivo

## 2. PostgreSQL

- [ ] 2.1 Adicionar o serviço `postgres` com tag `postgres:16.<minor>` fixada (D2), banco e usuário `skyledger`, senha `${POSTGRES_PASSWORD:-skyledger}`, porta `127.0.0.1:5432:5432` (D7), volume nomeado `postgres-data` (D8) e healthcheck `pg_isready` (D6). Verificar com `docker compose up -d --wait postgres` e `docker compose ps`, que deve mostrar `healthy`
- [ ] 2.2 Criar `scripts/verify-local-env.sh` (D10), com saída não zero no primeiro cenário que falhar, e implementar nele os cenários "Conexão com as credenciais padrão" (`SELECT version()` informa 16) e "Senha incorreta" (conexão recusada). Verificar executando o script
- [ ] 2.3 Implementar no script os cenários "Dados sobrevivem ao reinício" (criar tabela, `down`, `up --wait`, tabela presente) e "Reset explícito do ambiente" (`down -v`, `up --wait`, tabela ausente). Verificar executando o script
- [ ] 2.4 Verificar manualmente os cenários "Sobrescrita por variável de ambiente" e "Sobrescrita com volume já inicializado" do PostgreSQL, com `POSTGRES_PASSWORD=outra docker compose up` sobre volume novo e depois sobre volume existente, e registrar os passos em `docs/ambiente-local.md`
- [ ] 2.5 Documentar em `docs/ambiente-local.md` a linha do PostgreSQL na tabela de serviços (porta, banco, usuário, senha padrão, volume) e a ressalva de que a senha só se aplica a volume novo

## 3. Redis

- [ ] 3.1 Adicionar o serviço `redis` com tag `redis:8.<minor>` fixada, sem senha, com `--save "" --appendonly no` (D8), porta `127.0.0.1:6379:6379` e healthcheck `redis-cli ping`. Verificar que `docker volume ls` não mostra volume anônimo novo após `docker compose up -d --wait redis`
- [ ] 3.2 Implementar no script o cenário "Comando sem credenciais" (`PING` responde `PONG` e `INFO server` informa `redis_version` 8.x). Verificar executando o script
- [ ] 3.3 Documentar em `docs/ambiente-local.md` a linha do Redis (porta, sem senha, sem persistência)

## 4. SeaweedFS com API S3 autenticada

- [ ] 4.1 Escolher a tag exata `chrislusf/seaweedfs` ≥ 4.40 (D2) e adicionar o serviço `seaweedfs` com `weed server -s3` (D3), volume nomeado `seaweedfs-data`, porta `127.0.0.1:8333:8333`, sem publicar 9333, 8080 e 8888, e healthcheck do gateway S3 (D6). Verificar com `docker compose up -d --wait seaweedfs` mostrando `healthy` e `docker compose port seaweedfs 9333` sem retornar mapeamento
- [ ] 4.2 Declarar o JSON de identidades S3 como `configs:` com `content:` que interpola `${S3_ACCESS_KEY:-...}` e `${S3_SECRET_KEY:-...}`, com uma única identidade `Admin` e nenhuma anônima, e montá-lo via `-s3.config` (D4). Se `configs.content` não funcionar na versão de Compose disponível, aplicar o plano B do D4 e registrar a escolha no `design.md`. Verificar com `docker compose config`, que deve mostrar as credenciais interpoladas
- [ ] 4.3 Implementar no script o cenário "Credenciais inválidas ou ausentes" (`curl` anônimo e `curl --aws-sigv4` com secret errada retornam 403). Verificar executando o script
- [ ] 4.4 Implementar no script o cenário "Objetos sobrevivem ao reinício" (PUT assinado de um objeto em `skyledger-raw`, `down`, `up --wait`, GET assinado retorna o mesmo conteúdo). Verificar executando o script
- [ ] 4.5 Documentar em `docs/ambiente-local.md` a linha do SeaweedFS (endpoint `http://localhost:8333`, credenciais padrão, bucket, volume) e um exemplo de acesso com cliente S3

## 5. Provisionamento do bucket na subida

- [ ] 5.1 Adicionar o serviço one-shot `seaweedfs-init`, com a mesma imagem e tag do `seaweedfs`, `depends_on: seaweedfs: condition: service_healthy` e `restart: "no"`, que roda `weed shell` e cria `skyledger-raw` apenas se `s3.bucket.list` não o listar (D5). Verificar com `docker compose logs seaweedfs-init` mostrando saída com código 0
- [ ] 5.2 Confirmar que `docker compose up -d --wait` só retorna depois de `seaweedfs-init` terminar com sucesso (D6). Se o Compose tratar a saída do one-shot como falha, aplicar o plano B dos Riscos (healthcheck do `seaweedfs` exigindo o bucket) e registrar no `design.md`. Verificar com `docker compose up -d --wait` retornando código 0 logo após um `down -v`
- [ ] 5.3 Implementar no script os cenários "Acesso com as credenciais de desenvolvimento" (listagem assinada inclui `skyledger-raw`), "Bucket criado na primeira subida" (bucket existe e vazio após `down -v` + `up --wait`) e "Subida com bucket já existente" (segundo `up --wait` com objeto no bucket termina com sucesso e o objeto permanece). Verificar executando o script
- [ ] 5.4 Documentar em `docs/ambiente-local.md` o provisionamento automático do bucket e os comandos de subir (`docker compose up -d --wait`), parar (`docker compose down`) e zerar (`docker compose down -v`)

## 6. Verificação integrada

- [ ] 6.1 Em ambiente limpo (`docker compose down -v`, sem `.env`), executar `scripts/verify-local-env.sh` do início ao fim, cobrindo o cenário "Primeira subida em máquina limpa", e confirmar saída com código 0
- [ ] 6.2 Verificar manualmente o cenário "Porta do host já ocupada", ocupando a 6379 com outro processo: o `up` falha citando a porta e `docker volume ls` mostra os volumes do projeto intactos
- [ ] 6.3 Verificar manualmente o cenário "Conexão a partir de outra máquina da rede": `docker compose ps` / `ss -ltn` mostram as portas 5432, 6379 e 8333 ligadas só a `127.0.0.1`
- [ ] 6.4 Seguir `docs/ambiente-local.md` do zero, exatamente como escrito, e confirmar que todos os comandos documentados funcionam

## 7. Quality Gates

- [ ] 7.1 O arquivo Compose é válido: `docker compose config --quiet`
- [ ] 7.2 O script de verificação tem sintaxe válida: `bash -n scripts/verify-local-env.sh`
- [ ] 7.3 Nenhum `.env` ou credencial fora dos padrões de desenvolvimento está rastreado: `git ls-files | grep -x '.env'` não retorna nada, e `git diff --cached` não contém segredos além dos padrões triviais documentados
- [ ] 7.4 Registrar que os gates Maven (`./mvnw verify`) ainda não se aplicam, porque o projeto Maven só nasce na mudança do esqueleto Spring Boot
