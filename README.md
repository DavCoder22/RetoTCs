# SmartBancs App

Technical challenge for the **NextGen Engineering** process.

SmartBancs is a financial platform that processes **real-time transactions** and provides **personalized financial recommendations** powered by AI.

## Challenge summary

- Support high transaction peaks, for example **10,000 transactions per second**.
- Integrate with the legacy banking core (`Bancs`) without degrading its performance.
- Complete a transfer in less than **2 seconds**.
- AI recommendations must **never block** the main transaction flow.

Required deliverables: a technical document, a runnable **MVP** hosted in a Git repository, run instructions, evidence, and an AI usage declaration.

## Status

| Stage | State |
| --- | --- |
| Project bootstrap (devcontainer, structure, domain model) | Done |
| Local MVP via Docker Compose (PostgreSQL + API + AI service) | Done |
| Business core (3-layer architecture) | Done |
| Data access + PostgreSQL schema (Flyway) | Done |
| REST CRUD: customers, accounts, transactions + transfers/ledger | Done |
| AI async service | Pending |
| ETL / data warehouse load | Pending |
| Observability (metrics, logs, traces) | Done |
| Incident simulation + post mortem | Pending |
| Final documentation + evidence | Pending |

## Tech stack

- **Java 21** (virtual threads) and **Spring Boot 3** / Maven
- **PostgreSQL 16** (planned)
- **Docker** and **Docker Compose** (infrastructure as code)
- **Devcontainer**: reproducible development environment

## Repository structure

```
RetoTCs/
├─ .devcontainer/          # JDK 21 + Maven + Docker development environment
├─ smartbancs-api/         # Boot app: controllers, DTOs, security, error handling
├─ smartbancs-domain/      # Entities, enums and business rules (pure Java)
├─ smartbancs-infra/       # Data access, Flyway, ai-client, bancs-client, ETL
├─ ai-service/             # AI recommendation service (mock), standalone
├─ db/                     # Raw DDL / DML scripts (challenge requirement)
├─ scripts/                # Load tests, seed data, evidence
├─ docs/                   # Architecture, ADRs, incident, post mortem, defense
├─ observability/          # Prometheus, Tempo, Loki, Promtail + Grafana (provisioned)
└─ docker-compose.yml      # Local MVP: PostgreSQL 16 + api + ai-service + observability stack
```

## How to run the project (local MVP with Docker)

Prerequisites: **Docker** with the Compose plugin.

1. (Optional) Copy `.env.example` to `.env` and set your own credentials.
2. Build and start the stack:

   ```bash
   docker compose up --build -d
   ```

3. Verify health:

   ```bash
   docker compose ps
   ```

4. Check the endpoints:

   - API: `http://localhost:8080/health` (actuator: `http://localhost:8080/actuator/health`)
   - AI service: `http://localhost:8081/actuator/health`
   - PostgreSQL: `localhost:5432` (db `smartbancs`, user `smartbancs`)

5. Stop the stack:

   ```bash
   docker compose down
   ```

   Remove data volumes too with `docker compose down -v`.

> **Docker rootless (Linux):** si `promtail` no encuentra el daemon, exporta las
> rutas antes de `docker compose up`:
> `DOCKER_SOCKET=/run/user/$UID/docker.sock` y
> `DOCKER_CONTAINERS_DIR=$HOME/.local/share/docker/containers`.

## Observabilidad (metrics · logs · traces)

El stack de observabilidad se levanta con el mismo `docker compose` y queda
lista la integración de **Prometheus** (métricas), **Tempo** (traces OTLP) y
**Loki** (logs JSON) con **Grafana** ya provisionada:

- Grafana (dashboard "SmartBancs - Observabilidad"): `http://localhost:3333` — usuario `admin`, contraseña `admin` (configurable vía `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`). Si `3333` está ocupado, cambia `GRAFANA_HOST_PORT`.
- Prometheus: `http://localhost:9090` · Tempo: `http://localhost:3200` · Loki: `http://localhost:3100`
- Métricas: `http://localhost:8080/actuator/prometheus` y `http://localhost:8081/actuator/prometheus`
- Cada transacción expone métricas de negocio (`smartbancs_transactions_total`, `smartbancs_transaction_duration_seconds`, `smartbancs_transaction_amount_*`), trazas y logs correlacionados por `traceId`.

Detalle completo (arquitectura, instrumentación, queries, verificación y
troubleshooting): [`docs/OBSERVABILIDAD.md`](docs/OBSERVABILIDAD.md).

## Demo y evidencia

La API expone **Swagger UI** interactivo (misma experiencia que FastAPI) y un script genera evidencia reproducible del funcionamiento.

- **Swagger UI (API):** `http://localhost:8080/swagger-ui.html`
- **OpenAPI spec:** `http://localhost:8080/v3/api-docs`
- **Swagger UI (AI service):** `http://localhost:8081/swagger-ui.html`

Generar la evidencia de la demo completa (CRUD, depósito, transferencia con idempotencia, ledger, invariante contable, casos de error, estado del stack y logs):

```bash
./scripts/demo.sh
```

El script crea `scripts/evidencia/run-<timestamp>/` con 17 archivos (`00-estado-stack.txt` … `16-resumen.txt`). La carpeta `scripts/evidencia/` no se versiona (`.gitignore`).

Para una **presentación pública paso a paso** (journey completo clic-a-clic en Swagger, enfocado en funciones y capas): [`docs/GUIA_DEMO_SWAGGER.md`](docs/GUIA_DEMO_SWAGGER.md).

> **Regla de negocio (DELETE):** `DELETE /customers/{id}` y `DELETE /accounts/{id}` devuelven `409 CONFLICT` cuando el recurso tiene dependencias: un cliente no se borra si todavía tiene cuentas, y una cuenta no se borra si tiene saldo distinto de 0 o historial de movimientos. Para demostrar el borrado, la demo crea un cliente temporal sin cuentas (flujo `204` → `404`) y muestra el `409` como protección esperada en `04-crear-cliente.txt`.

## Run without Docker (development)

Requires **JDK 21**:

```bash
./mvnw clean package -DskipTests     # build all modules
java -jar smartbancs-api/target/smartbancs-api-0.1.0-SNAPSHOT.jar
java -jar ai-service/target/ai-service-0.1.0-SNAPSHOT.jar
```

Detailed test and stop instructions are published in a later stage (documentation deliverable).

## Domain model

The business core is organized by bounded contexts:

- **Accounts** — balance, status, limits (e.g. a frozen account cannot operate).
- **Transactions** — atomic transfer, idempotency, ledger entries.
- **Recommendations** — AI output generated asynchronously.
- **Integration with Bancs** — outbox and reconciliation pattern.
- **Customer** — identity and segment.

## Git workflow

- `main` — stable releases, merged only through pull requests.
- `develop` — integration branch.
- `feature/*` — isolated work per deliverable.
- Conventional commits in English (for example `feat(core): add transaction entity`).

## License

GPL-2.0 — see [LICENSE](LICENSE).

## Author

David Malquin (DavCoder22) — NextGen Engineering candidate.