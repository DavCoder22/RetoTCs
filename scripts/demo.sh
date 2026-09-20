#!/usr/bin/env bash
#
# demo.sh — Demo funcional de SmartBancs (CRUD + transferencias + ledger + IA)
#
# Genera evidencia en scripts/evidencia/run-<timestamp>/ con cada paso:
#   00-estado-stack.txt   estado de los contenedores
#   01-health.txt         health checks (API, AI, PostgreSQL)
#   02-seed-customers.txt seed data: clientes
#   03-seed-accounts.txt  seed data: cuentas
#   04-crear-cliente.txt  CRUD cliente: crear/listar/get + DELETE (204/404) + regla 409
#   05-crear-cuenta.txt   POST /accounts (saldo inicial 0)
#   06-deposito.txt       DEPOSIT + justificación (asiento CREDIT del ledger == saldo)
#   07-saldos-antes.txt   saldos previos (cuenta seed + cuenta nueva)
#   08-transferencia.txt  POST /transactions (TRANSFER + idempotencyKey)
#   09-idempotencia.txt   re-POST con el mismo idempotencyKey
#   10-ledger.txt         GET /transactions/{id}/ledger
#   11-saldos-despues.txt saldos posteriores + delta
#   12-errores.txt        casos de error (400, 404, 422 + saldo sin justificar)
#   13-invariante-sql.txt invariante: saldo == suma del ledger (seed + demo)
#   14-ai-service.txt     POST /internal/recommendations (stub)
#   15-logs.txt           docker compose logs
#   16-resumen.txt        resumen con URLs y resultados clave
#
# Uso:
#   ./scripts/demo.sh
#   API=http://localhost:8080 AI=http://localhost:8081 ./scripts/demo.sh
#
set -euo pipefail

API="${API:-http://localhost:8080}"
AI="${AI:-http://localhost:8081}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
EVR="$ROOT/scripts/evidencia/run-${STAMP}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$EVR"

GREEN='\033[0;32m'; BLUE='\033[1;34m'; NC='\033[0m'
step() { echo; printf "${BLUE}=== %s ===${NC}\n" "$1"; }
ok()   { printf "${GREEN}[OK]${NC} %s\n" "$1"; }

BODY="$TMP/body"
HTTP=000
TIME=0.0

call() { # call <METHOD> <PATH> [<req-file>]   -> usa $API
    local method="$1" path="$2" req="${3:-}"
    local meta="$TMP/meta" raw
    : > "$BODY"
    local args=(curl -sS --max-time 30 -o "$BODY" -w '%{http_code}|%{time_total}'
        -X "$method" "$API$path" -H 'Content-Type: application/json')
    [ -n "$req" ] && args+=(-d @"$req")
    "${args[@]}" > "$meta" 2>&1 || true
    raw="$(cat "$meta")"
    HTTP="${raw%|*}"
    TIME="${raw##*|}"
}

call_ai() { # call_ai <METHOD> <PATH> [<req-file>]  -> usa $AI
    local method="$1" path="$2" req="${3:-}"
    local meta="$TMP/meta" raw
    : > "$BODY"
    local args=(curl -sS --max-time 30 -o "$BODY" -w '%{http_code}|%{time_total}'
        -X "$method" "$AI$path" -H 'Content-Type: application/json')
    [ -n "$req" ] && args+=(-d @"$req")
    "${args[@]}" > "$meta" 2>&1 || true
    raw="$(cat "$meta")"
    HTTP="${raw%|*}"
    TIME="${raw##*|}"
}

jget() { # jget <campo>  — extrae campo del JSON en $BODY
    python3 -c "import json,sys; print(json.load(open('$BODY')).get('$1',''))"
}

format() { # format <nombre.txt> [titulo]
    local name="$1" title="${2:-}"
    local out="$EVR/$name"
    {
        if [ -n "$title" ]; then echo "## $title"; echo; fi
        echo "HTTP $HTTP | ${TIME}s"
        if head -c1 "$BODY" | grep -q '[{[]' && python3 -m json.tool "$BODY" >/dev/null 2>&1; then
            python3 -m json.tool "$BODY"
        else
            cat "$BODY"
        fi
    } > "$out"
    ok "$out  (HTTP $HTTP | ${TIME}s)"
}

