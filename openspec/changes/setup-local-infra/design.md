# Design

## Context

O repositório ainda não tem código, build nem infraestrutura. Hoje só existem
a configuração do OpenSpec e o `CLAUDE.md`. Esta mudança não herda nenhuma
convenção de Compose ou de configuração, então as decisões abaixo estabelecem
a primeira. A stack fixada no projeto define as versões: PostgreSQL 16,
Redis 8 e SeaweedFS 4.40 ou superior (versões anteriores têm CVEs críticos).
Os requisitos estão em `specs/local-dev-environment/spec.md`, e a motivação em
`proposal.md`.

## Goals / Non-Goals

**Goals:**
- `docker compose up -d --wait` é o único passo necessário, e só retorna com o
  ambiente realmente utilizável, inclusive com o bucket já criado.
- Nomes de volumes e de projeto estáveis, independentes do nome do diretório
  do clone.
- Nenhum segredo versionado com valor diferente do padrão trivial de
  desenvolvimento.

**Non-Goals:**
- Integração com a aplicação Spring Boot (propriedades de conexão, suporte a
  Docker Compose do Spring Boot, Testcontainers). Isso fica para a mudança do
  esqueleto da aplicação.
- Persistência do Redis. Ele é só cache, e reiniciar vazio é o comportamento
  esperado.
- Alta disponibilidade, TLS, backup, limites de recursos ou qualquer
  endurecimento de produção.
- Consoles web ou portas administrativas do SeaweedFS (master, filer) no host.

## Decisions

### D1. Um único `compose.yaml` na raiz, com `name: skyledger`
`compose.yaml` é o nome preferido pela especificação Compose, e é também o
nome que o suporte a Docker Compose do Spring Boot procura por padrão. Isso
facilita a próxima mudança. O `name:` fixo evita que os volumes mudem de nome
conforme o diretório em que o repositório foi clonado.
*Alternativas:* `docker-compose.yml`, nome legado que ainda funciona mas não é
o preferido. Arquivos separados por serviço ou `profiles` foram descartados,
porque os três serviços são essenciais desde o início, conforme o pedido.

### D2. Tags de imagem fixadas, nunca `latest`
- `postgres:16.<minor>`: major fixada pelo requisito, minor fixada para que
  todos rodem a mesma versão.
- `redis:8.<minor>`.
- `chrislusf/seaweedfs:<versão exata ≥ 4.40>`: a versão exata é obrigatória
  aqui, porque o piso 4.40 é uma exigência de segurança. Uma tag flutuante não
  garante esse piso de forma verificável.

A versão exata de cada tag é escolhida na implementação (a mais recente
estável da linha) e registrada no próprio `compose.yaml`.
*Alternativa:* tags só de major (`postgres:16`) atualizam sozinhas, mas quebram
a reprodutibilidade entre máquinas.

### D3. SeaweedFS em um único container (`weed server -s3`)
`weed server` roda master, volume server, filer e gateway S3 no mesmo
processo. Para desenvolvimento, isso basta e é o modo documentado para uso
local. Um volume nomeado é montado no diretório de dados.
*Alternativa:* containers separados para master, volume, filer e S3, o que é
mais próximo de produção mas triplica a configuração sem benefício local.
Isso contraria o Non-Goal de endurecimento.

### D4. Credenciais S3 via arquivo de identidades gerado pelo Compose
O gateway S3 do SeaweedFS lê as identidades de um JSON (`-s3.config`) que
contém `accessKey` e `secretKey`. Para não versionar um JSON com credenciais
literais e manter a sobrescrita por variável de ambiente, o JSON é declarado
como `configs:` com `content:` inline no `compose.yaml`, interpolando
`${S3_ACCESS_KEY:-...}` e `${S3_SECRET_KEY:-...}`, e montado no container.
Uma única identidade de desenvolvimento recebe a ação `Admin`, e nenhuma
identidade anônima é declarada. Com isso, requisições sem assinatura são
recusadas.
*Alternativas:* (a) um JSON versionado em arquivo, que obriga a editar um
arquivo para trocar a credencial e fere o requisito de sobrescrita;
(b) um entrypoint shell que gera o JSON a partir do ambiente, que funciona mas
acrescenta um script próprio para manter; (c) variáveis de ambiente de
credencial lidas pelo próprio SeaweedFS, que dependem de comportamento de
versão que não verifiquei e por isso não são a escolha principal. Se `configs.content`
não estiver disponível no Compose de quem desenvolve (exige Compose v2.23.1+),
a alternativa (b) é o plano B.

