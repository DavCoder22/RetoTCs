# SmartBancs App

Reto técnico de ingeniería para el proceso **NextGen Engineering**.

SmartBancs es una plataforma financiera que procesa **transacciones en tiempo
real** y ofrece **recomendaciones financieras personalizadas** impulsadas por
IA, integrada con el core bancario legado (`Bancs`).

## Resumen del reto

- Soportar picos altos de transacciones, por ejemplo **10 000 transacciones por segundo**.
- Integrarse con el core bancario legado (`Bancs`) **sin degradar su rendimiento**.
- Completar una transferencia en **menos de 2 segundos**.
- Las recomendaciones de IA **nunca deben bloquear** el flujo transaccional principal.

Entregables requeridos: documento técnico, **MVP** ejecutable alojado en un
repositorio Git, instrucciones de ejecución, evidencia y declaración de uso de IA.

## Estado del entregable

| Etapa | Estado |
| --- | --- |
| Bootstrap del proyecto (devcontainer, estructura, modelo de dominio) | ✅ Hecho |
| MVP local con Docker Compose (PostgreSQL + API + IA + observabilidad) | ✅ Hecho |
| Núcleo de negocio (arquitectura en 3 capas) | ✅ Hecho |
| Acceso a datos + esquema PostgreSQL (Flyway) | ✅ Hecho |
| REST CRUD: customers, accounts, transactions + transferencias/ledger | ✅ Hecho |
| Idempotencia, concurrencia (`FOR UPDATE`/`@Version`) e invariante contable | ✅ Hecho |
| ETL / carga a data warehouse | ✅ Hecho |
| Ingesta por lotes (`POST /transactions/batch`) | ✅ Hecho |
| Servicio de IA asíncrono | 🟡 Pendiente (mock funcional) |
| Observabilidad (métricas · logs · trazas · alertas) | ✅ Hecho |
| Incidente simulado + post mortem | ✅ Hecho |
| Documentación final + evidencia | ✅ Hecho |

## Stack tecnológico

- **Java 21** (virtual threads) y **Spring Boot 3** / Maven (multimódulo: domain, infra, api).
- **PostgreSQL 16** (Flyway para el esquema).
- **Docker** y **Docker Compose** (entorno local como infraestructura como código).
- **Devcontainer**: entorno de desarrollo reproducible (JDK 21 + Maven + Docker).
- **Observabilidad**: Prometheus, Tempo (OTLP), Loki + Promtail y Grafana provisionada.
- **Terraform + GitHub Actions (OIDC)**: scaffolding de despliegue en AWS.

## Arquitectura

```
  clientes/batch ──► smartbancs-api :8080  (REST, virtual threads, reglas de negocio)
                        │ Spring (JPA / @Transactional)
                        ▼
               smartbancs-infra  (Spring Data JPA + Flyway)
                        ▼
               PostgreSQL 16  (accounts · transactions · ledger · outbox)
                        │
      ┌─────────────────┼──────────────────┐
      ▼                 ▼                  ▼
   Bancs (legado)   ETL → fact table   ai-service :8081 (asíncrono, no bloqueante)

   Observabilidad transversal: Micrometer → Prometheus · Tempo · Loki · Grafana
```

Decisiones clave (patrón **outbox** para integrar con Bancs sin tráfico
síncrono, locks ordenados por fila + versión optimista para evitar doble gasto,
idempotencia, "IA nunca en el camino crítico") y el detalle completo de la
arquitectura: [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md).

## Estructura del repositorio

```
RetoTCs/
├─ .devcontainer/          # Entorno de desarrollo reproducible (JDK 21 + Maven + Docker)
├─ smartbancs-api/         # App Boot: controllers, DTOs, servicios, manejo de errores, métricas
├─ smartbancs-domain/      # Entidades, enums y reglas de negocio (Java puro)
├─ smartbancs-infra/       # Acceso a datos, Flyway, repositorios, clientes IA/Bancs
├─ ai-service/             # Servicio de recomendaciones IA (mock), independiente
├─ etl/                    # ETL: transformación + ingesta por lote + fact table (ver sección ETL)
├─ scripts/                # demo.sh (evidencia reproducible), aws-configure.sh
├─ docs/                   # Documentación técnica (en español)
├─ observability/          # Prometheus, Tempo, Loki, Promtail + Grafana (provisionada)
├─ terraform/              # IaC para AWS + workflow OIDC
└─ docker-compose.yml      # MVP local: PostgreSQL 16 + api + ai-service + observabilidad
```

## Cómo ejecutar el proyecto (MVP local con Docker)

Prerrequisito: **Docker** con el plugin de Compose.