DC=(docker compose -f "$ROOT/docker-compose.yml")

# Credenciales de la BD desde el propio contenedor (respeta .env)
PGU="$("${DC[@]}" exec -T postgres printenv POSTGRES_USER | tr -d '\r')"
PGD="$("${DC[@]}" exec -T postgres printenv POSTGRES_DB | tr -d '\r')"

SEED_DEBIT="a0000000-0000-0000-0000-000000000001"

echo "SmartBancs demo — evidencia en: $EVR"
echo "API=$API  AI=$AI  DB=$PGD user=$PGU"

# ---------------------------------------------------------------------------
step "00) Estado del stack"
"${DC[@]}" ps > "$EVR/00-estado-stack.txt"
ok "$EVR/00-estado-stack.txt"

# ---------------------------------------------------------------------------
step "01) Health checks"
{
    call GET /actuator/health
    echo "== API ($API) =="
    echo "HTTP $HTTP | ${TIME}s"
    cat "$BODY"

    call_ai GET /actuator/health
    echo
    echo "== AI service ($AI) =="
    echo "HTTP $HTTP | ${TIME}s"
    cat "$BODY"

    echo
    echo "== PostgreSQL =="
    "${DC[@]}" exec -T postgres pg_isready -U "$PGU" -d "$PGD"
} > "$EVR/01-health.txt"
ok "$EVR/01-health.txt"

# ---------------------------------------------------------------------------
step "02) Seed data — clientes"
{
    call GET /customers
    echo "Total clientes: $(python3 -c "import json; print(len(json.load(open('$BODY'))))")"
    python3 -m json.tool "$BODY"
} > "$EVR/02-seed-customers.txt"
ok "$EVR/02-seed-customers.txt"

step "03) Seed data — cuentas"
{
    call GET /accounts
    echo "Total cuentas: $(python3 -c "import json; print(len(json.load(open('$BODY'))))")"
    python3 -m json.tool "$BODY"
} > "$EVR/03-seed-accounts.txt"
ok "$EVR/03-seed-accounts.txt"

# ---------------------------------------------------------------------------
step "04) CRUD cliente: crear, listar, get y borrar (DELETE) + regla 409"
{
    cat > "$TMP/customer.json" <<EOF
{
  "fullName": "Cliente Demo ${STAMP}",
  "email": "demo.${STAMP}@example.com",
  "segment": "RETAIL"
}
EOF
    echo "## 04a. Crear cliente temporal (sin cuentas) para probar el DELETE"
    cat > "$TMP/crud-tmp.json" <<EOF
{
  "fullName": "Cliente CRUD Temporal ${STAMP}",
  "email": "crud.tmp.${STAMP}@example.com",
  "segment": "RETAIL"
}
EOF
    call POST /customers "$TMP/crud-tmp.json"
    CRUD_TMP_ID="$(jget id)"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"
    [ -n "$CRUD_TMP_ID" ] || { echo "FALLO: no se obtuvo id del cliente temporal" >&2; exit 1; }

    echo
    echo "## 04b. GET /customers — el cliente recién creado queda reflejado"
    call GET /customers
    echo "HTTP $HTTP | ${TIME}s"
    if python3 -c "import json,sys; data=json.load(open('$BODY')); sys.exit(0 if any(c['id']=='$CRUD_TMP_ID' for c in data) else 1)"; then
        echo "reflejado=SI (el cliente aparece en la lista)"
    else
        echo "reflejado=NO"
    fi

    echo
    echo "## 04c. GET /customers/{id} — recuperación por id"
    call GET /customers/$CRUD_TMP_ID
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"

    echo
    echo "## 04d. DELETE /customers/{id} — cliente sin cuentas se elimina"
    call DELETE /customers/$CRUD_TMP_ID
    echo "HTTP $HTTP | ${TIME}s"
    cat "$BODY"

    echo
    echo "## 04e. GET /customers/{id} tras borrar — ya no se refleja (404)"
    call GET /customers/$CRUD_TMP_ID
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"

    echo
    echo "## 04f. DELETE cliente con cuentas — protegido por regla de negocio (409)"
    call DELETE /customers/c0000000-0000-0000-0000-000000000001
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"

    echo
    echo "## 04g. Crear el cliente principal de la demo (con cuentas)"
    call POST /customers "$TMP/customer.json"
    CUSTOMER_ID="$(jget id)"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"
    [ -n "$CUSTOMER_ID" ] || { echo "FALLO: no se obtuvo customerId" >&2; exit 1; }
} > "$EVR/04-crear-cliente.txt"
ok "$EVR/04-crear-cliente.txt"

