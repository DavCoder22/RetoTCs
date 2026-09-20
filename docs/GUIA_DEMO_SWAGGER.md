# Guía de demostración en Swagger (presentación pública)

> Foco: **funciones y capas**. Cada paso de este recorrido es una *función* de
> negocio identificable (método HTTP + ruta) y muestra qué *capa* la resuelve:
> **Controller** (orquesta HTTP) → **Service** (regla de negocio) → **Repository**
> (persistencia de datos). Nada se explica por "clic"; todo se explica por
> "qué función se ejecutó y en qué capa vive su regla".

## 0. Escenario reproducible

Prerrequisito: el stack corriendo.

```bash
docker compose up --build -d
```

Para números predecibles en vivo, resetea la base:

```bash
docker compose down -v && docker compose up --build -d
```

Recursos usados en la demo:

| Recurso | URL |
| --- | --- |
| Swagger UI de la API | `http://localhost:8080/swagger-ui.html` |
| OpenAPI spec (JSON) | `http://localhost:8080/v3/api-docs` |
| Swagger UI del AI service | `http://localhost:8081/swagger-ui.html` |
| Actuator / health | `http://localhost:8080/actuator/health` |

**Cómo leer Swagger UI**: el panel izquierdo agrupa las funciones por
*controller* (`customer-controller`, `account-controller`, `transaction-controller`,
`health-controller`). Cada bloque es una función con su firma HTTP completa
(método + ruta + parámetros). La API expone 17 operaciones.

**Los cuerpos vienen PRECARGADOS**: los DTOs declaran `example` y *required* en la
spec, así que al pulsar **Try it out** ya aparece el JSON de la plantilla, sin
copiar/pegar. Solo se reemplazan los **ids dinámicos** del propio journey
(`customer_id`, `account_id`) cuando quieras encadenar pasos; los valores que
vienen por defecto apuntan a datos seed y funcionan de inmediato.

## El recorrido (historia)

> *"SmartBancs da de alta a una clienta PREMIUM, le abre una cuenta, le abona su
> salario (DEPOSIT), la clienta transfiere una parte de su dinero, y el banco
> intenta operaciones que la lógica de negocio rechaza: abrir una cuenta con
> saldo, transferir sin fondos y borrar registros con dependencias."*

Cada paso =\> una función. Antes de ejecutar cada uno, señala en pantalla el
bloque (controller/service) que lo resuelve.

---

## Paso 1 — Crear un cliente (`POST /customers`)

**Función:** alta de identidad de cliente.
**Capas:** `CustomerController.create` → `CustomerService.create` (email único,
segmento válido) → `customerRepository.save`.

En Swagger UI: `POST /customers` → **Try it out** → reemplaza el cuerpo:

```json
{
  "fullName": "María Presentación Gómez",
  "email": "maria.presentacion@example.com",
  "segment": "PREMIUM"
}
```

**Execute** → `201 Created`. Toma el `id` de la respuesta (**customer_id**).

*Para decir en público:* "El controlador aceptó el JSON, pero la decisión de
crearlo la tomó el servicio: es la capa que valida y escribe. En Java cada capa
es una clase = una función con responsabilidad única."

---

## Paso 2 — Abrir una cuenta con saldo 0 (`POST /accounts`)

**Función:** apertura de cuenta.
**Capas:** `AccountController.create` → `AccountService.create`.

Cuerpo (reemplaza `<customer_id>`):

```json
{
  "customerId": "<customer_id>",
  "accountNumber": "4651DEMO00000000001",
  "currency": "PEN",
  "balance": 0,
  "dailyTransferLimit": 10000,
  "status": "ACTIVE"
}
```

**Execute** → `201 Created`. Copia el **account_id**.

> Nota para el presentador: `accountNumber` ya viene precargado
> (`4651DEMO00000000001`). Si repites la demo en vivo, cambia ese número por uno
> nuevo (la cuenta no se puede duplicar).

### Demostración de regla de negocio (el "no")

Repite el mismo `POST /accounts` pero con `"balance": 1000` → **`422`**
`"initial balance must be justified by a DEPOSIT transaction: create the account
with balance 0 and register a deposit"`.

*Para decir en público:* "El `422` llega desde la función del servicio, no desde
la interfaz. Aunque el usuario envíe un saldo inicial, la regla lo rechaza: el
saldo de una cuenta nace siempre de una transacción registrada."

---

## Paso 3 — Depósito (`POST /transactions`, type `DEPOSIT`)

**Función:** registrar un abono y asentarlo en el ledger.
**Capas:** `TransactionController.create` → `TransactionService.processTransfer`
→ repo (inserción atómica de transacción + asiento de ledger).

Cuerpo:

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

**Execute** → `201`, `status: SUCCEEDED`. Copia el **transaction_id**.

### Idempotencia (repetir sin duplicar)

