#!/usr/bin/env bash
# Smoke test do ambiente local (compose.yaml): exercita os cenários de
# openspec/specs/local-dev-environment. Roda em um projeto Compose isolado
# (skyledger-verify) para nunca apagar os volumes de desenvolvimento, e remove
# os próprios volumes ao terminar. Sai com código != 0 no primeiro cenário que
# falhar. Requer as portas 5432, 6379 e 8333 livres (pare o ambiente de dev).
set -euo pipefail

cd "$(dirname "$0")/.."

PROJECT=skyledger-verify
PG_IMAGE=postgres:16.15
REDIS_IMAGE=redis:8.10
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-skyledger}"
S3_ACCESS_KEY="${S3_ACCESS_KEY:-skyledger-dev}"
S3_SECRET_KEY="${S3_SECRET_KEY:-skyledger-dev-secret}"
S3_ENDPOINT=http://127.0.0.1:8333
BUCKET=skyledger-raw

compose() { docker compose -p "$PROJECT" "$@"; }
up() { compose up -d --wait --quiet-pull >/dev/null 2>&1 || { compose ps -a; compose logs --tail 50; fail "docker compose up --wait falhou"; }; }

pass() { printf '  [ok]    %s\n' "$1"; }
fail() { printf '  [FALHA] %s\n' "$1" >&2; exit 1; }
scenario() { printf '\n* %s\n' "$1"; }

# psql pela porta publicada no host: dentro do container o PostgreSQL confia em
# conexões locais, o que mascararia o teste de senha.
psql_host() {
  local password="$1"; shift
  docker run --rm --network host -e PGPASSWORD="$password" "$PG_IMAGE" \
    psql -h 127.0.0.1 -p 5432 -U skyledger -d skyledger -v ON_ERROR_STOP=1 -tAc "$@"
}

redis_host() {
  docker run --rm --network host "$REDIS_IMAGE" redis-cli -h 127.0.0.1 -p 6379 "$@"
}

# Requisição S3 assinada (SigV4) com as credenciais informadas; imprime o corpo
# e, na última linha, o código HTTP.
s3() {
  local user="$1" method="$2" path="$3"; shift 3
  curl -sS -X "$method" --aws-sigv4 "aws:amz:us-east-1:s3" --user "$user" \
    -w '\n%{http_code}' "$@" "$S3_ENDPOINT$path"
}
s3_dev() { s3 "$S3_ACCESS_KEY:$S3_SECRET_KEY" "$@"; }
http_code() { tail -n1 <<<"$1"; }

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

cleanup() { compose down -v --remove-orphans >/dev/null 2>&1 || true; }

for port in 5432 6379 8333; do
  if port_in_use "$port"; then
    echo "Porta $port em uso. Pare o ambiente de desenvolvimento (docker compose down) antes de rodar este script." >&2
    exit 2
  fi
done

trap cleanup EXIT
cleanup

scenario "Primeira subida em máquina limpa"
up
pass "docker compose up --wait terminou com sucesso"

scenario "S3: bucket criado na primeira subida"
listing="$(s3_dev GET "/$BUCKET?list-type=2")"
[[ "$(http_code "$listing")" == "200" ]] || fail "bucket $BUCKET não acessível: $listing"
[[ "$listing" != *"<Contents>"* ]] || fail "bucket $BUCKET não está vazio"
pass "bucket $BUCKET existe e está vazio"

scenario "PostgreSQL: conexão com as credenciais padrão"
version="$(psql_host "$POSTGRES_PASSWORD" 'SELECT version()')"
[[ "$version" == "PostgreSQL 16."* ]] || fail "versão inesperada: $version"
pass "conexão aceita, $version"

scenario "PostgreSQL: senha incorreta"
if psql_host "senha-incorreta-$RANDOM" 'SELECT 1' >/dev/null 2>&1; then
  fail "conexão com senha incorreta foi aceita"
fi
pass "conexão recusada"

scenario "PostgreSQL: dados sobrevivem ao reinício"
psql_host "$POSTGRES_PASSWORD" 'CREATE TABLE smoke (id int); INSERT INTO smoke VALUES (42)' >/dev/null
compose down >/dev/null 2>&1
up
[[ "$(psql_host "$POSTGRES_PASSWORD" 'SELECT id FROM smoke')" == "42" ]] || fail "tabela smoke perdida após down/up"
pass "tabela e linha presentes após down + up"

scenario "PostgreSQL: reset explícito do ambiente"
compose down -v >/dev/null 2>&1
up
[[ "$(psql_host "$POSTGRES_PASSWORD" "SELECT to_regclass('public.smoke') IS NULL")" == "t" ]] || fail "tabela smoke sobreviveu ao down -v"
pass "banco vazio após down -v + up"

scenario "Redis: comando sem credenciais"
[[ "$(redis_host PING)" == "PONG" ]] || fail "PING sem senha não respondeu PONG"
redis_version="$(redis_host INFO server | tr -d '\r' | sed -n 's/^redis_version://p')"
[[ "$redis_version" == 8.* ]] || fail "versão inesperada do Redis: $redis_version"
pass "PONG sem senha, redis_version $redis_version"

scenario "S3: acesso com as credenciais de desenvolvimento"
buckets="$(s3_dev GET /)"
[[ "$(http_code "$buckets")" == "200" ]] || fail "listagem de buckets recusada: $buckets"
[[ "$buckets" == *"<Name>$BUCKET</Name>"* ]] || fail "listagem não inclui $BUCKET"
pass "listagem aceita e inclui $BUCKET"

scenario "S3: credenciais inválidas ou ausentes"
anon="$(curl -sS -o /dev/null -w '%{http_code}' "$S3_ENDPOINT/")"
[[ "$anon" == "403" ]] || fail "requisição anônima retornou $anon"
bad="$(http_code "$(s3 "$S3_ACCESS_KEY:secret-incorreta" GET /)")"
[[ "$bad" == "403" ]] || fail "secret incorreta retornou $bad"
pass "anônima e secret incorreta recusadas com 403"

scenario "S3: objetos sobrevivem ao reinício"
payload="smoke-$RANDOM-$(date +%s)"
[[ "$(http_code "$(s3_dev PUT "/$BUCKET/smoke.txt" --data-binary "$payload")")" == "200" ]] || fail "PUT do objeto falhou"
compose down >/dev/null 2>&1
up
got="$(s3_dev GET "/$BUCKET/smoke.txt")"
[[ "$(http_code "$got")" == "200" && "$(head -n -1 <<<"$got")" == "$payload" ]] || fail "objeto perdido ou alterado após down/up: $got"
pass "objeto com o mesmo conteúdo após down + up"

scenario "S3: subida com bucket já existente"
up
got="$(s3_dev GET "/$BUCKET/smoke.txt")"
[[ "$(http_code "$got")" == "200" && "$(head -n -1 <<<"$got")" == "$payload" ]] || fail "objeto perdido após nova subida: $got"
pass "nova subida terminou com sucesso e o objeto permanece"

printf '\nTodos os cenários passaram.\n'