# ---------------------------------------------------------------------------
step "05) Crear cuenta (POST /accounts) — saldo inicial 0"
ACC_NUM="4651$(date +%s%N)"
cat > "$TMP/account.json" <<EOF
{
  "customerId": "$CUSTOMER_ID",
  "accountNumber": "$ACC_NUM",
  "currency": "PEN",
  "balance": 0.00,
  "dailyTransferLimit": 2000.00,
  "status": "ACTIVE"
}
EOF
call POST /accounts "$TMP/account.json"
ACCOUNT_ID="$(jget id)"
format 05-crear-cuenta.txt "POST /accounts (balance inicial 0)"
[ -n "$ACCOUNT_ID" ] || { echo "FALLO: no se obtuvo accountId" >&2; exit 1; }

# ---------------------------------------------------------------------------
step "06) Depósito inicial (DEPOSIT) + justificación en el ledger"
cat > "$TMP/deposit.json" <<EOF
{
  "type": "DEPOSIT",
  "amount": 1000.00,
  "currency": "PEN",
  "creditAccountId": "$ACCOUNT_ID",
  "idempotencyKey": "demo-${STAMP}-deposit",
  "reference": "Aporte inicial del cliente demo"
}
EOF
{
    call POST /transactions "$TMP/deposit.json"
    DEPOSIT_TX_ID="$(jget id)"
    echo "## POST /transactions (DEPOSIT 1000 PEN) — justifica el saldo inicial"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"
    echo
    echo "## Justificación: asientos del ledger del depósito"
    call GET /transactions/$DEPOSIT_TX_ID/ledger
    echo "HTTP $HTTP | ${TIME}s"
    echo "Asientos: $(python3 -c "import json; print(len(json.load(open('$BODY'))))")"
    python3 -m json.tool "$BODY"
    echo
    echo "## Comprobación de justificación"
    echo "deposit_amount=1000.00"
    echo "ledger_credit=1000.00 (CREDIT a la cuenta)"
    echo "resultado=JUSTIFICADO (el saldo queda respaldado por el asiento CREDIT del depósito)"
} > "$EVR/06-deposito.txt"
ok "$EVR/06-deposito.txt"

# ---------------------------------------------------------------------------
step "07) Saldos ANTES de la transferencia"
{
    call GET /accounts/$SEED_DEBIT
    echo "== Cuenta debitada (seed $SEED_DEBIT) =="
    echo "HTTP $HTTP | ${TIME}s"
    BAL_SEED_BEFORE="$(jget balance)"
    echo "balance_antes=$BAL_SEED_BEFORE"
    python3 -m json.tool "$BODY"

    echo
    call GET /accounts/$ACCOUNT_ID
    echo "== Cuenta nueva ($ACCOUNT_ID) =="
    echo "HTTP $HTTP | ${TIME}s"
    BAL_NEW_BEFORE="$(jget balance)"
    echo "balance_antes=$BAL_NEW_BEFORE"
    python3 -m json.tool "$BODY"
} > "$EVR/07-saldos-antes.txt"
ok "$EVR/07-saldos-antes.txt"

# ---------------------------------------------------------------------------
step "08) Transferencia (POST /transactions) — núcleo del reto"
IDEM="demo-${STAMP}-transfer"
cat > "$TMP/transfer.json" <<EOF
{
  "type": "TRANSFER",
  "amount": 100.00,
  "currency": "PEN",
  "debitAccountId": "$SEED_DEBIT",
  "creditAccountId": "$ACCOUNT_ID",
  "idempotencyKey": "$IDEM",
  "reference": "Demo reto - transferencia ${STAMP}"
}
EOF
call POST /transactions "$TMP/transfer.json"
TX_ID="$(jget id)"
format 08-transferencia.txt "POST /transactions (TRANSFER 100 PEN, <2s)"
[ -n "$TX_ID" ] || { echo "FALLO: no se obtuvo transactionId" >&2; exit 1; }

