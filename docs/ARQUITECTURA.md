# Documento Técnico — SmartBancs App

> Entregable oficial del reto: **Arquitectura, decisiones técnicas, integración
> con Bancs, manejo del modelo de IA y respuesta al incidente simulado.**
> Este documento describe el *diseño* (teórico) y lo que está *implementado* y
> verificable en el repositorio (práctico).

---

## 1. Resumen ejecutivo

SmartBancs es una plataforma de procesamiento de transacciones financieras en
tiempo real con recomendaciones personalizadas por IA. El reto impone cuatro
restricciones de diseño:

1. Soportar picos de **10 000 transacciones por segundo**.
2. Integrarse con el core legado **Bancs** sin degradar su rendimiento.
3. Completar una transferencia en **menos de 2 segundos**.
4. Que la IA **nunca bloquee** el flujo transaccional principal.

La solución adoptada separa **tres frentes**: (a) una API transaccional
elástica y de baja latencia (Java 21 + Spring Boot), (b) una capa de
integración con Bancs basada en **outbox + reconciliación** (sin llamadas
síncronas al core), y (c) un servicio de IA **independiente y asíncrono**.
Todo el stack se levanta con un solo comando (`docker compose up`) y está
instrumentado para observabilidad (métricas, logs, trazas y alertas).

---

## 2. Requerimientos y métricas objetivo

| Requerimiento | Objetivo | SLI asociado |
| --- | --- | --- |
| Alta concurrencia | 10 000 tps | `smartbancs_transactions_total` (rate) |
| Transferencia < 2 s | p95 < 2 s | `smartbancs_transaction_duration_seconds{type="TRANSFER"}` |
| No degradar Bancs | 0 llamadas síncronas a Bancs | Outbox status en BD |
| IA no bloqueante | 0 ms en el camino crítico de la transacción | Trazas: span de IA nunca dentro del span de `/transactions` |

---

## 3. Arquitectura propuesta

```
                        ┌────────────────────  capa presentación  ────────────────────┐
  Clientes / Core-banking│   smartbancs-api :8080                                     │
                        │   Controllers (REST)  →  Services (reglas de negocio)        │
                        │   DTOs · Validation · GlobalExceptionHandler                │
                        └───────────────┬─────────────────────────────────────────────┘
                                        │ Spring (JPA / @Transactional)
                        ┌───────────────▼─────────────────────────────────────────────┐
                        │   smartbancs-infra  (Spring Data JPA + Flyway)              │
                        │   Entidades · Repositorios · Migraciones V1/V2              │
                        └───────────────┬─────────────────────────────────────────────┘
                                        │
                        ┌───────────────▼──────────────┐        ┌─────────────────────┐
                        │   PostgreSQL 16 (smartbancs) │        │   smartbancs-domain │
                        │   accounts · transactions · │        │   Entidades / enums  │
                        │   ledger_entries · outbox   │        │   (Java puro, sin        │
                        │   recommendations          │        │    dependencias)      │
                        └───────────────┬──────────────┘        └─────────────────────┘
                                        │
              ┌──────────────────────────┼──────────────────────────┐
              │                          │                          │
  ┌───────────▼────────┐  ┌──────────────▼─────────────┐  ┌────────▼─────────────┐
  │  Bancs (legado)    │  │  ETL / Warehouse (etl/)    │  │  ai-service :8081    │
  │  outbox relayer +  │  │  limpieza + fact table     │  │  recomendaciones     │
  │  reconciliación    │  │  para IA / análisis        │  │  (Python/FastAPI)   │
  └────────────────────┘  └────────────────────────────┘  └──────────────────────┘

      Observabilidad (transversal): Micrometer → Prometheus · Tempo · Loki · Grafana
```

**Capa de dominio limpia**: `smartbancs-domain` no conoce Spring ni JPA; las
reglas de cuenta (saldo, estado), transacción (doble partida, idempotencia) y
ledger viven allí. `smartbancs-infra` persiste, y `smartbancs-api` expone HTTP.
Esto permite razonar el negocio sin acoplarse a la infraestructura.

---

## 4. Decisiones técnicas y justificación

Cada decisión se justifica frente a **rendimiento, seguridad y escalabilidad**,
como exige el reto (stack tecnológico libre).

