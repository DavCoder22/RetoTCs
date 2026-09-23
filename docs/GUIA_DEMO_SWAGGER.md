# Manual de usuario — SmartBancs (guía de pruebas por secciones)

> Manual estandarizado para **probar cada sección** de la solución con Swagger
> (o `curl`). Cada sección del reto tiene su **objetivo**, sus **pasos** y su
> **resultado esperado**. Todas las secciones son independientes: se pueden
> validar una a una, en el orden que prefieras.

- **0.** Preparar el entorno
- **1.** Clientes (CRUD)
- **2.** Cuentas (apertura + reglas)
- **3.** Transacciones (depósito y transferencia)
- **4.** Ledger e invariante contable
- **5.** ETL e ingesta por lotes
- **6.** Agente de IA (recomendaciones)
- **7.** Observabilidad (Grafana/Prometheus)
- **8.** Despliegue en AWS (EC2 + Docker)
- **9.** Mapa rápido de pruebas

---

## 0. Preparar el entorno

Levanta el stack (requiere Docker):

```bash
docker compose up --build -d
docker compose ps          # todo en estado healthy
```

Para números predecibles en vivo, resetea la base:

```bash
docker compose down -v && docker compose up --build -d
```

Recursos disponibles:

| Recurso | URL |
| --- | --- |
| Swagger UI de la API | `http://localhost:8080/swagger-ui.html` |
| OpenAPI spec (JSON) | `http://localhost:8080/v3/api-docs` |
| Health de la API | `http://localhost:8080/actuator/health` |
| **AI service** (FastAPI, Swagger integrado) | `http://localhost:8081/docs` |
| Health del AI service | `http://localhost:8081/health` |
| Prometheus (métricas) | `http://localhost:9090` |
| Grafana (dashboard + alertas) | `http://localhost:3333` |

**Cómo usar Swagger:** el panel izquierdo agrupa las operaciones por
*controller* (`customer-controller`, `account-controller`, `transaction-controller`,
`health-controller`). Los cuerpos vienen **precargados** (los DTOs declaran
`example`), así pulsa **Try it out → Execute** sin copiar/pegar. Solo cambia los
**ids dinámicos** (`customer_id`, `account_id`) al encadenar pasos. El `AI
service` expone su documentación interactiva en **`/docs`** (FastAPI).

> **Datos de seed:** hay clientes/cuentas precargados; si un `idempotencyKey` o
> `accountNumber` ya se usó en una ejecución previa, cámbialo y vuelve a
> intentar (2 respuestas posibles: `201` nuevo o el `200`/`409` repetido de la
> primera).

---

## 1. Clientes (CRUD)

**Objetivo:** comprobar el ciclo completo de vida de un cliente y sus reglas.

**1.1 Crear** — `POST /customers`

```json
{
  "fullName": "María Presentación Gómez",
  "email": "maria.presentacion@example.com",
  "segment": "PREMIUM"
}
```
**Esperado:** `201 Created` con un `id`.

**1.2 Listar / consultar** — `GET /customers` y `GET /customers/{id}` → `200`.

**1.3 Duplicado** — repetir el `POST` anterior → `409` por email único.

**1.4 Modificar** — `PUT /customers/{id}` → `200`.

**1.5 Borrado protegido** — `DELETE /customers/{id}` de un cliente **con
cuentas** → `409` (no rompe el historial).

**1.6 Contraste** — crear un cliente temporal **sin cuentas** y borrarlo →
`204`; luego `GET /customers/{id}` → `404`.

---

## 2. Cuentas (apertura + reglas)

**Objetivo:** comprobar que una cuenta nace con saldo 0 y que el saldo solo
proviene de transacciones.

**2.1 Abrir cuenta** — `POST /accounts` (reemplaza `<customer_id>`):

```json
{
  "customerId": "<customer_id>",
  "accountNumber": "4651DEMO00000000002",
  "currency": "PEN",
  "balance": 0,
  "dailyTransferLimit": 10000,
  "status": "ACTIVE"
}
```
**Esperado:** `201 Created` con `account_id`.

**2.2 Regla: saldo inicial ≠ 0** — repetir con `"balance": 1000` → `422`
"initial balance must be justified by a DEPOSIT transaction".

**2.3 Cuenta duplicada** — repetir el mismo `accountNumber` → `409`.

**2.4 Consultar saldo** — `GET /accounts/{account_id}` → `balance: 0` antes de
cualquier transacción.

---

## 3. Transacciones (depósito y transferencia)

**Objetivo:** comprobar abono, transferencia, idempotencia y errores de regla.

**3.1 Depósito** — `POST /transactions` (reemplaza `<account_id>`):

```json
{
  "type": "DEPOSIT",
  "amount": 1500,
  "currency": "PEN",
  "creditAccountId": "<account_id>",
  "idempotencyKey": "presentacion-2026-01",
  "reference": "Depósito inicial"
}
```
**Esperado:** `201`, `status: SUCCEEDED`.

**3.2 Idempotencia** — re-ejecutar el **mismo cuerpo** → el **mismo**
`transaction_id` y **sin duplicar** el asiento.

