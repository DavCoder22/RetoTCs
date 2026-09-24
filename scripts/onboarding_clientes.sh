#!/usr/bin/env bash
#
# onboarding_clientes.sh — Alta masiva de clientes (onboarding) con casos de error.
#
# Registra 12 clientes nuevos (datos completos) y abre 1 cuenta ACTIVA por cliente
# con saldo 0 y límite diario 0 (sin restricción). Además demuestra las reglas de
# negocio con casos de error deliberados (400 / 409 / 422), fáciles de replicar
# a mano desde Swagger (http://<ip>:8080/swagger-ui.html).
#
# Flujo (coherente con las entidades Java del dominio):
#   00) Estado/seed: totales actuales de clientes y cuentas (antes/después)
#   01) POST /customers     12 altas VÁLIDAS (RETAIL | PREMIUM | CORPORATE)
#   02) POST /customers     4  casos de ERROR (email duplicado, segmento
#       inválido, nombre vacío, correo malformado)
#   03) POST /accounts      1 cuenta ACTIVA por cliente (balance 0,
#       dailyTransferLimit 0) -> números de cuenta únicos por corrida
#   04) POST /accounts      4  casos de ERROR (saldo sin justificar 422,
#       cliente inexistente 400, número duplicado 409, moneda inválida 400)
#   05) Verificación        GET /accounts/customer/{id} de un cliente nuevo
#   06) Resumen             ids de clientes/cuentas + curl listos para copiar
#
# Reglas de negocio ejercitadas (ver AccountService/CustomerService):
#   - balance debe ser 0: el saldo nace con un DEPOSIT (si no -> 422).
#   - email único (409), número de cuenta único (409).
#   - dailyTransferLimit es informativo: 0 = sin límite configurado.
#
# Uso:
#   API=http://localhost:8080        ./scripts/onboarding_clientes.sh
#   API=http://23.20.196.80:8080     ./scripts/onboarding_clientes.sh
#
# Variables:
#   API   URL base de la API   (default http://localhost:8080)
#
# Evidencia: scripts/evidencia/onboarding-<timestamp>/
#
set -euo pipefail

API="${API:-http://localhost:8080}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
SUF="$(date +%s%N)"
EVR="$ROOT/scripts/evidencia/onboarding-${STAMP}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$EVR"

GREEN='\033[0;32m'; BLUE='\033[1;34m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
step() { echo; printf "${BLUE}=== %s ===${NC}\n" "$1"; }
ok()   { printf "${GREEN}[OK]${NC} %s\n" "$1"; }
err()  { printf "${RED}[ERR]${NC} %s\n" "$1"; }

BODY="$TMP/body"
HTTP=000
TIME=0.0

call() { # call <METHOD> <PATH> [<req-file>]   -> usa $API
    local method="$1" path="$2" req="${3:-}"
    local meta="$TMP/meta" raw
    : > "$BODY"
    local args=(curl -sS --max-time 40 -o "$BODY" -w '%{http_code}|%{time_total}'
        -X "$method" "$API$path" -H 'Content-Type: application/json')
    [ -n "$req" ] && args+=(-d @"$req")
    "${args[@]}" > "$meta" 2>&1 || true
    raw="$(cat "$meta")"
    HTTP="${raw%|*}"
    TIME="${raw##*|}"
}

jget() { # jget <campo>  — extrae campo del JSON en $BODY (vacío si no existe)
    python3 -c "import json,sys; print(json.load(open('$BODY')).get('$1',''))" 2>/dev/null || true
}

count_json() { # count_json  — tamaño de la lista en $BODY
    python3 -c "import json,sys; print(len(json.load(open('$BODY'))))" 2>/dev/null || echo 0
}