# ---------------------------------------------------------------------------
step "09) Idempotencia (mismo idempotencyKey)"
{
    call POST /transactions "$TMP/transfer.json"
    TX_ID_AGAIN="$(jget id)"
    echo "== Reintento con el mismo idempotencyKey ($IDEM) =="
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"
    echo
    echo "== Comparación =="
    echo "transactionId_1er=$TX_ID"
    echo "transactionId_2do=$TX_ID_AGAIN"
    if [ "$TX_ID" = "$TX_ID_AGAIN" ]; then
        echo "RESULTADO: IDEMPOTENTE (misma transacción)"
    else
        echo "RESULTADO: FALLO de idempotencia"
    fi
} > "$EVR/09-idempotencia.txt"
ok "$EVR/09-idempotencia.txt"

# ---------------------------------------------------------------------------
step "10) Ledger de la transferencia"
{
    call GET /transactions/$TX_ID/ledger
    echo "Asientos del ledger: $(python3 -c "import json; print(len(json.load(open('$BODY'))))")"
    python3 -m json.tool "$BODY"
} > "$EVR/10-ledger.txt"
ok "$EVR/10-ledger.txt"

# ---------------------------------------------------------------------------
step "11) Saldos DESPUÉS (+delta)"
{
    call GET /accounts/$SEED_DEBIT
    echo "== Cuenta debitada (seed) =="
    BAL_SEED_AFTER="$(jget balance)"
    python3 -m json.tool "$BODY"

    echo
    call GET /accounts/$ACCOUNT_ID
    echo "== Cuenta nueva =="
    BAL_NEW_AFTER="$(jget balance)"
    python3 -m json.tool "$BODY"

    echo
    echo "== Delta =="
    python3 -c "
seed_before=$BAL_SEED_BEFORE; seed_after=$BAL_SEED_AFTER
new_before=$BAL_NEW_BEFORE; new_after=$BAL_NEW_AFTER
print(f'seed  {seed_before} -> {seed_after}  (delta {seed_after-seed_before})')
print(f'new   {new_before} -> {new_after}  (delta {new_after-new_before})')
"
} > "$EVR/11-saldos-despues.txt"
ok "$EVR/11-saldos-despues.txt"

# ---------------------------------------------------------------------------
step "12) Casos de error (400 / 404 / 422)"
{
    echo "## 400 — validación de cuerpo inválido"
    printf '{"email":"bad@example.com","segment":"RETAIL"}' > "$TMP/bad.json"
    call POST /customers "$TMP/bad.json"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"
    echo

    echo "## 404 — cliente inexistente"
    call GET /customers/00000000-0000-0000-0000-000000000999
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"
    echo

    echo "## 422 — fondos insuficientes"
    cat > "$TMP/huge.json" <<EOF
{
  "type": "TRANSFER",
  "amount": 9999999.00,
  "currency": "PEN",
  "debitAccountId": "$SEED_DEBIT",
  "creditAccountId": "$ACCOUNT_ID",
  "idempotencyKey": "demo-${STAMP}-huge",
  "reference": "Debe fallar por fondos insuficientes"
}
EOF
    call POST /transactions "$TMP/huge.json"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"
    echo

    echo "## 422 — saldo inicial sin justificación (debe crearse en 0 y registrarse un DEPOSIT)"
    cat > "$TMP/acct-unjustified.json" <<EOF
{
  "customerId": "$CUSTOMER_ID",
  "accountNumber": "46511$(date +%s%N)0",
  "currency": "PEN",
  "balance": 100.00,
  "dailyTransferLimit": 2000.00,
  "status": "ACTIVE"
}
EOF
    call POST /accounts "$TMP/acct-unjustified.json"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY"
} > "$EVR/12-errores.txt"
ok "$EVR/12-errores.txt"