| # | Decisión | Justificación |
| --- | --- | --- |
| D1 | **Java 21 con virtual threads** (`spring.threads.virtual.enabled=true`) | Miles de peticiones concurrentes sin inflar hilos de OS: los hilos virtuales se suspenden en I/O (BD, red) con coste casi nulo. Es la base para sostener picos altos con una footprint baja. |
| D2 | **Spring Boot 3 + Spring Data JPA sobre PostgreSQL 16** | Maduro, gestionado por Spring y con capacidades transaccionales ACID críticas para dinero (viaje de fondos). PostgreSQL: `SELECT ... FOR UPDATE` (proxy de pesimismo a nivel de fila), versionado (optimismo) e índices únicos de idempotencia. |
| D3 | **Módulos Maven: domain / infra / api / ai-service** | Separación de responsabilidades que permite evolucionar capas, probar el dominio de forma aislada y desplegar el AI service de forma independiente. |
| D4 | **Concurrencia en saldos: `findByIdForUpdate` (lock pesimista por fila) + `@Version` (optimista)** | Evita **condiciones de carrera** y **doble gasto**: dos transferencias concurrentes sobre la misma cuenta se serializan en la fila, mientras el resto de cuentas siguen en paralelo. El `@Version` protege contra actualizaciones perdidas. |
| D5 | **Idempotencia por `idempotency_key` (columna única)** | Reintentos del cliente o del mensajero no duplican operaciones: requisito habitual en pagos y lo que permite reintentar sin miedo. |
| D6 | **Patrón outbox** para integrar con Bancs | El core legado **no puede recibir tráfico alto y síncrono**. Las transacciones se confirman solo contra PostgreSQL (rápido), y un *relayer* entrega los eventos a Bancs en lotes controlados. Aísla SmartBancs de la disponibilidad de Bancs. |
| D7 | **Reconciliación programada (batch nocturna / horaria)** | Si un evento de outbox no se entrega, la reconciliación de saldos detecta la desviación y la corrige. Da **consistencia eventual** garantizada sin necesitar síncrono. |
| D8 | **IA como servicio separado y asíncrono (`ai-service`)** | El agente de IA es **independiente en Python/FastAPI** (antes Java): consume OpenRouter (opcional) y cae a un mock avanzado si no hay API key o el proveedor falla. El flujo de `/transactions` **nunca** espera a la IA; si la IA falla o va lenta, la transacción no se afecta. |
| D9 | **Observabilidad con Micrometer → Prometheus/Tempo/Loki** | Instrumentación oficial de Spring Boot (sin agentes), visible en Grafana con dashboards y alertas por SLO. Cubre métricas, logs JSON con `traceId` y trazas OTLP. |
| D10 | **Docker Compose como IaC del entorno** y **Terraform para AWS** | Un comando levanta BD + API + AI + observabilidad (entorno de desarrollo portable). Terraform prepara el despliegue en AWS con OIDC para CI. |
| D11 | **Actuator + `build-info`** | Healthchecks con `curl` en los contenedores, `/actuator/health` detallado y versión de build visible en métricas/info → trazabilidad despliegue-incidente. |

### Seguridad (resumen transversal)

- Credenciales por variables de entorno (`.env` no versionado, `.env.example` sí).
- Validación de entrada (`@Valid`, DTOs) y manejo central de errores con
  `ProblemDetail`.
- No se registran datos sensibles; los logs JSON son estructurados y auditable
  el nivel (`loggers` vía actuator).
- Healthchecks sin exponer secretos.

---

## 5. Integración con Bancs (estrategia de sincronización)

### 5.1 Flujo de datos (teórico)

```
cliente → /transactions (API) ──► PostgreSQL (commit local, tx < 50 ms)
                                     │
                                     ▼
                              outbox_events (INSERT, mismo tx)
                                     │
                   relayer (worker, lotes de N, throttling)
                                     │  POST /bancs/v1/settle  (lotes, idempotente)
                                     ▼
                              Bancs (legacy core)
                                     │
                    respuesta + confirmación ──► actualizar estado del outbox
                                     │
                ┌────────────────────┴───────────────────┐
                ▼                                         ▼
     Reconciliación diaria            (si falla/rechaza) cola de reintento
     compara saldos SI ─ Bancs        con backoff + dead-letter + alerta
```

1. **Confirmación local primero**: la transferencia se asienta en PostgreSQL
   (transacción + ledger + evento de outbox) **en un solo commit**. El SLO de
   < 2 s se cumple desconectado de la latencia de Bancs.
2. **Relayer con lotes y throttling**: un worker lee `outbox_events` pendientes
   y los envía a Bancs en lotes (p. ej. 100) con límite de QPS configurado,
   para no saturar el core. No hay llamadas síncronas desde la API.
3. **Idempotencia en Bancs**: cada evento lleva `outbox_event_id`; Bancs
   deduplica. Eso permite reintentar sin crear dobles asientos.
4. **Reconciliación**: job periódico (nocturno) que compara el saldo de
   SmartBancs contra el reporte de saldos de Bancs y emite asientos de ajuste /
   alertas. Es la red de seguridad ante pérdida de mensajes.
5. **Backpressure y degradación**: si Bancs no responde, el relayer acumula
   (retention del outbox) y dispara alerta (`Postgres_*`/logs), sin bloquear al
   cliente final.

### 5.2 Esquema de soporte (implementado)

La tabla `outbox_events` existe en el esquema (`V1__init_schema.sql`) con
`payload`, `type`, `status` (`PENDING/...`), `created_at`; es el contrato que
facilitará el relayer de Bancs. El modelo y los repositorios
(`OutboxEventJpaRepository`) quedan listos.

