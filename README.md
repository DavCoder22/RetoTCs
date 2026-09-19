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
| Business core (3-layer architecture) | Next |
| Data access + PostgreSQL schema (Flyway) | Pending |
| AI async service | Pending |
| ETL / data warehouse load | Pending |
| Observability (metrics, logs, traces) | Pending |
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
└─ docs/                   # Architecture, ADRs, incident, post mortem, defense
```

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

## How to run the project

1. Open this folder in Visual Studio Code.
2. Accept the prompt to reopen the project inside the Dev Container.
3. Build the modules: `mvn clean compile`.

Detailed run, test and stop instructions are published in a later stage (documentation deliverable).

## License

GPL-2.0 — see [LICENSE](LICENSE).

## Author

David Malquin (DavCoder22) — NextGen Engineering candidate.