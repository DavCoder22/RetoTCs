#!/usr/bin/env bash
#
# stress.sh — Prueba de estrés de resiliencia con k6, visible en Prometheus/Grafana.
#
# Orden del flujo (coherente con las entidades Java del dominio):
#   1) Health checks
#   2) CRUD de entidades: crear cliente -> editar (PUT) -> crear cuenta -> editar
#      (PUT) -> eliminar cuenta -> eliminar cliente   (demuestra C-R-E-D completo)
#   3) Fondos: garantiza saldo en la cuenta origen (DEPOSIT si hace falta)
#   4) k6: N transferencias de 0.01 origen->destino con idempotencyKey única
#   5) Resumen + cómo observar en Grafana
#
# Uso (local o contra AWS):
#   API=http://<ip-aws>:8080 PROM=http://<ip-aws>:9090 ./scripts/stress.sh
#   API=http://localhost:8080 PROM=http://localhost:9090 ./scripts/stress.sh
#
# Variables:
#   API          URL base de la API                 (default http://localhost:8080)
#   PROM         URL de Prometheus para remote write (vacío = sin envío, solo k6)
#   AMOUNT       monto por transferencia            (default 0.01)
#   TOTAL        transferencias                     (default 10000)
#   VUS          concurrencia k6                    (default 50)
#   TEST_ID      etiqueta en Prometheus/Grafana     (default stress-<timestamp>)
#   DEBIT_ACCOUNT, CREDIT_ACCOUNT  cuentas origen/destino (seed a0000...0001/0002)
#   K6_CMD       override de comando k6
#
set -euo pipefail

API="${API:-http://localhost:8080}"
PROM="${PROM:-}"
AMOUNT="${AMOUNT:-0.01}"
TOTAL="${TOTAL:-10000}"
VUS="${VUS:-50}"
TEST_ID="${TEST_ID:-stress-$(date +%Y%m%d-%H%M%S)}"
DEBIT="${DEBIT_ACCOUNT:-a0000000-0000-0000-0000-000000000001}"
CREDIT="${CREDIT_ACCOUNT:-a0000000-0000-0000-0000-000000000002}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
EVR="$ROOT/scripts/evidencia/stress-${STAMP}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$EVR"

GREEN='\033[0;32m'; BLUE='\033[1;34m'; NC='\033[0m'
step() { echo; printf "${BLUE}=== %s ===${NC}\n" "$1"; }
ok()   { printf "${GREEN}[OK]${NC} %s\n" "$1"; }

BODY="$TMP/body"; HTTP=000; TIME=0.0
call() {
    local method="$1" path="$2" req="${3:-}" meta="$TMP/meta" raw
    : > "$BODY"
    local args=(curl -sS --max-time 30 -o "$BODY" -w '%{http_code}|%{time_total}'
        -X "$method" "$API$path" -H 'Content-Type: application/json')
    [ -n "$req" ] && args+=(-d @"$req")
    "${args[@]}" > "$meta" 2>&1 || true
    raw="$(cat "$meta")"; HTTP="${raw%|*}"; TIME="${raw##*|}"
}
jget() { python3 -c "import json,sys; print(json.load(open('$BODY')).get('$1',''))"; }

echo "SmartBancs — prueba de estrés (k6)"
echo "  API=$API  PROM=${PROM:-<sin remote write>}  AMOUNT=$AMOUNT  TOTAL=$TOTAL  VUS=$VUS"
echo "  TEST_ID=$TEST_ID  DEBIT=$DEBIT  CREDIT=$CREDIT"
echo "  Evidencia: $EVR"

# ---------------------------------------------------------------------------
step "01) Health checks"
call GET /actuator/health
echo "HTTP $HTTP | ${TIME}s"
python3 -m json.tool "$BODY" > "$EVR/01-health.txt" 2>/dev/null || cat "$BODY" > "$EVR/01-health.txt"
ok "$EVR/01-health.txt"
[ "$HTTP" = "200" ] || { echo "API no disponible ($HTTP)" >&2; exit 1; }