1. (Opcional) Copia `.env.example` a `.env` y define tus propias credenciales.
2. Construye y levanta el stack:

   ```bash
   docker compose up --build -d
   ```

3. Verifica el estado de salud:

   ```bash
   docker compose ps
   ```

   Debe quedar **healthy** (BD, api, ai-service y los servicios de observabilidad).

4. Comprueba los endpoints:

   | Servicio | URL |
   | --- | --- |
   | API (health): | `http://localhost:8080/actuator/health` |
   | Swagger UI (API): | `http://localhost:8080/swagger-ui.html` |
   | OpenAPI spec: | `http://localhost:8080/v3/api-docs` |
   | AI service (health): | `http://localhost:8081/actuator/health` |
   | PostgreSQL: | `localhost:5432` (db `smartbancs`, usuario `smartbancs`) |
   | Grafana: | `http://localhost:3333` (`admin`/`admin`, configurable) |
   | Prometheus: | `http://localhost:9090` |
   | Tempo: | `http://localhost:3200` |
   | Loki: | `http://localhost:3100` |
   | postgres-exporter: | `http://localhost:9187/metrics` |

5. Detén el stack:

   ```bash
   docker compose down
   ```

   Para eliminar también los volúmenes de datos: `docker compose down -v`.

> **Docker rootless (Linux):** si `promtail` no encuentra el daemon, exporta las
> rutas antes de `docker compose up`:
> `DOCKER_SOCKET=/run/user/$UID/docker.sock` y
> `DOCKER_CONTAINERS_DIR=$HOME/.local/share/docker/containers`.

## Observabilidad (métricas · logs · trazas · alertas)