### D5. Bucket garantido pelo próprio serviço `seaweedfs` (plano B aplicado)
O `command` do `seaweedfs` é um wrapper `sh`. Ele inicia `/entrypoint.sh
server ...` em segundo plano, espera o `/healthz` do S3 responder e roda
`weed shell` contra o próprio processo. O comando cria `skyledger-raw`
somente se `s3.bucket.list` não o listar, porque `s3.bucket.create` falha com
código 1 quando o bucket já existe. No fim, o wrapper grava o marcador
`/tmp/bucket-ready` e aguarda o servidor. O SIGTERM é repassado ao `weed` por
`trap`, e `stop_grace_period: 40s` cobre o shutdown gracioso do `weed server`,
que leva cerca de 25s (medido). Se a criação falhar, o wrapper encerra o
servidor e o container sai com erro, e a subida falha. Tudo roda na mesma
imagem, sem ferramenta de terceiros, conforme o Impact.

*Por que não o one-shot `seaweedfs-init` planejado originalmente:* na
implementação (Compose v5.5.1), `docker compose up -d --wait` retornou 1 em
todas as 5 tentativas de subir de novo um ambiente já de pé
(`container ... seaweedfs-init-1 exited (0)`). Isso viola o cenário "Subida com
bucket já existente". Só a primeira subida, em ambiente limpo, passava.
*Alternativas:* AWS CLI ou MinIO Client em uma imagem extra, que seria uma
dependência nova e não foi aprovada; criar o bucket manualmente, o que o
requisito proíbe; ou o one-shot acima, descartado pelo motivo descrito.

### D6. Healthchecks que representam "pronto para uso"
- PostgreSQL: `pg_isready -U skyledger -d skyledger`.
- Redis: `redis-cli ping`.
- SeaweedFS: `/tmp/bucket-ready` existe **e** `/healthz` do S3 responde.

Como o healthcheck do `seaweedfs` já implica o bucket criado, `docker compose
up --wait` retorna com o bucket pronto e depende só de serviços de longa
duração.

### D7. Portas publicadas só em `127.0.0.1`
`127.0.0.1:5432:5432`, `127.0.0.1:6379:6379` e `127.0.0.1:8333:8333`. Por
padrão, o Docker publica em `0.0.0.0`, o que exporia na rede local um Redis
sem senha e credenciais triviais. As portas internas do SeaweedFS (9333, 8080,
8888) não são publicadas.
*Alternativa:* publicar em todas as interfaces, mais simples mas inseguro sem
ganho para desenvolvimento local.

### D8. Volumes nomeados e Redis efêmero
Volumes `postgres-data` e `seaweedfs-data`, prefixados pelo nome do projeto
(`skyledger_postgres-data`, `skyledger_seaweedfs-data`). O Redis sobe com
`--save "" --appendonly no`: como é só cache, não grava snapshots nem AOF e
sempre sobe vazio. Na implementação, verifiquei que `redis:8.10` não declara
`VOLUME`, então nenhum volume anônimo é criado.

### D9. Documentação em `docs/ambiente-local.md`, com ponteiro no `README.md`
Não existe README nem `docs/` ainda. A documentação técnica do projeto fica
em `/docs/` (PT-BR, com tabelas no lugar de prosa). O `README.md` criado
nesta mudança só aponta para ela. `docs/ambiente-local.md` cobre: pré-requisitos
(Docker, Compose v2.23.1+), aviso de uso exclusivo em desenvolvimento, subir,
parar, zerar (`down -v`), tabela de portas e credenciais padrão, variáveis
sobrescrevíveis e a ressalva de que a senha do PostgreSQL só se aplica a
volume novo.