# ---------------------------------------------------------------------------
step "02) CRUD de entidades — crear, editar (PUT) y eliminar"
{
    echo "## Crear cliente temporal (para ejercer el CRUD sin tocar datos reales)"
    cli="$TMP/cli.json"
    cat > "$cli" <<EOF
{"fullName": "Cliente STRESS ${STAMP}", "email": "stress.${STAMP}@example.com", "segment": "RETAIL"}
EOF
    call POST /customers "$cli"
    CLIENTE_ID="$(jget id)"
    echo "HTTP $HTTP | ${TIME}s  -> id=$CLIENTE_ID"
    python3 -m json.tool "$BODY"

    echo
    echo "## PUT /customers/{id} — edición del cliente"
    cat > "$cli" <<EOF
{"fullName": "Cliente STRESS EDITADO ${STAMP}", "email": "stress.${STAMP}@example.com", "segment": "PREMIUM"}
EOF
    call PUT /customers/$CLIENTE_ID "$cli"
    echo "HTTP $HTTP | ${TIME}s  -> segmento reflejado: $(jget segment)"
    python3 -m json.tool "$BODY"

    echo
    echo "## Crear cuenta temporal"
    acc="$TMP/acc.json"
    cat > "$acc" <<EOF
{"customerId": "$CLIENTE_ID", "accountNumber": "4652$(date +%s%N)", "currency": "PEN",
 "balance": 0.00, "dailyTransferLimit": 500.00, "status": "ACTIVE"}
EOF
    call POST /accounts "$acc"
    CUENTA_ID="$(jget id)"
    echo "HTTP $HTTP | ${TIME}s  -> id=$CUENTA_ID"
    python3 -m json.tool "$BODY"

    echo
    echo "## PUT /accounts/{id} — edición de la cuenta (límite diario)"
    printf '{"dailyTransferLimit": 2000.00}' > "$acc"
    call PUT /accounts/$CUENTA_ID "$acc"
    echo "HTTP $HTTP | ${TIME}s  -> límite: $(jget dailyTransferLimit)"
    python3 -m json.tool "$BODY"

    echo
    echo "## DELETE /accounts/{id} — cuenta sin saldo ni movimientos"
    call DELETE /accounts/$CUENTA_ID
    echo "HTTP $HTTP | ${TIME}s"

    echo
    echo "## DELETE /customers/{id} — cliente ya sin cuentas"
    call DELETE /customers/$CLIENTE_ID
    echo "HTTP $HTTP | ${TIME}s"
} > "$EVR/02-crud.txt"
ok "$EVR/02-crud.txt"

# ---------------------------------------------------------------------------
step "03) Fondos — saldo suficiente en la cuenta origen ($DEBIT)"
{
    needed="$(python3 -c "print($AMOUNT * $TOTAL)")"
    call GET /accounts/$DEBIT
    echo "GET /accounts/$DEBIT -> HTTP $HTTP | ${TIME}s"
    saldo="$(jget balance)"
    echo "saldo_actual=$saldo  necesario=$needed"
    echo
    if python3 -c "exit(0 if float('$saldo') >= $needed else 1)"; then
        echo "Fondos suficientes: no se deposita."
    else
        dep="$TMP/dep.json"
        cat > "$dep" <<EOF
{"type": "DEPOSIT", "amount": $needed, "currency": "PEN",
 "creditAccountId": "$DEBIT", "idempotencyKey": "stress-${STAMP}-fondo",
 "reference": "Fondo para prueba de estrés ${TEST_ID}"}
EOF
        call POST /transactions "$dep"
        echo "DEPOSIT de $needed PEN -> HTTP $HTTP | ${TIME}s"
        python3 -m json.tool "$BODY"
    fi
} > "$EVR/03-fondos.txt"
ok "$EVR/03-fondos.txt"