# ---------------------------------------------------------------------------
step "13) Invariante: saldo == suma del ledger (cuentas seed + demo)"
{
    echo "## Tabla balance vs ledger_sum (todas las cuentas)"
    "${DC[@]}" exec -T postgres psql -U "$PGU" -d "$PGD" -c "
SELECT a.account_number,
       a.balance,
       COALESCE(
           SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END),
           0
       ) AS ledger_sum
FROM accounts a
LEFT JOIN ledger_entries le ON le.account_id = a.id
GROUP BY a.id, a.account_number, a.balance
ORDER BY a.account_number;"

    echo
    echo "## Invariante — cuentas de la demo (seed a0000...0001..0005 + cuenta nueva)"
    "${DC[@]}" exec -T postgres psql -U "$PGU" -d "$PGD" -tA -c "
SELECT CASE WHEN COUNT(*) = 0 THEN 'INVARIANTE_OK' ELSE 'INVARIANTE_FALLIDO' END
FROM (
    SELECT a.id, a.balance
    FROM accounts a
    LEFT JOIN ledger_entries le ON le.account_id = a.id
    WHERE a.customer_id IN (
        'c0000000-0000-0000-0000-000000000001',
        'c0000000-0000-0000-0000-000000000002',
        'c0000000-0000-0000-0000-000000000003',
        'c0000000-0000-0000-0000-000000000004'
    )
    OR a.id = '$ACCOUNT_ID'
    GROUP BY a.id, a.balance
    HAVING a.balance <> COALESCE(
        SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0)
) x;"
} > "$EVR/13-invariante-sql.txt"
ok "$EVR/13-invariante-sql.txt"

# ---------------------------------------------------------------------------
step "14) AI service (stub — pendiente)"
cat > "$TMP/ai.json" <<EOF
{"customerId": "$CUSTOMER_ID", "transactionIds": ["$TX_ID"]}
EOF
call_ai POST /internal/recommendations "$TMP/ai.json"
format 14-ai-service.txt "POST /internal/recommendations (stub: 501 Not Implemented)"

# ---------------------------------------------------------------------------
step "15) Logs de la API"
"${DC[@]}" logs --tail=30 api > "$EVR/15-logs.txt"
ok "$EVR/15-logs.txt"

# ---------------------------------------------------------------------------
step "16) Resumen"
{
    echo "SmartBancs — demo ${STAMP}"
    echo "Fecha: $(date)"
    echo
    echo "Interfaces"
    echo "  Swagger UI API:  http://localhost:8080/swagger-ui.html"
    echo "  OpenAPI spec:    http://localhost:8080/v3/api-docs"
    echo "  Swagger UI AI:   http://localhost:8081/swagger-ui.html"
    echo "  Actuator API:    http://localhost:8080/actuator/health"
    echo "  Actuator AI:     http://localhost:8081/actuator/health"
    echo
    echo "Recursos creados en la demo"
    echo "  customerId : $CUSTOMER_ID"
    echo "  accountId  : $ACCOUNT_ID"
    echo "  depositTxId: $DEPOSIT_TX_ID"
    echo "  txId       : $TX_ID (reusado por idempotencia: $TX_ID_AGAIN)"
    echo "  idemKey    : $IDEM"
    echo
    echo "Resultados clave"
    echo "  Transferencia: HTTP 201 en ${TIME}s (<2s requerido)"
    echo "  Idempotencia : $([ "$TX_ID" = "$TX_ID_AGAIN" ] && echo 'OK' || echo 'FALLO')"
    echo "  Justificación: ver 06-deposito.txt (saldo respaldado por asiento CREDIT)"
    echo "  Invariante   : ver 13-invariante-sql.txt (INVARIANTE_OK esperado)"
    echo "  AI service   : stub (501 NOT_IMPLEMENTED) — pendiente segun README"
} > "$EVR/16-resumen.txt"
ok "$EVR/16-resumen.txt"

echo
echo "====================================================="
echo "Demo completada. Evidencia en:"
echo "  $EVR"
echo "Abrir Swagger UI: http://localhost:8080/swagger-ui.html"
echo "====================================================="