**3.3 Transferencia** — crea un destinatario (sección 1+2) y envía:

```json
{
  "type": "TRANSFER",
  "amount": 100,
  "currency": "PEN",
  "debitAccountId": "<account_id_origen>",
  "creditAccountId": "<account_id_destino>",
  "idempotencyKey": "presentacion-transf-01"
}
```
**Esperado:** `201 SUCCEEDED`; el ledger genera **dos asientos** (DEBIT/CREDIT).

**3.4 Error: fondos insuficientes** — repetir con `"amount": 999999` → `422`
`insufficient funds`.

**3.5 Cuenta inactiva** — usar una cuenta `BLOCKED`/`CLOSED` en una
transferencia → `422` (cuenta no operativa).

---

## 4. Ledger e invariante contable

**Objetivo:** comprobar que el saldo **se deriva** de los asientos (invariante
contable: todo DEBIT tiene su CREDIT).

**4.1** `GET /transactions/{transaction_id}/ledger` → lista de asientos
(`{"type","amount","accountId",...}`). En un depósito: 1 asiento `CREDIT`.

**4.2** `GET /accounts/{account_id}` → el `balance` **coincide** con la suma de
sus asientos (ej.: tras el depósito de 1500 → `balance: 1500`).

**4.3** En una transferencia: el ledger muestra **2 asientos**
(DEBIT en origen + CREDIT en destino) con montos idénticos → la contabilidad
de partida doble se mantiene.

---

## 5. ETL e ingesta por lotes

**Objetivo:** comprobar la limpieza/normalización de un lote crudo y su envío a
la API. Requiere el stack levantado.

**5.1 Ver el reporte sin publicar nada:**

```bash
python3 etl/etl_transform.py --input etl/sample-data/raw_transactions.csv --dry-run
```

**5.2 Cargar el lote de verdad:**

```bash
python3 etl/etl_transform.py --input etl/sample-data/raw_transactions.csv
```

**5.3 Resultado esperado:** en `etl/output/` quedan
`transactions_fact.csv` (una fila por leg del ledger, `amount_minor_units`
entero) y `etl_report.json` con conteos (`raw_rows`, `valid`, `dropped`,
`duplicates`, `sent`, `accepted`, `rejected`).

**5.4 Con el lote de muestra:** 16 crudas → 11 válidas → 10 analizables (1
duplicada omitida) → 9 publicadas → **8 aceptadas** + 1 rechazada por
`insufficient funds`. Al re-ejecutar no duplica (idempotencia por
`idempotencyKey` determinístico).

> Probar el endpoint directamente: `POST /transactions/batch` con el JSON de
> un *payload* de 1..n transacciones → `200` con conteos `accepted`/`rejected`
> por ítem.

---

## 6. Agente de IA (recomendaciones)

**Objetivo:** comprobar que el agente de IA genera recomendaciones **sin
bloquear** el flujo transaccional (requisito del reto).

| Endpoint | URL | Para qué |
| --- | --- | --- |
| Documentación (FastAPI) | `http://localhost:8081/docs` | Probar el contrato directo |
| Health | `http://localhost:8081/health` | Verificar que está vivo |
| Métricas | `http://localhost:8081/metrics` | Ver `smartbancs_ai_*` |
| Contrato interno | `POST /internal/recommendations` | Generar una recomendación manual |

**6.1** `GET /health` del agente → `200` (`{"status":"up",...}`).

**6.2** En Swagger del agente (`/docs`): `POST /internal/recommendations` con un
contexto transaccional → `200` con `source: "mock"` (sin `OPENROUTER_API_KEY`)
o `source: "openrouter"` (con clave).

**6.3 Desde la API** — `GET /recommendations?customerId=<customer_id>&limit=20`
→ lista las recomendaciones generadas asíncronamente para ese cliente.

**6.4 No bloqueo**: dispara un depósito/transferencia y verifica en Tempo que
la traza de `/transactions` termina < 2 s **sin** spans de IA dentro (el
worker asíncrono corre aparte).

---

## 7. Observabilidad (Grafana/Prometheus)

**Objetivo:** comprobar las 4 señales (métricas, logs, trazas, alertas).

**7.1 Métricas** — `http://localhost:9090/targets` → los 4 targets en `UP`
(api, ai-service, postgres-exporter, prometheus). En *Graph* consulta
`smartbancs_transactions_total` tras una transacción (sección 3).

**7.2 ALERTAS** — *Alerts* en Prometheus → **8 reglas** cargadas y evaluando.

**7.3 Dashboard** — Grafana (`http://localhost:3333`, d. admin/admin) →
dashboard **"SmartBancs – Observabilidad"**: paneles de negocio (transacciones,
latencia), JVM y PostgreSQL.

**7.4 Trazas** — Grafana → **Explore → Tempo**: busca el `traceId` de una
transacción y observa los spans (API → repositorio → BD).

**7.5 Logs** — Grafana → **Explore → Loki**: logs JSON con `traceId`; salta
hasta la traza en Tempo (correlación log↔traza).

---

## 8. Despliegue en AWS (EC2 + Docker)

**Objetivo:** comprobar el despliegue en AWS (Terraform → **instancias EC2** +
**imágenes Docker**) y el **acceso público a la API**.