# ---------------------------------------------------------------------------
step "04) k6 — ${TOTAL} transferencias de ${AMOUNT} PEN (${VUS} VUs)"
K6_CMD="${K6_CMD:-}"
if [ -z "$K6_CMD" ]; then
    if command -v k6 >/dev/null 2>&1; then
        K6_CMD=(k6 run)
        SCRIPT="$ROOT/scripts/k6/transfers.js"
    else
        K6_CMD=(docker run --rm --network host -v "$ROOT/scripts/k6:/scripts:ro" grafana/k6 run)
        SCRIPT="/scripts/transfers.js"
    fi
else
    SCRIPT="${K6_SCRIPT:-$ROOT/scripts/k6/transfers.js}"
fi
K6_ARGS=(--tag testid="$TEST_ID")
K6_PROM_URL=""
if [ -n "$PROM" ]; then
    # k6 v2: output experimental-prometheus-rw + env K6_PROMETHEUS_RW_SERVER_URL.
    # (k6 v1 usaba --out prometheus-remote-write <url>) -> override con K6_PROM_OUT.
    K6_PROM_OUT="${K6_PROM_OUT:-experimental-prometheus-rw}"
    K6_ARGS+=(--out "$K6_PROM_OUT")
    K6_PROM_URL="${PROM}/api/v1/write"
fi
echo "Comando: ${K6_CMD[*]} ${K6_ARGS[*]} --env API_URL=$API --env TEST_ID=$TEST_ID $SCRIPT"
"${K6_CMD[@]}" "${K6_ARGS[@]}" \
    --env API_URL="$API" \
    --env DEBIT_ACCOUNT="$DEBIT" \
    --env CREDIT_ACCOUNT="$CREDIT" \
    --env AMOUNT="$AMOUNT" \
    --env TOTAL_ITERS="$TOTAL" \
    --env VUS="$VUS" \
    --env TEST_ID="$TEST_ID" \
    --env K6_PROMETHEUS_RW_SERVER_URL="$K6_PROM_URL" \
    "$SCRIPT" 2>&1 | tee "$EVR/04-k6.txt"
ok "$EVR/04-k6.txt"

# ---------------------------------------------------------------------------
step "05) Resumen y observación en Grafana"
{
    echo "== Data de la corrida =="
    echo "testid : $TEST_ID"
    echo "API    : $API"
    echo "PROM   : ${PROM:-<sin envío>}"
    echo "total  : ${TOTAL} x ${AMOUNT} PEN (${VUS} VUs)"
    echo
    echo "== Observar en Grafana =="
    echo "1) Grafana (local o AWS :3333) -> Explore -> datasource Prometheus"
    echo "   Carga de peticiones :   sum(rate(k6_http_reqs_total{testid=\"$TEST_ID\"}[1m]))"
    echo "   Latencia p99        :   k6_http_req_duration_p99{testid=\"$TEST_ID\"}"
    echo "   Tasa de error       :   k6_http_req_failed_rate{testid=\"$TEST_ID\"}"
    echo "   Usuarios concurrentes: k6_vus{testid=\"$TEST_ID\"}"
    echo "   Respuesta del lado servidor:"
    echo "     increase(http_server_requests_seconds_count{uri=\"/transactions\"}[1m])"
    echo "     smartbancs_transaction_amount_count{type=\"TRANSFER\"}"
    echo "   Base de datos        :   pg_stat_database_xact_commit"
    echo
    echo "2) Dashboard oficial k6 (import en Grafana, ID 13039) filtra por"
    echo "   testid=\"$TEST_ID\" para ver SOLO esta corrida."
} > "$EVR/05-observar.txt"
cat "$EVR/05-observar.txt"
echo
echo "====================================================="
echo "Prueba de estrés completada. Evidencia en:"
echo "  $EVR"
echo "====================================================="