El stack de observabilidad se levanta con el mismo `docker compose` e integra
**Prometheus** (métricas), **Tempo** (trazas OTLP) y **Loki** (logs JSON) con
**Grafana** provisionada automáticamente (datasources + dashboard "SmartBancs –
Observabilidad").

- **Métricas**: `http://localhost:8080/actuator/prometheus` (api) y
  `http://localhost:8081/actuator/prometheus` (IA). Cada transacción expone
  `smartbancs_transactions_total`, `smartbancs_transaction_duration_seconds`,
  `smartbancs_transaction_amount_*`.
- **Trazas y logs**: correlacionados por `traceId`; en Grafana → Explore puedes
  saltar del log a la traza completa en Tempo.
- **Alertas por SLO** (reglas PromQL en `observability/prometheus/rules.yml`):
  instancia caída, latencia de transferencia, tasa de error (HTTP y de
  transacciones), timeouts/esperas del pool Hikari, **deadlocks** y conexiones
  altas de PostgreSQL.
- **Validado en vivo**: pico simulado de carga y deadlock controlado →
  contador `pg_stat_database_deadlocks` incrementado y alerta
  `Postgres_Deadlocks` en estado **firing** en Prometheus.

Detalle completo (arquitectura, instrumentación, queries, troubleshooting):
[`docs/OBSERVABILIDAD.md`](docs/OBSERVABILIDAD.md).

## ETL / carga a data warehouse

Proceso que recibe un **lote de datos crudos (no homologados)** desde un archivo,
los limpia/estandariza y los ingesta como transacciones reales a través de la
API, dejando además una **fact table** optimizada para análisis / modelos de IA.

### Componentes

- `etl/sample-data/raw_transactions.csv` — lote de muestra *sin procesar*:
  montos con comas/decimales (`"1,250.50"`, `"750,00"`, `"$2,500.00"`), monedas en
  minúsculas/espaciadas, fechas en formatos mixtos, nulos, montos negativos, tipos
  desconocidos, filas duplicadas (idempotencia), cuentas inexistentes y un caso de
  fondos insuficientes.
- `etl/etl_transform.py` — ETL en **Python estándar** (sin dependencias externas),
  por lo que no afecta el despliegue Terraform (no es un servicio nuevo).
- `POST /transactions/batch` en `smartbancs-api` — endpoint de ingesta que resuelve
  cada `accountNumber` al `accountId` interno y aplica las **mismas reglas** que
  `POST /transactions` (validación, saldo, cuenta activa, idempotencia por ítem),
  tolerando errores parciales: cada ítem se reporta como `accepted`/`rejected`.

### Pipeline

```
EXTRACT  csv.DictReader + mapeo de columnas (headings sucios)
  -> TRANSFORM  normalización: tipo, monto, ISO-4217, fecha ISO, nulos
  -> DEDUP      idempotencyKey determinístico (evita duplicados)
  -> RESOLVE    GET /accounts -> accountNumber -> accountId
  -> LOAD       POST /transactions/batch (chunks de 50)
  -> ANALYZE    transactions_fact.csv (unidades menores enteras) + etl_report.json
```

### Uso

```bash
# Solo transformar/reportar (sin publicar nada):
python3 etl/etl_transform.py --input etl/sample-data/raw_transactions.csv --dry-run

# Carga real contra el stack local:
docker compose up -d            # postgres + api arriba
python3 etl/etl_transform.py --input etl/sample-data/raw_transactions.csv
```

Salida (en `etl/output/`, no versionado):

- `transactions_fact.csv` — un registro por **leg de ledger** (DEBIT/CREDIT) con
  `amount_minor_units` entero (analítica/IA directa).
- `etl_report.json` — conteos (`raw_rows`, `valid`, `dropped`, `duplicates`,
  `sent`, `accepted`, `rejected`), detalle de descartes/rechazos y rutas de salida.

Ejemplo de ejecución real (lote de muestra): 16 filas crudas → 11 válidas →
10 analizables (1 duplicada omitida) → 9 publicadas → **8 aceptadas** y 1 rechazada
por regla de negocio (`insufficient funds`), con idempotencia confirmada al re-ejecutar.

> **Nota de calidad de datos:** el CSV debe ser *estructuralmente* válido (comas
> internas entre comillas). La "suciedad" semántica (formatos, nulos, alias de tipos)
> la resuelve el ETL; la ambigüedad estructural no es recuperable sin el dialecto
> de origen.

## Demo y evidencia

La API expone **Swagger UI** interactivo y un script genera evidencia reproducible
del funcionamiento:

```bash
./scripts/demo.sh
```

El script crea `scripts/evidencia/run-<timestamp>/` con 17 archivos
(`00-estado-stack.txt` … `16-resumen.txt`) que cubren CRUD, depósito,
transferencia con idempotencia, ledger, invariante contable, casos de error,
estado del stack y logs. `scripts/evidencia/` no se versiona (`.gitignore`).

Para una **presentación pública paso a paso** (journey completo clic-a-clic en
Swagger): [`docs/GUIA_DEMO_SWAGGER.md`](docs/GUIA_DEMO_SWAGGER.md).

> **Regla de negocio (DELETE):** `DELETE /customers/{id}` y
> `DELETE /accounts/{id}` devuelven `409 CONFLICT` cuando el recurso tiene
> dependencias: un cliente no se borra si todavía tiene cuentas, y una cuenta no
> se borra si tiene saldo distinto de 0 o historial de movimientos. La demo crea
> un cliente temporal sin cuentas para mostrar el flujo `204` → `404`.

## Ejecución sin Docker (desarrollo)

Requiere **JDK 21**:

```bash
./mvnw clean package -DskipTests     # construye todos los módulos
java -jar smartbancs-api/target/smartbancs-api-0.1.0-SNAPSHOT.jar
java -jar ai-service/target/ai-service-0.1.0-SNAPSHOT.jar
```

## Documentación técnica (en español)

| Documento | Contenido |
| --- | --- |
| [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) | Documento técnico: arquitectura, decisiones justificadas, integración con Bancs, manejo del modelo de IA, estado de implementación |
| [`docs/OBSERVABILIDAD.md`](docs/OBSERVABILIDAD.md) | Estrategia de observabilidad: SLIs/SLOs, alertas, verificación en vivo y troubleshooting |
| [`docs/INCIDENTE_Y_POST_MORTEM.md`](docs/INCIDENTE_Y_POST_MORTEM.md) | Incidente simulado, acciones inmediatas, escalamiento y plantilla de post mortem |
| [`docs/DECLARACION_IA.md`](docs/DECLARACION_IA.md) | Declaración de uso de IA (herramientas y componentes donde se aplicó) |
| [`docs/GUIA_DEMO_SWAGGER.md`](docs/GUIA_DEMO_SWAGGER.md) | Guía de demostración paso a paso con Swagger |

## Modelo de dominio

El núcleo de negocio se organiza por contextos acotados:

- **Accounts** — saldo, estado y límites (p. ej. una cuenta bloqueada no opera).
- **Transactions** — transferencia atómica, idempotencia, asientos de ledger.
- **Recommendations** — salida de IA generada de forma asíncrona.
- **Integración con Bancs** — patrón outbox y reconciliación.
- **Customer** — identidad y segmento.

## Flujo de trabajo Git

- `main` — releases estables, solo por pull requests.
- `develop` — rama de integración.
- `feature/*` — trabajo aislado por entregable.
- [Conventional commits](https://www.conventionalcommits.org/) (o consenso del equipo).

## Licencia

GPL-2.0 — ver [LICENSE](LICENSE).

## Autor

David Malquin (DavCoder22) — candidato NextGen Engineering.