# SmartBancs App

> **Reto técnico de ingeniería · NextGen Engineering**
> Plataforma financiera transaccional en tiempo real con recomendaciones
> personalizadas por IA, integrada con el core legado `Bancs`.

SmartBancs procesa **transacciones en tiempo real** y genera **recomendaciones
financieras personalizadas** impulsadas por IA, sin que la IA bloquee jamás el
flujo transaccional principal.

---

## Índice

1. [Resumen ejecutivo](#1-resumen-ejecutivo)
2. [Estado del entregable](#2-estado-del-entregable)
3. [Stack tecnológico](#3-stack-tecnológico)
4. [Arquitectura](#4-arquitectura)
5. [Estructura del repositorio](#5-estructura-del-repositorio)
6. [Puesta en marcha (MVP con Docker)](#6-puesta-en-marcha-mvp-con-docker)
7. [Agente de IA (recomendaciones)](#7-agente-de-ia-recomendaciones)
8. [Observabilidad](#8-observabilidad)
9. [ETL / carga a data warehouse](#9-etl--carga-a-data-warehouse)
10. [Demo y evidencia](#10-demo-y-evidencia)
11. [Ejecución sin Docker (desarrollo)](#11-ejecución-sin-docker-desarrollo)
12. [Documentación técnica](#12-documentación-técnica)
13. [Modelo de dominio](#13-modelo-de-dominio)
14. [Flujo de trabajo Git](#14-flujo-de-trabajo-git)
15. [Licencia y autor](#15-licencia-y-autor)

---

## 1. Resumen ejecutivo

| | |
| --- | --- |
| **Alta concurrencia** | Soporta picos de **10 000 transacciones por segundo** |
| **Latencia** | Transferencia completada en **menos de 2 segundos** |
| **Integración segura** | Se conecta al core legado **`Bancs` sin degradar su rendimiento** |
| **IA no bloqueante** | Las recomendaciones de IA **nunca** entorpecen el flujo transaccional |

> **Autoría y uso de IA.** Las secciones/funciones comprendidas entre la
> **observabilidad** y la **sección del agente de IA** se generaron con el apoyo
> de **Big Pickle** (agente de codificación de **opencode**) y la estructura del
> **mock** se diseñó con el **agente de planificación** de opencode. Es parte de
> la estrategia para documentar la **autoría del backend**: el candidato
> revisa, compila, ejecuta y verifica cada pieza; la IA actúa como apoyo de
> programación. Detalle: [`docs/DECLARACION_IA.md`](docs/DECLARACION_IA.md).

**Entregables del reto:** documento técnico, MVP ejecutable en un repositorio
Git, instrucciones de ejecución, evidencia de funcionamiento y declaración de
uso de IA.

## 2. Estado del entregable

| Etapa | Estado |
| --- | --- |
| Bootstrap del proyecto (devcontainer, estructura, modelo de dominio) | ✅ Hecho |
| MVP local con Docker Compose (PostgreSQL + API + IA + observabilidad) | ✅ Hecho |
| Núcleo de negocio (arquitectura en 3 capas) | ✅ Hecho |
| Acceso a datos + esquema PostgreSQL (Flyway) | ✅ Hecho |
| REST CRUD: customers, accounts, transactions + transferencias y ledger | ✅ Hecho |
| Idempotencia, concurrencia (`FOR UPDATE` / `@Version`) e invariante contable | ✅ Hecho |
| ETL / carga a data warehouse | ✅ Hecho |
| Ingesta por lotes (`POST /transactions/batch`) | ✅ Hecho |
| Agente de IA asíncrono (no bloqueante) | ✅ Hecho (worker/outbox verificado end-to-end; mock + OpenRouter) |
| Observabilidad (métricas · logs · trazas · alertas) | ✅ Hecho |
| Incidente simulado + post mortem | ✅ Hecho |
| Documentación final + evidencia | ✅ Hecho |

## 3. Stack tecnológico

| Capa | Tecnología |
| --- | --- |
| API transaccional | **Java 21** (virtual threads) + **Spring Boot 3** / Maven (multimódulo: `domain`, `infra`, `api`) |
| Agente de IA | **Python 3.12** + **FastAPI** (uvicorn) — servicio independiente y asíncrono |
| Base de datos | **PostgreSQL 16** + **Flyway** (migraciones versionadas) |
| Entorno local | **Docker** + **Docker Compose** (infraestructura como código) |
| Entorno de desarrollo | **Devcontainer** reproducible (JDK 21 + Maven + Docker) |
| Observabilidad | **Prometheus**, **Tempo** (OTLP), **Loki** + **Promtail** y **Grafana** provisionada |
| Despliegue (AWS) | **Terraform** + **GitHub Actions** (OIDC) |

## 4. Arquitectura

```
 clientes / batch ──► smartbancs-api :8080   (REST · virtual threads · reglas de negocio)
                           │  Spring (JPA / @Transactional)
                           ▼
                 smartbancs-infra            (Spring Data JPA + Flyway)
                           ▼
                 PostgreSQL 16               (accounts · transactions · ledger · outbox)
                           │
          ┌────────────────┼───────────────────┐
          ▼                ▼                   ▼
   Bancs (legado)   ETL → fact table     ai-service :8081
   outbox +         para IA/análisis     (Python/FastAPI · recomendaciones)

   Observabilidad transversal: Micrometer → Prometheus · Tempo · Loki · Grafana
```

**Decisiones de diseño** (justificadas en [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md)):

- **Patrón outbox** para integrar con `Bancs`: la transacción se confirma solo
  contra PostgreSQL y un *relayer* entrega eventos a Bancs en lotes (sin
  llamadas síncronas al core).
- **Concurrencia segura**: `SELECT … FOR UPDATE` (bloqueo por fila) + `@Version`
  (optimista) e idempotencia por clave — evitan condiciones de carrera y doble gasto.
- **IA nunca en el camino crítico**: el agente de IA corre como servicio
  separado y se consume de forma asíncrona.

## 5. Estructura del repositorio

```
RetoTCs/
├─ .devcontainer/          # Entorno reproducible (JDK 21 + Maven + Docker)
├─ smartbancs-api/         # API Spring Boot: controllers, DTOs, servicios, errores, métricas
├─ smartbancs-domain/      # Entidades, enums y reglas de negocio (Java puro, sin Spring)
├─ smartbancs-infra/       # Acceso a datos, Flyway, repositorios, clientes IA/Bancs
├─ ai-service/             # Agente de IA: Python/FastAPI (OpenRouter + mock avanzado), independiente
├─ etl/                    # ETL: transformación + ingesta por lote + fact table
├─ scripts/                # demo.sh (evidencia reproducible) · aws-configure.sh
├─ docs/                   # Documentación técnica (en español)
├─ observability/          # Prometheus · Tempo · Loki · Promtail · Grafana (provisionada)
├─ terraform/              # IaC para AWS + workflow OIDC
└─ docker-compose.yml      # MVP local: PostgreSQL + api + ai-service + observabilidad
```

## 6. Puesta en marcha (MVP con Docker)

**Prerrequisito:** Docker con el plugin de Compose.

**1. Configuración (opcional).** Copia `.env.example` a `.env` y define tus
propias credenciales.

**2. Construye y levanta el stack:**

```bash
docker compose up --build -d
```

**3. Verifica el estado de salud:**

```bash
docker compose ps
```

Deben quedar `healthy`: PostgreSQL, API, agente de IA y toda la capa de
observabilidad.

**4. Endpoints disponibles:**

| Servicio | URL |
| --- | --- |
| API · health | `http://localhost:8080/actuator/health` |
| API · Swagger UI | `http://localhost:8080/swagger-ui.html` |
| API · OpenAPI spec | `http://localhost:8080/v3/api-docs` |
| Agente de IA · health | `http://localhost:8081/health` |
| Agente de IA · docs (FastAPI) | `http://localhost:8081/docs` |
| Agente de IA · métricas | `http://localhost:8081/metrics` |
| PostgreSQL | `localhost:5432` (db/usuario `smartbancs`) |
| Grafana | `http://localhost:3333` (`admin`/`admin`, configurable) |
| Prometheus | `http://localhost:9090` |
| Tempo | `http://localhost:3200` |
| Loki | `http://localhost:3100` |
| postgres-exporter | `http://localhost:9187/metrics` |

**5. Detén el stack:**

```bash
docker compose down
```

Para borrar también los volúmenes de datos: `docker compose down -v`.

> **Docker rootless (Linux).** Si `promtail` no encuentra el daemon, exporta las
> rutas antes de `docker compose up`:
> `DOCKER_SOCKET=/run/user/$UID/docker.sock` y
> `DOCKER_CONTAINERS_DIR=$HOME/.local/share/docker/containers`.

## 7. Agente de IA (recomendaciones)

El agente de IA vive en **`ai-service`** (**Python 3.12 + FastAPI**) como
servicio **independiente y asíncrono**: la API nunca lo espera en el flujo de
una transacción.

| Endpoint | Descripción |
| --- | --- |
| `POST /internal/recommendations` | Genera una recomendación a partir del contexto transaccional (endpoints `async`, no bloquean el event loop) |
| `GET /health` | Healthcheck (usado por Docker) |
| `GET /metrics` | Métricas Prometheus `smartbancs_ai_*` (peticiones, recomendaciones, errores, latencia) |
| `GET /docs` | Documentación interactiva de FastAPI |

**Proveedores.** Si existe `OPENROUTER_API_KEY` consulta a **OpenRouter**
(`httpx.AsyncClient`); sin API key o ante un error del proveedor cae a un
**mock avanzado** (reglas heurísticas con `source="mock"`), por lo que la demo
funciona siempre.

**Desde la API.** `GET /recommendations?customerId={uuid}&limit=20` devuelve las
recomendaciones generadas de forma asíncrona para un cliente (persistidas tras
la transacción vía worker/outbox).

> Variables de entorno: `OPENROUTER_API_KEY`, `AI_MODEL` (por defecto
> `moonshotai/kimi-k2.6`), `AI_HTTP_TIMEOUT`, `LOG_LEVEL`.

## 8. Observabilidad

Se levanta con el mismo `docker compose` y queda integrado **Prometheus**
(métricas), **Tempo** (trazas OTLP) y **Loki** (logs JSON) en **Grafana**
(provisionada con datasources y dashboard **"SmartBancs – Observabilidad"**).

| Señal | Cómo se captura |
| --- | --- |
| Métricas | `http://localhost:8080/actuator/prometheus` (API) · `http://localhost:8081/metrics` (agente de IA) |
| Negocio | `smartbancs_transactions_total` · `smartbancs_transaction_duration_seconds` · `smartbancs_transaction_amount_*` |
| IA | `smartbancs_ai_requests_total` · `smartbancs_ai_recommendations_total` · `smartbancs_ai_duration_seconds` |
| Trazas y logs | Correlacionadas por `traceId`; en Grafana → Explore saltas del log a la traza en Tempo |
| Alertas SLO | 8 reglas en `observability/prometheus/rules.yml`: instancia caída, latencia, tasa de error, timeouts/esperas del pool Hikari, deadlocks y conexiones de PostgreSQL |

**Validado en vivo:** pico de carga simulado y un deadlock controlado que elevó
`pg_stat_database_deadlocks` y disparó la alerta **`Postgres_Deadlocks`** a
estado `firing` en Prometheus.

Detalle completo (arquitectura, instrumentación, queries y troubleshooting):
[`docs/OBSERVABILIDAD.md`](docs/OBSERVABILIDAD.md).

## 9. ETL / carga a data warehouse

Proceso que recibe un **lote de datos crudos (no homologados)** desde un
archivo, los limpia/estandariza y los ingesta como transacciones reales a
través de la API, además de dejar una **fact table** optimizada para análisis
y modelos de IA.

**Componentes:**

- `etl/sample-data/raw_transactions.csv` — lote de muestra *sin procesar*:
  montos con comas/decimales (`"1,250.50"`, `"750,00"`, `"$2,500.00"`), monedas
  en minúsculas/espaciadas, fechas en formatos mixtos, nulos, montos negativos,
  tipos desconocidos, filas duplicadas (idempotencia), cuentas inexistentes y un
  caso de fondos insuficientes.
- `etl/etl_transform.py` — ETL en **Python estándar** (sin dependencias
  externas), no añade servicios nuevos al despliegue.
- `POST /transactions/batch` — endpoint de ingesta que resuelve
  `accountNumber` → `accountId` y aplica las **mismas reglas** que
  `POST /transactions`, tolerando errores parciales (`accepted`/`rejected` por
  ítem).

**Pipeline:**

```text
EXTRACT   csv.DictReader + mapeo de columnas (headings sucios)
TRANSFORM Normalización: tipo · monto · ISO-4217 · fecha ISO · nulos
DEDUP     idempotencyKey determinístico (evita duplicados)
RESOLVE   GET /accounts → accountNumber → accountId
LOAD      POST /transactions/batch (chunks de 50)
ANALYZE   transactions_fact.csv (unidades menores enteras) + etl_report.json
```

**Uso:**

```bash
# Solo transformar/reportar (sin publicar nada):
python3 etl/etl_transform.py --input etl/sample-data/raw_transactions.csv --dry-run

# Carga real contra el stack local:
docker compose up -d            # postgres + api arriba
python3 etl/etl_transform.py --input etl/sample-data/raw_transactions.csv
```

**Salida** (en `etl/output/`, no versionado):

- `transactions_fact.csv` — un registro por **leg de ledger** (DEBIT/CREDIT)
  con `amount_minor_units` entero (listo para analítica/IA).
- `etl_report.json` — conteos (`raw_rows`, `valid`, `dropped`, `duplicates`,
  `sent`, `accepted`, `rejected`) y detalle de descartes/rechazos.

**Ejemplo real (lote de muestra):** 16 filas crudas → 11 válidas → 10
analizables (1 duplicada omitida) → 9 publicadas → **8 aceptadas** y 1
rechazada por regla de negocio (`insufficient funds`), con idempotencia
confirmada al re-ejecutar.

> **Nota de calidad de datos.** El CSV debe ser *estructuralmente* válido
> (comas internas entre comillas). La suciedad *semántica* (formatos, nulos,
> alias de tipos) la resuelve el ETL; la ambigüedad estructural no es
> recuperable sin el dialecto de origen.

## 10. Demo y evidencia

La API expone **Swagger UI** interactivo y un script genera **evidencia
reproducible** del funcionamiento:

```bash
./scripts/demo.sh
```

El script crea `scripts/evidencia/run-<timestamp>/` con 17 archivos
(`00-estado-stack.txt` … `16-resumen.txt`) que cubren CRUD, depósito,
transferencia con idempotencia, ledger, invariante contable, casos de error,
estado del stack y logs. `scripts/evidencia/` no se versiona (`.gitignore`).

Para una **presentación pública paso a paso** (journey completo clic-a-clic en
Swagger): [`docs/GUIA_DEMO_SWAGGER.md`](docs/GUIA_DEMO_SWAGGER.md).

> **Regla de negocio (DELETE).** `DELETE /customers/{id}` y
> `DELETE /accounts/{id}` devuelven `409 CONFLICT` cuando el recurso tiene
> dependencias (un cliente con cuentas; una cuenta con saldo o movimientos).
> La demo crea un cliente temporal sin cuentas y muestra los flujos
> `204` → `404` y el `409` como protección esperada.

## 11. Ejecución sin Docker (desarrollo)

Requiere **JDK 21** (API) y **Python 3.12** (agente de IA):

```bash
# 1) API (Java/Spring Boot)
./mvnw clean package -DskipTests     # construye domain + infra + api
java -jar smartbancs-api/target/smartbancs-api-0.1.0-SNAPSHOT.jar

# 2) Agente de IA (Python/FastAPI)
python3 -m venv .venv && . .venv/bin/activate
pip install -r ai-service/requirements.txt
uvicorn app.main:app --app-dir ai-service --host 0.0.0.0 --port 8081
```

## 12. Documentación técnica

| Documento | Contenido |
| --- | --- |
| [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) | Documento técnico: arquitectura, decisiones justificadas, integración con `Bancs`, manejo del modelo de IA y estado de implementación |
| [`docs/OBSERVABILIDAD.md`](docs/OBSERVABILIDAD.md) | Estrategia de observabilidad: SLIs/SLOs, alertas, verificación en vivo y troubleshooting |
| [`docs/INCIDENTE_Y_POST_MORTEM.md`](docs/INCIDENTE_Y_POST_MORTEM.md) | Incidente simulado, acciones inmediatas, escalamiento y plantilla de post mortem |
| [`docs/DECLARACION_IA.md`](docs/DECLARACION_IA.md) | Declaración de uso de IA (herramientas, componentes y verificación humana) |
| [`docs/GUIA_DEMO_SWAGGER.md`](docs/GUIA_DEMO_SWAGGER.md) | Guía de demostración paso a paso con Swagger |

## 13. Modelo de dominio

El núcleo de negocio se organiza por **contextos acotados**:

- **Cuentas (Accounts)** — saldo, estado y límites (una cuenta bloqueada no opera).
- **Transacciones (Transactions)** — transferencia atómica, idempotencia y asientos de ledger.
- **Recomendaciones (Recommendations)** — salida de IA generada de forma asíncrona.
- **Integración con Bancs** — patrón outbox y reconciliación.
- **Clientes (Customers)** — identidad y segmento.

## 14. Flujo de trabajo Git

- `main` — releases estables, solo mediante pull requests.
- `develop` — rama de integración.
- `feature/*` — trabajo aislado por entregable.
- [Conventional commits](https://www.conventionalcommits.org/) como estilo de
  mensajes de commit.

## 15. Licencia y autor

| | |
| --- | --- |
| **Licencia** | GPL-2.0 — ver [`LICENSE`](LICENSE) |
| **Autor** | David Malquin (DavCoder22) — candidato NextGen Engineering |