### D10. Teste automatizado por script de smoke test
Ainda não há build Maven onde colocar testes. Os cenários do spec são
verificados por `scripts/verify-local-env.sh`, que sobe o ambiente e exercita
os serviços. PostgreSQL e Redis são testados por `docker compose exec` (psql e
redis-cli das próprias imagens). O S3 é testado com `curl`, usando
`--aws-sigv4` para as requisições assinadas. O script termina com código
diferente de zero no primeiro cenário que falhar. Os cenários de conflito de
porta e de acesso por outra máquina da rede ficam como verificação manual
documentada: automatizá-los exigiria ocupar portas do host ou usar uma segunda
máquina.
O script roda em um projeto Compose isolado (`-p skyledger-verify`), com
volumes próprios que remove ao terminar. Isso é necessário porque os cenários
de reset usam `down -v`, que apagaria os dados de desenvolvimento. O script
exige as portas livres e aborta, sem tocar em nada, se o ambiente de dev
estiver de pé. O `psql` roda com a imagem do PostgreSQL em `--network host`,
contra a porta publicada: dentro do container, a imagem confia em conexões
locais, o que faria o teste de senha incorreta passar indevidamente.
*Alternativa:* Testcontainers em JUnit, que só faz sentido quando o esqueleto
Maven existir e testa as imagens, não o `compose.yaml` em si.

## Security & Observability

- **Segredos e origem:** `POSTGRES_PASSWORD`, `S3_ACCESS_KEY` e
  `S3_SECRET_KEY`. Em desenvolvimento, eles vêm de variáveis de ambiente do
  shell ou de um `.env` local, que o Compose lê automaticamente. Na ausência
  deles, valem os padrões triviais embutidos na interpolação
  `${VAR:-padrão}` do `compose.yaml`. Não há outros ambientes nesta mudança.
  Qualquer ambiente real usará outro mecanismo, a definir quando existir.
- **Chegada à máquina de quem desenvolve:** `.env.example` versionado com as
  três variáveis e seus padrões de desenvolvimento, e `.env` listado no
  `.gitignore` (criado nesta mudança, já que ainda não existe).
- **Exposição:** portas só em loopback (D7), nenhuma identidade S3 anônima
  (D4).
- **Dados pessoais e rastreamento:** não se aplica. Nenhum serviço de
  aplicação é criado.

## Risks / Trade-offs

- [O wrapper `sh` do `seaweedfs` é código próprio (D5)] → ele é curto e
  coberto pelos cenários de bucket do smoke test. Se o `weed server` morrer,
  o `wait` retorna e o container sai, então não fica um container "vivo" sem
  servidor.
- [`docker compose stop`/`down` leva cerca de 25s por causa do shutdown
  gracioso do `weed server`] → aceito. Reduzir o grace period faria o
  Docker usar SIGKILL durante a gravação dos volumes.
- [`configs.content` exige Compose v2.23.1+] → documentar a versão mínima em
  `docs/ambiente-local.md`. Plano B: entrypoint que gera o JSON (D4, alternativa b).
- [Credencial padrão trivial vazar para um ambiente real por cópia do Compose]
  → aviso de uso exclusivo em desenvolvimento no topo do arquivo e em
  `docs/ambiente-local.md`, e portas em loopback.
- [Conflito com PostgreSQL ou Redis já instalados na máquina nas portas 5432
  ou 6379] → as portas são fixas por requisito. A documentação orienta a parar o
  serviço local. Portas configuráveis ficam como evolução, se isso virar dor
  recorrente.
- [Trocar `POSTGRES_PASSWORD` depois do primeiro `up` não tem efeito] →
  comportamento da imagem oficial, coberto por cenário no spec e explicado em
  `docs/ambiente-local.md`.