save_body() { # save_body <archivo> [titulo]
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

echo "SmartBancs — alta masiva de clientes (onboarding) con casos de error"
echo "  API=$API  run=$STAMP"
echo "  Evidencia: $EVR"
echo

# ---------------------------------------------------------------------------
step "00) Estado actual (antes) — total de clientes y cuentas"
BEFORE_CUSTOMERS=0; BEFORE_ACCOUNTS=0
{
    call GET /customers
    BEFORE_CUSTOMERS="$(count_json)"
    echo "clientes_antes=$BEFORE_CUSTOMERS"
    call GET /accounts
    BEFORE_ACCOUNTS="$(count_json)"
    echo "cuentas_antes=$BEFORE_ACCOUNTS"
} > "$EVR/00-estado-antes.txt"
cat "$EVR/00-estado-antes.txt"
ok "$EVR/00-estado-antes.txt"

# ---------------------------------------------------------------------------
# Clientes VÁLIDOS: 'slug|Nombre completo|Segmento'  (email = slug.<run>@example.com)
CLIENTES=(
    "maria.gomez|María Presentación Gómez|RETAIL"
    "juan.ramirez|Juan Carlos Ramírez Torres|RETAIL"
    "ana.fernandez|Ana Lucía Fernández Rojas|PREMIUM"
    "pedro.salazar|Pedro Xavier Salazar Vega|RETAIL"
    "lucia.mendoza|Lucía del Carmen Mendoza Paredes|PREMIUM"
    "jorge.tello|Jorge Andrés Tello Núñez|CORPORATE"
    "sofia.cardenas|Sofía Valentina Cárdenas Ruiz|PREMIUM"
    "carlos.huaman|Carlos Enrique Huamán Díaz|RETAIL"
    "rosa.quispe|Rosa Isabel Quispe Mamani|RETAIL"
    "luis.perez|Luis Alberto Pérez Flores|RETAIL"
    "valeria.sotomayor|Valeria Antonia Sotomayor Bocanegra|PREMIUM"
    "renato.cabrera|Renato Eduardo Cabrera Velásquez|CORPORATE"
)
N_OK="${#CLIENTES[@]}"
# notable: el cliente 03 (ana.fernandez) se usa después para demostrar el email duplicado.

declare -a CUSTOMER_IDS=()   # ids de las altas válidas (en orden)
CLIENT_JSON="$TMP/cliente.json"
ACC_COUNTER=0
TOTAL_OK_CUSTOMERS=0
TOTAL_OK_ACCOUNTS=0

step "01) Alta de clientes — $N_OK altas VÁLIDAS (POST /customers)"
{
    i=0
    for entry in "${CLIENTES[@]}"; do
        i=$((i + 1))
        slug="${entry%%|*}"; rest="${entry#*|}"
        name="${rest%%|*}"; seg="${rest#*|}"
        email="${slug}.${STAMP}@example.com"
        cat > "$CLIENT_JSON" <<EOF
{"fullName": "$name", "email": "$email", "segment": "$seg"}
EOF
        call POST /customers "$CLIENT_JSON"
        CID="$(jget id)"
        CUSTOMER_IDS+=("$CID")
        printf '  [%02d/%s OK ] POST /customers  %-42s %-9s -> %s | %ss  id=%s\n' \
            "$i" "$N_OK" "$name" "$seg" "$HTTP" "$TIME" "$CID"
        if [ "$HTTP" = "201" ] && [ -n "$CID" ]; then
            TOTAL_OK_CUSTOMERS=$((TOTAL_OK_CUSTOMERS + 1))
        else
            err "NO se creó el cliente $i (HTTP $HTTP) — ¿cliente esperado?"
        fi
    done
} > "$EVR/01-clientes-validos.txt"
cat "$EVR/01-clientes-validos.txt"
ok "$EVR/01-clientes-validos.txt"

# ---------------------------------------------------------------------------
step "02) Casos de ERROR en clientes (POST /customers — reglas de negocio)"
ERRORS_CLIENTS=0
{
    # E1: email ya registrado (duplicado del cliente 03 dentro del mismo run) -> 409
    echo "## E1) Email duplicado (mismo correo que el cliente 03) -> 409 esperado"
    cat > "$CLIENT_JSON" <<EOF
{"fullName": "Ana Lucía Fernández Rojas (reintento)", "email": "ana.fernandez.${STAMP}@example.com", "segment": "PREMIUM"}
EOF
    call POST /customers "$CLIENT_JSON"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY" 2>/dev/null || cat "$BODY"
    r=$([ "$HTTP" = "409" ] && echo OK || echo FALLO)
    [ "$HTTP" = "409" ] && ERRORS_CLIENTS=$((ERRORS_CLIENTS + 1))

    echo
    echo "## E2) Segmento no válido (VIP no existe: RETAIL|PREMIUM|CORPORATE) -> 400 esperado"
    cat > "$CLIENT_JSON" <<EOF
{"fullName": "Cliente Prueba Segmento", "email": "vip.${STAMP}@example.com", "segment": "VIP"}
EOF
    call POST /customers "$CLIENT_JSON"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY" 2>/dev/null || cat "$BODY"
    [ "$HTTP" = "400" ] && ERRORS_CLIENTS=$((ERRORS_CLIENTS + 1))

    echo
    echo "## E3) Nombre vacío (fullName es obligatorio) -> 400 esperado"
    cat > "$CLIENT_JSON" <<EOF
{"fullName": "", "email": "vacio.${STAMP}@example.com", "segment": "RETAIL"}
EOF
    call POST /customers "$CLIENT_JSON"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY" 2>/dev/null || cat "$BODY"
    [ "$HTTP" = "400" ] && ERRORS_CLIENTS=$((ERRORS_CLIENTS + 1))

    echo
    echo "## E4) Correo malformado -> 400 esperado"
    cat > "$CLIENT_JSON" <<EOF
{"fullName": "Correo Malformado", "email": "no-es-un-correo", "segment": "RETAIL"}
EOF
    call POST /customers "$CLIENT_JSON"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY" 2>/dev/null || cat "$BODY"
    [ "$HTTP" = "400" ] && ERRORS_CLIENTS=$((ERRORS_CLIENTS + 1))
} > "$EVR/02-clientes-errores.txt"
cat "$EVR/02-clientes-errores.txt"
ok "$EVR/02-clientes-errores.txt"

# ---------------------------------------------------------------------------
step "03) Apertura de cuentas — 1 cuenta ACTIVA por cliente (balance 0, límite 0)"
ACCOUNT_JSON="$TMP/cuenta.json"
FIRST_ACCOUNT_NUM=""
{
    i=0
    for CID in "${CUSTOMER_IDS[@]}"; do
        i=$((i + 1))
        ACC_COUNTER=$((ACC_COUNTER + 1))
        NUM="4651${SUF}${ACC_COUNTER}"
        [ -n "$FIRST_ACCOUNT_NUM" ] || FIRST_ACCOUNT_NUM="$NUM"
        CURR="PEN"
        if [ "$i" -eq 3 ] || [ "$i" -eq 6 ]; then CURR="USD"; fi
        cat > "$ACCOUNT_JSON" <<EOF
{"customerId": "$CID", "accountNumber": "$NUM", "currency": "$CURR",
 "balance": 0.00, "dailyTransferLimit": 0.00, "status": "ACTIVE"}
EOF
        call POST /accounts "$ACCOUNT_JSON"
        AID="$(jget id)"
        printf '  [%02d/%s OK ] POST /accounts  #%-25s %s -> %s | %ss  id=%s\n' \
            "$i" "$N_OK" "$NUM" "$CURR" "$HTTP" "$TIME" "$AID"
        if [ "$HTTP" = "201" ] && [ -n "$AID" ]; then
            TOTAL_OK_ACCOUNTS=$((TOTAL_OK_ACCOUNTS + 1))
        else
            err "NO se creó la cuenta del cliente $i (HTTP $HTTP)"
        fi
    done
} > "$EVR/03-cuentas-validas.txt"
cat "$EVR/03-cuentas-validas.txt"
ok "$EVR/03-cuentas-validas.txt"

# ---------------------------------------------------------------------------
step "04) Casos de ERROR en cuentas (POST /accounts — reglas de negocio)"
ERRORS_ACCOUNTS=0
{
    # A1: balance distinto de 0 sin DEPOSIT que lo justifique -> 422
    echo "## A1) Saldo inicial 150.00 no justificado por DEPOSIT -> 422 esperado"
    cat > "$ACCOUNT_JSON" <<EOF
{"customerId": "${CUSTOMER_IDS[0]}", "accountNumber": "4651${SUF}91", "currency": "PEN",
 "balance": 150.00, "dailyTransferLimit": 0.00, "status": "ACTIVE"}
EOF
    call POST /accounts "$ACCOUNT_JSON"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY" 2>/dev/null || cat "$BODY"
    [ "$HTTP" = "422" ] && ERRORS_ACCOUNTS=$((ERRORS_ACCOUNTS + 1))

    echo
    echo "## A2) Cliente inexistente -> 400 esperado"
    cat > "$ACCOUNT_JSON" <<EOF
{"customerId": "00000000-0000-0000-0000-000000000999", "accountNumber": "4651${SUF}92", "currency": "PEN",
 "balance": 0.00, "dailyTransferLimit": 0.00, "status": "ACTIVE"}
EOF
    call POST /accounts "$ACCOUNT_JSON"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY" 2>/dev/null || cat "$BODY"
    [ "$HTTP" = "400" ] && ERRORS_ACCOUNTS=$((ERRORS_ACCOUNTS + 1))

    echo
    echo "## A3) Número de cuenta duplicado ($FIRST_ACCOUNT_NUM ya existe) -> 409 esperado"
    cat > "$ACCOUNT_JSON" <<EOF
{"customerId": "${CUSTOMER_IDS[1]}", "accountNumber": "$FIRST_ACCOUNT_NUM", "currency": "PEN",
 "balance": 0.00, "dailyTransferLimit": 0.00, "status": "ACTIVE"}
EOF
    call POST /accounts "$ACCOUNT_JSON"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY" 2>/dev/null || cat "$BODY"
    [ "$HTTP" = "409" ] && ERRORS_ACCOUNTS=$((ERRORS_ACCOUNTS + 1))

    echo
    echo "## A4) Moneda inválida (XX no es ISO 4217 de 3 letras) -> 400 esperado"
    cat > "$ACCOUNT_JSON" <<EOF
{"customerId": "${CUSTOMER_IDS[2]}", "accountNumber": "4651${SUF}93", "currency": "XX",
 "balance": 0.00, "dailyTransferLimit": 0.00, "status": "ACTIVE"}
EOF
    call POST /accounts "$ACCOUNT_JSON"
    echo "HTTP $HTTP | ${TIME}s"
    python3 -m json.tool "$BODY" 2>/dev/null || cat "$BODY"
    [ "$HTTP" = "400" ] && ERRORS_ACCOUNTS=$((ERRORS_ACCOUNTS + 1))
} > "$EVR/04-cuentas-errores.txt"
cat "$EVR/04-cuentas-errores.txt"
ok "$EVR/04-cuentas-errores.txt"

# ---------------------------------------------------------------------------
step "05) Verificación — cuenta del primer cliente (saldo 0, límite 0, ACTIVE)"
call GET /accounts/customer/"${CUSTOMER_IDS[0]}"
save_body 05-cuentas-cliente-1.txt "GET /accounts/customer/{id} — cuentas del primer cliente"

# ---------------------------------------------------------------------------
step "06) Resumen"
CUSTOMER_FIRST="${CUSTOMER_IDS[0]}"
{
    echo "SmartBancs — onboarding ${STAMP}"
    echo "Fecha: $(date)"
    echo
    echo "Resumen de la corrida"
    echo "  clientes creados : ${TOTAL_OK_CUSTOMERS}/${N_OK}"
    echo "  cuentas creadas  : ${TOTAL_OK_ACCOUNTS}/${N_OK}  (saldo 0.00, límite 0.00, ACTIVE)"
    echo "  errores mostrados: ${ERRORS_CLIENTS} en clientes (400/409) + ${ERRORS_ACCOUNTS} en cuentas (400/409/422)"
    echo
    echo "Antes -> después"
    echo "  clientes: ${BEFORE_CUSTOMERS} -> $((BEFORE_CUSTOMERS + TOTAL_OK_CUSTOMERS))"
    echo "  cuentas : ${BEFORE_ACCOUNTS} -> $((BEFORE_ACCOUNTS + TOTAL_OK_ACCOUNTS))"
    echo
    echo "Ids creados (para pegar en Swagger)"
    for i in "${!CUSTOMER_IDS[@]}"; do
        printf '  cliente %02d: %s\n' "$((i + 1))" "${CUSTOMER_IDS[$i]}"
    done
    echo
    echo "Verificación rápida (curl)"
    echo "  Listar clientes      : curl -s $API/customers | python3 -m json.tool"
    echo "  Cuentas del cliente 1: curl -s $API/accounts/customer/$CUSTOMER_FIRST | python3 -m json.tool"
    echo "  Swagger UI           : $API/swagger-ui.html"
} > "$EVR/06-resumen.txt"
cat "$EVR/06-resumen.txt"
ok "$EVR/06-resumen.txt"

echo
echo "====================================================="
echo "Onboarding completado. Evidencia en:"
echo "  $EVR"
echo "====================================================="