Re-ejecuta el **mismo cuerpo** (mismo `idempotencyKey`) → devuelve el **mismo
`transaction_id`** (200) y **no duplica** el asiento.

*Para decir en público:* "La función es *idempotente*: mismo insumo, mismo
resultado. Un tema crítico para una API de pagos: si la red reintenta, el banco
no cobra dos veces."

---

## Paso 4 — Leer el ledger (`GET /transactions/{id}/ledger`)

**Función:** leer los asientos de una transacción.
**Capas:** `TransactionController.ledger` → repo (solo lectura de datos).

`GET /transactions/{transaction_id}/ledger` → devuelve un asiento
`{"type": "CREDIT", "amount": 1500}`.

Luego `GET /accounts/{account_id}` → `balance: 1500`.

*Para decir en público:* "El saldo de la cuenta **es** la suma de sus asientos.
Aquí el saldo (1500) coincide con el asiento CREDIT del depósito: el invariante
contable se cumple. Los datos no se escriben 'a mano': se derivan del ledger."

---

## Paso 5 — Transferencia y su error (`POST /transactions`, type `TRANSFER`)

**Función:** transferir fondos entre dos cuentas.
**Capas:** `TransactionService` ejecuta la regla completa: verificar fondos,
mismo conductor (debit/credit), límite diario y asientos dobles.

Primero crea un destinatario (repite Paso 1 y Paso 2 para un `RETAIL`) o usa una
cuenta seed existente. Luego:

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

**Execute** → `201 SUCCEEDED`. Revisa `GET /transactions/{id}/ledger` →
**dos asientos**: un `DEBIT` en origen y un `CREDIT` en destino.

### Error controlado

Repite la transferencia con `"amount": 999999` → **`422`**
`"insufficient funds"`.

*Para decir en público:* "Una transferencia no es dos UPDATEs sueltos: es una
sola función que escribe dos asientos en una transacción. La regla del saldo y
los asientos dobles viven en el Service. Eso es lo que hace consistente al dato."

---

## Paso 6 — Borrado protegido (`DELETE`, reglas de dependencia)

**Funciones:** `CustomerService.delete` y `AccountService.delete`.
**Capas:** Service (no acepta destruir un recurso con dependencias).

En orden:

1. `DELETE /customers/{customer_id}` (la clienta tiene cuentas) → **`409`**
   `"customer cannot be deleted while it still has accounts"`.
2. `DELETE /accounts/{account_id}` (la cuenta tiene saldo e historial) → **`409`**.

### Contraste: el borrado que sí funciona

Crea un **cliente temporal sin cuentas** (Paso 1) y borra su detalle:

- `DELETE /customers/{temporal_id}` → **`204`** (sin cuerpo).
- `GET /customers/{temporal_id}` → **`404`** `"customer not found"`.

*Para decir en público:* "DELETE existe, pero es una función con reglas: no se
borra un cliente con cuentas ni una cuenta con historial, porque eso rompería el
ledger. El ciclo completo del CRUD (crear → listar → borrar → ya no existe) se
ve con el cliente temporal."

---

## Paso 7 — Cierre: la aplicación, no el CRUD

1. `GET /health` → `200` (función de monitoreo).
2. Abre `http://localhost:8081/swagger-ui.html`: el **AI service** es un
   microservicio independiente con su propio contrato (`/internal/...`), que
   consume la API sin bloquear las transacciones (uno de los requerimientos del
   reto: las recomendaciones de IA nunca bloquean el flujo principal).

---

## Resumen para la audiencia

| Paso | Función (endpoint) | Capa que decide | Concepto |
| --- | --- | --- | --- |
| 1 | `POST /customers` | Controller → Service → Repo | Responsabilidad única por capa |
| 2 | `POST /accounts` | Service | Regla de negocio (saldo inicial = 0) |
| 3 | `POST /transactions` (DEPOSIT) | Service | Transacción atómica + idempotencia |
| 4 | `GET /transactions/{id}/ledger` | Repo | Lectura de datos / invariante contable |
| 5 | `POST /transactions` (TRANSFER) | Service | Asientos dobles, fondos, límite diario |
| 6 | `DELETE /customers/{id}` | Service | Reglas de dependencia (no destruir el ledger) |
| 7 | `GET /health` + AI service | Infraestructura | Aplicación en microservicios |

## Hallazgos registrados

- Los cuerpos de `POST /customers`, `POST /accounts` y `POST /transactions` vienen
  precargados con ejemplos funcionales (DTOs con `@Schema(example)`) y los campos
  obligatorios quedan marcados con `*` en Swagger, alineando spec y validación.
- Los `idempotencyKey` permiten repetir segmentos de la demo sin duplicar datos.
- El API bloquea el borrado con si el recurso tiene dependencias porque la
  destrucción de historial rompería la justificación del saldo en el ledger.