**8.1** Localiza la URL de la instancia que lanza el workflow de CI:

```bash
cd terraform && terraform output
# public_ip       = "54.x.x.x"
# swagger_url     = "http://54.x.x.x:8080/swagger-ui.html"
```

**8.2** Abre `swagger_url` desde cualquier navegador → Swagger de la API en
AWS. Repite alguna sección de este manual (ej. 3.1 Depósito) sobre esa URL.

**8.3** `GET http://<public_ip>:8080/actuator/health` → `200`.

> Las imágenes son las mismas del MVP local (ECR + `docker compose`): lo que
> pruebas en local funciona igual en la EC2.

---

## 9. Prueba de estrés con k6 (resiliencia)

**Objetivo:** demostrar resiliencia bajo carga tras el flujo completo CRUD
(crear → editar → eliminar) de las entidades Java, con **10.000 transferencias
de 0.01** entre dos cuentas y los resultados **visibles en Prometheus/Grafana**.

El runner `./scripts/stress.sh` orquesta todo el flujo en este orden:

1. **Health checks** (`/actuator/health`).
2. **CRUD de entidades**: `POST /customers` → `PUT /customers/{id}` → `POST
   /accounts` → `PUT /accounts/{id}` → `DELETE /accounts/{id}` → `DELETE
   /customers/{id}` (mismos endpoints que aparecen en este Swagger).
3. **Fondos**: deposita el monto necesario si la cuenta origen no cubre el
   total (garantiza que las 10.000 no fallen por `422`).
4. **k6** (`scripts/k6/transfers.js`): cada iteración es un `POST
   /transactions` con `idempotencyKey` única, así las 10.000 generan asientos
   **reales** en el ledger (sin duplicados).
5. **Remote write**: las métricas `k6_*` se envían a Prometheus
   (`/api/v1/write`, ya habilitado con `--web.enable-remote-write-receiver`).

**9.1** Ejecutar (local o contra AWS):

```bash
# Local
./scripts/stress.sh

# Contra la instancia AWS (resultados visibles en su Grafana :3333)
API=http://<public_ip>:8080 PROM=http://<public_ip>:9090 ./scripts/stress.sh

# Con STRESS=1, la demo completa (demo.sh) termina lanzando la prueba de estrés
STRESS=1 PROM=http://<public_ip>:9090 ./scripts/demo.sh
```

Detalles configurables: `AMOUNT=0.01`, `TOTAL=10000`, `VUS=50`,
`DEBIT_ACCOUNT`/`CREDIT_ACCOUNT`. Evidencia en `scripts/evidencia/stress-*`.

**9.2** Observar en Grafana (`:3333` → Explore → Prometheus):

| Qué ver | Query (filtro por `testid`) |
| --- | --- |
| Carga | `sum(rate(k6_http_reqs_total{testid="<TEST_ID>"}[1m]))` |
| Latencia p99 | `k6_http_req_duration_p99{testid="<TEST_ID>"}` |
| Errores | `k6_http_req_failed_rate{testid="<TEST_ID>"}` |
| Usuarios concurrentes | `k6_vus{testid="<TEST_ID>"}` |
| Respuesta del servidor | `increase(http_server_requests_seconds_count{uri="/transactions"}[1m])` |
| Transacciones asentadas | `smartbancs_transaction_amount_count{type="TRANSFER"}` |
| BD bajo carga | `pg_stat_database_xact_commit` |

> Importa el dashboard oficial de k6 (Grafana.com **ID 13039**) en
> `http://<host>:3333/dashboards/import` y filtra por `testid` para ver solo la
> corrida. Las 10.000 se cruzan en Grafana por dos lados: **cliente (k6)** y
> **servidor (Micrometer + Postgres)**.

---

## 10. Mapa rápido de pruebas

| Sección | Endpoint / comando | Resultado esperado |
| --- | --- | --- |
| Clientes | `POST /customers` → `GET /customers/{id}` → `DELETE` | `201` · `200` · `204`/`409` |
| Cuentas | `POST /accounts` (saldo 0) | `201`; con saldo ≠ 0 → `422` |
| Transacciones | `POST /transactions` DEPOSIT/TRANSFER | `201 SUCCEEDED`; repetir = mismo id |
| Ledger | `GET /transactions/{id}/ledger` | Asientos DEBIT/CREDIT = saldo |
| ETL | `python3 etl/etl_transform.py ...` | `transactions_fact.csv` + `etl_report.json` |
| IA | `GET /recommendations?customerId=...` | Recomendaciones sin bloquear |
| Observabilidad | Grafana :3333 · Prometheus :9090 | 4 targets `UP` · 8 alertas evaluando |
| Resiliencia (k6) | `./scripts/stress.sh` (10k transacciones de 0.01) | Métricas `k6_*` en Prometheus · Grafana Explore |
| AWS | `http://<public_ip>:8080/swagger-ui.html` | API desplegada con imágenes Docker |

---

*Manual alineado con el README (§3–§10) y con las secciones del reto. El
script `./scripts/demo.sh` genera además evidencia reproducible de los flujos
principales.*