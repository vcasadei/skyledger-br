# Ambiente local de desenvolvimento

> **SOMENTE DESENVOLVIMENTO LOCAL. NÃO USE EM PRODUÇÃO.** O `compose.yaml` usa
> credenciais triviais, Redis sem senha e nenhum endurecimento (TLS, backup,
> limites de recursos). Não use este arquivo como base para outros ambientes.

O `compose.yaml` na raiz do repositório sobe os serviços de apoio de que o
Skyledger BR precisa para desenvolver: PostgreSQL, Redis e SeaweedFS (S3). As
portas ficam publicadas apenas em `127.0.0.1`, ou seja, não são acessíveis a
partir de outras máquinas da rede.

## Pré-requisitos

| Item | Versão mínima | Observação |
|------|---------------|------------|
| Docker Engine | recente | Verificado com 29.8 |
| Docker Compose | v2.23.1+ | Necessário para `configs.content` (credenciais S3) |
| Portas livres no host | 5432, 6379, 8333 | Pare PostgreSQL/Redis locais que usem essas portas |

## Variáveis sobrescrevíveis

Nenhum arquivo `.env` é necessário: sem ele, valem os padrões abaixo. Para
sobrescrever, exporte a variável no shell ou copie `.env.example` para `.env`
(o `.env` é ignorado pelo git e nunca deve ser versionado).

| Variável | Padrão | Uso |
|----------|--------|-----|
| `POSTGRES_PASSWORD` | `skyledger` | Senha do usuário `skyledger` no PostgreSQL |
| `S3_ACCESS_KEY` | `skyledger-dev` | Access key da API S3 do SeaweedFS |
| `S3_SECRET_KEY` | `skyledger-dev-secret` | Secret key da API S3 do SeaweedFS |

## Serviços

| Serviço | Imagem | Endereço no host | Credenciais padrão | Persistência |
|---------|--------|------------------|--------------------|--------------|
| PostgreSQL | `postgres:16.15` | `127.0.0.1:5432`, banco `skyledger` | usuário `skyledger`, senha `skyledger` | volume `skyledger_postgres-data` |
| SeaweedFS (S3) | `chrislusf/seaweedfs:4.48` | `http://127.0.0.1:8333` | access key `skyledger-dev`, secret key `skyledger-dev-secret` | volume `skyledger_seaweedfs-data` |
| Redis | `redis:8.10` | `127.0.0.1:6379` | sem senha | nenhuma: é só cache, sem RDB/AOF, sobe sempre vazio |

A versão mínima do SeaweedFS é 4.40, porque versões anteriores têm CVEs
críticos. Só a porta S3 (8333) é publicada. Master (9333), volume (8080) e
filer (8888) ficam internos à rede do Compose.

## Uso

| Ação | Comando | Efeito |
|------|---------|--------|
| Subir | `docker compose up -d --wait` | Retorna só quando os 3 serviços estão `healthy` e o bucket existe |
| Estado | `docker compose ps` | Lista os serviços e o healthcheck |
| Parar | `docker compose down` | Remove os containers e **mantém** os dados (volumes) |
| Zerar | `docker compose down -v` | Remove containers **e volumes**: apaga banco e objetos S3 |
| Logs | `docker compose logs -f <serviço>` | `postgres`, `redis` ou `seaweedfs` |

`docker compose down`/`stop` leva cerca de 25s: o SeaweedFS faz shutdown
gracioso, e o Compose espera até 40s antes de forçar a parada.

### SeaweedFS: bucket e acesso S3

- O bucket `skyledger-raw` é criado automaticamente na subida, sem passo
  manual. Se ele já existir, nada muda: nenhum objeto é alterado.
- A API S3 **exige assinatura** (SigV4). Requisições anônimas ou com secret
  incorreta recebem `403`.
- Use estilo de caminho (`http://127.0.0.1:8333/<bucket>/<chave>`). Qualquer
  região é aceita; use `us-east-1`.

| Parâmetro do cliente S3 | Valor |
|-------------------------|-------|
| Endpoint | `http://127.0.0.1:8333` |
| Região | `us-east-1` |
| Path-style access | habilitado |
| Access key / secret key | `S3_ACCESS_KEY` / `S3_SECRET_KEY` (padrões na tabela acima) |

Exemplo com `curl` (8.3+ para `--aws-sigv4`):

```bash
AUTH=(--aws-sigv4 "aws:amz:us-east-1:s3" --user "skyledger-dev:skyledger-dev-secret")
curl "${AUTH[@]}" http://127.0.0.1:8333/                                             # lista buckets
curl "${AUTH[@]}" -X PUT --data-binary @arquivo.csv http://127.0.0.1:8333/skyledger-raw/arquivo.csv
curl "${AUTH[@]}" http://127.0.0.1:8333/skyledger-raw/arquivo.csv                    # baixa o objeto
```

### PostgreSQL: troca de senha

A imagem oficial só aplica `POSTGRES_PASSWORD` ao **inicializar um volume
vazio**. Depois disso, mudar a variável não tem efeito e a senha anterior
continua valendo.

| Situação | Comando | Resultado |
|----------|---------|-----------|
| Volume novo | `POSTGRES_PASSWORD=outra docker compose up -d --wait` | Senha `outra` aceita, `skyledger` recusada |
| Volume já existente | `POSTGRES_PASSWORD=terceira docker compose up -d --wait` | `terceira` recusada, senha anterior continua valendo |
| Aplicar a nova senha | `docker compose down -v` e depois `up` com a variável | Senha nova aceita (**apaga os dados do banco**) |

## Verificação do ambiente

`scripts/verify-local-env.sh` exercita os cenários do spec
`local-dev-environment`: subida, credenciais, persistência, reset, bucket e
S3 autenticado. Ele sai com código diferente de zero no primeiro cenário que
falhar.

| Item | Detalhe |
|------|---------|
| Isolamento | Roda no projeto Compose `skyledger-verify`, com volumes próprios, removidos no fim. **Não** toca nos seus dados |
| Pré-condição | Portas 5432, 6379 e 8333 livres: rode `docker compose down` antes |
| Duração | ~3 min (várias subidas e paradas) |
| Dependências no host | Docker, `curl` 8.3+. `psql` e `redis-cli` vêm das próprias imagens |

Verificações manuais, que não são automatizadas porque exigem ocupar portas ou
usar outra máquina:

| Cenário | Como verificar |
|---------|----------------|
| Porta do host ocupada | Com outro processo na 6379, `docker compose up -d --wait` falha citando a porta. Os volumes do projeto permanecem em `docker volume ls` |
| Acesso só local | `docker compose ps` mostra as portas como `127.0.0.1:<porta>->...`. A partir do IP de rede do host (`hostname -I`), as conexões em 5432, 6379 e 8333 são recusadas. `ss -ltn` pode não listar nada, porque o Docker publica por regras de firewall |