---

## 6. Manejo del modelo de IA (ciclo de vida en producción)

### 6.1 Alimentación del modelo

1. **ETL / Warehouse** (implementado en `etl/`): el lote transaccional crudo
   se limpia (nulos, formatos mixtos de fecha/moneda/monto), se estandariza y
   se estructura en un almacén analítico (`etl/output/transactions_fact.csv`,
   un registro por leg de ledger, unidades menores enteras) optimizado para
   consultas y para *feature store* del modelo. La ingesta se publica por la
   API (`POST /transactions/batch`), que resuelve `accountNumber`→`accountId`
   y reutiliza las reglas de negocio de `/transactions`, tolerando errores
   parciales por ítem (`accepted`/`rejected`). Más detalle en la sección ETL
   del README.
2. **Feature store**: variables (frecuencia de transacciones, saldo promedio,
   segmento, antigüedad, tasa de rechazo) servidas de forma consistente entre
   *inference* y *training*.
3. **Retraining**: pipeline programado (batch semanal/mensual) que regenera el
   modelo con los nuevos datos etiquetados (recomendaciones = lo que el cliente
   aceptó).

### 6.2 Monitoreo de *data drift* y calidad

| Señal | Cómo se mide | Acción |
| --- | --- | --- |
| Drift de características (*feature drift*) | Test de Kolmogorov–Smirnov / PSI entre distribución de entrenamiento y producción, por segmento | Alerta y reintreno |
| Drift de predicción | PSI de las recomendaciones generadas | Revisar umbrales/segmentación |
| Drift de etiquetas | Curva de aceptación/efectividad vs predicción | Recalibración |
| Residuo / calidad | Métricas de negocio (tasa de aceptación) | Eventualmente *canary* del modelo |

Estas señales son **métricas** (expuestas al mismo stack de observabilidad) y
disparan **alertas** cuando el drift supera un umbral, activando un reintreno
controlado (blue-green) con validación offline previa.

### 6.3 Gestión de recursos

- **Inferencia**: CPU (o GPU si el latencia/volumen lo exige), con autoescala
  horizontal del `ai-service`; cola de mensajes para balancear picos.
- **Entrenamiento**: GPU en jobs aislados (sin mezclar con el servicio).
- **Guardrails**: límites de concurrencia en el `ai-service`, timeout de
  inferencia y *fallback* (recomendación por reglas) si la IA no responde.
- **Never blocking**: la llamada a IA dispara el event y la transacción
  responde sin esperarla (ver sección Observabilidad: trace debe mostrar el
  span de IA fuera del camino crítico).

---

## 7. Respuesta al incidente simulado (resumen)

El escenario (pico de quincena, latencia alta, timeouts de BD y deadlocks) se
aborda en [`docs/INCIDENTE_Y_POST_MORTEM.md`](INCIDENTE_Y_POST_MORTEM.md). En
una línea: la observabilidad implementada permite **detectar** el evento
(`hikaricp_connections_timeout_total`, `pg_stat_database_deadlocks`),
**localizar** la consulta (`pg_stat_activity`, traces en Tempo) y **responder**
con acciones inmediatas y preventivas documentadas.

---

## 8. Estado de implementación (lo verificable en el repo)

| Componente | Estado | Dónde |
| --- | --- | --- |
| Modelo de dominio (3 capas) | ✅ Implementado | `smartbancs-domain`, `smartbancs-infra`, `smartbancs-api` |
| REST CRUD + transferencias + ledger + idempotencia | ✅ Implementado | `smartbancs-api/controller` |
| Concurrencia y race conditions (FOR UPDATE + version) | ✅ Implementado | `TransactionService` |
| Esquema DDL/DML | ✅ Flyway | `smartbancs-infra/src/main/resources/db/migration/V1|V2` |
| IaC entorno (Docker Compose) | ✅ Implementado | `docker-compose.yml` |
| IaC AWS (Terraform + OIDC) | ✅ Scaffolding | `terraform/` + `.github/workflows/terraform-ci.yml` |
| Patrón outbox (tabla/repositorio) | ✅ Tabla/repo listos; relayer pendiente | `outbox_events`, `OutboxEventJpaRepository` |
| ETL / transformación + fact table | ✅ Implementado | `etl/` + `POST /transactions/batch` |
| Agente de IA (Python/FastAPI, asíncrono, OpenRouter + mock) | ✅ Funcional y no bloqueante | `ai-service` |
| Observabilidad (métricas/logs/trazas/alertas) | ✅ Implementado y verificado | `observability/` + `docs/OBSERVABILIDAD.md` |
| Incidente simulado + post mortem | ✅ Documentado | `docs/INCIDENTE_Y_POST_MORTEM.md` |
| Declaración de uso de IA | ✅ Documentado | `docs/DECLARACION_IA.md` |