# Observabilidad (metrics · logs · traces)

> Implementación del entregable **Observability (metrics, logs, traces)** del
> reto. El stack se levanta con el mismo `docker compose` y no requiere
> agentes externos: la **API** (`smartbancs-api`, Spring Boot) se auto-instrumenta
> con **Micrometer** y exporta a **Prometheus**, **Tempo** y **Loki**; el
> **agente de IA** (`ai-service`, Python/FastAPI) expone sus propias métricas en
> `/metrics`.

## Arquitectura e integración

```
                        ┌──────────────────────────────────────────────────┐
                        │                    smartbancs                      │
   Navegador/ops ------ │  │  api:8080 │  ai-service:8081                 │
                        │  │   │  │     │      │                          │
                        │  │  (API) /actuator/prometheus · (IA) /metrics   │
                        │  │  OTLP HTTP (trazas)  ──► tempo:4318 ──► Tempo│
                        │  │  JSON logs (stdout)  ──► promtail ──► Loki    │
                        │  └──────────────────────────────────────────────┘
                                    │ scrape :9090/targets
                        ┌───────────▼───────────┐   datasources
                        │   Prometheus:9090     ├──────►  Grafana:3333
                        └───────────────────────┘
```

Las piezas nuevas se agrupan en la carpeta **`observability/`**:

| Servicio | Imagen | Puerto host | Rol |
| --- | --- | --- | --- |
| `prometheus` | `prom/prometheus:v2.53.1` | `9090` | Almacena y consulta métricas (scraping de `/actuator/prometheus` y `/metrics`) |
| `tempo` | `grafana/tempo:2.6.1` | `3200` (UI/API), `4317` gRPC, `4318` HTTP | Receptor **OTLP** y almacén de traces |
| `loki` | `grafana/loki:3.3.2` | `3100` | Almacén de logs (retention 7 días) |
| `promtail` | `grafana/promtail:3.3.2` | — (interno) | Descubre contenedores `smartbancs-*` y envía sus logs JSON a Loki |
| `postgres-exporter` | `prometheuscommunity/postgres-exporter:v0.15.0` | `9187` | Métricas de PostgreSQL: deadlocks, conexiones, committed/rolled-back |
| `grafana` | `grafana/grafana:11.4.0` | `3333` | Dashboards y Explore (Prometheus + Tempo + Loki) |

Grafana se provisiona automáticamente (datasources + dashboard) desde
`observability/grafana/provisioning/` y `observability/grafana/dashboards/`.

## Instrumentación de las aplicaciones

La instrumentación descrita corresponde a `smartbancs-api` (Spring Boot 3 +
Micrometer). El **agente de IA** (`ai-service`) quedó en **Python/FastAPI**:
expone `GET /health`, `GET /docs` y `GET /metrics`, con métricas propias
`smartbancs_ai_*` (peticiones, recomendaciones por fuente/categoría, errores de
validación, latencia). Las trazas OTLP actuales provienen de la API (incluida
la llamada API → IA dentro de su trace):

1. **Dependencias** (en los POM de `smartbancs-api`):
   - `micrometer-registry-prometheus` — expone `/actuator/prometheus`.
   - `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` — trazas
     OTLP a Tempo.
   - `logstash-logback-encoder` — logs JSON estructurados en stdout.
2. **Configuración** (`application.yml`):
   - Actuator expone `health, info, metrics, prometheus, loggers`.
   - `management.tracing.sampling.probability: 1.0` (configurable vía
     `TRACING_SAMPLING_PROBABILITY`; muestra completa, en producción se baja).
   - `management.otlp.tracing.endpoint` (configurable vía
     `OTLP_TRACING_ENDPOINT`, apunta a `http://tempo:4318/v1/traces` en Docker).
   - Tag `application` en todas las métricas para separar servicios en Grafana.
3. **Logs JSON** (`logback-spring.xml`): encoder Logstash con `service.name`,
   MDC incluida y `traceId` promovido a campo raíz → permite correlacionar un
   log con su trace en Tempo.
4. **Build info**: el plugin Spring Boot genera `META-INF/build-info.properties`
   (`/actuator/info` muestra versión y timestamp del build).

### Métricas de negocio (`TransactionMetrics`)

Clase `com.smartbancs.api.service.TransactionMetrics` registra métricas por
cada transacción procesada en `TransactionService.create`:

| Métrica | Tipo | Tags | Descripción |
| --- | --- | --- | --- |
| `smartbancs_transactions_total` | Counter | `type` (DEPOSIT/WITHDRAWAL/PAYMENT/TRANSFER), `outcome` (success/error/idempotent) | Transacciones por tipo y resultado |
| `smartbancs_transaction_duration_seconds` | Timer/histogram | `type` | Latencia de procesamiento |
| `smartbancs_transaction_amount_*` | DistributionSummary | `type` | Montos (sum/count/max por tipo) |

Las métricas del framework ya disponibles incluyen `http_server_requests_*`
(REST), `jvm_*`, `process_*`, `hikaricp_*` (pool JDBC), entre otras.

### Registrar una traza en logs (correlación)

`GlobalExceptionHandler` ahora loguea un **WARN** con el motivo del fallo; como
la petición viaja dentro de un span, el log JSON incluye el `traceId` y se puede
saltar del log en Grafana/Loki a la traza completa en Tempo.

## Cómo levantar

```bash
docker compose up --build -d
```

> **Docker rootless** (Linux): el socket y la carpeta de contenedores no están en
> las rutas por defecto. Si promtail no encuentra el daemon, exporta antes:
> ```bash
> export DOCKER_SOCKET=/run/user/$UID/docker.sock
> export DOCKER_CONTAINERS_DIR=$HOME/.local/share/docker/containers
> docker compose up -d
> ```
> En Docker Desktop / Docker rootful no hace falta nada extra.
>
> **Egreso de contenedores (rootless + kernel reciente):** si los servicios
> resuelven DNS pero todo TCP externo muere en timeout (p. ej. el agente de IA no
> llega a OpenRouter y cae a mock), es `slirp4netns` incompatible con tu kernel.
> Cambia el driver rootless a **pasta** (ver paso a paso en el README §7
> "Docker rootless").

## Accesos

| Recurso | URL | Credenciales por defecto |
| --- | --- | --- |
| Grafana | http://localhost:3333 | `admin` / `admin` (configurable: `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`) |
| Dashboard "SmartBancs - Observabilidad" | http://localhost:3333/d/smartbancs-overview | — |
| Prometheus | http://localhost:9090 | — |
| Tempo | http://localhost:3200 | — |
| Loki | http://localhost:3100 | — |
| Métricas de PostgreSQL | http://localhost:9187/metrics | — |
| Métricas API | http://localhost:8080/actuator/prometheus | — |
| Métricas AI service | http://localhost:8081/metrics | — |
| Alertas (Prometheus rules) | http://localhost:9090/rules | — |

Si `3333` está ocupado, cambia `GRAFANA_HOST_PORT` (`.env`): el puerto host de
Grafana es configurable.

## Cómo usarlo

### Dashboard
Abrir **Dashboards → SmartBancs**, o la URL del dashboard directo. Incluye:
salud de instancias, rate HTTP por servicio, errores 5xx, throughput
transaccional por tipo y por outcome, latencia p95, volumen procesado, heap
JVM, uptime y una vista de logs.

### Traces (Tempo)
En Grafana: **Explore → Tempo** → *Search*. Filtrar por servicio
(`smartbancs-api`, `smartbancs-ai-service`) y por operación (`http post
/transactions`). El *TraceQL* permite búsquedas tipo
`{ service.name="smartbancs-api" } && { http.route="/transactions" }`.

### Logs (Loki)
```logql
{container="smartbancs-api"}
{container=~"smartbancs-.*"} | json | level="ERROR"
```
El pipeline `| json` estructura el logstash JSON; `traceId` queda como campo
raíz para cruzar con Tempo.

### Métricas (Prometheus)
```promql
sum(rate(smartbancs_transactions_total{outcome="success"}[5m])) by (type)
histogram_quantile(0.95, sum(rate(smartbancs_transaction_duration_seconds_bucket[5m])) by (le, type))
```

## Diseño de observabilidad (estrategia para detectar problemas)

El reto exige definir *qué* información se usa para identificar problemas de
rendimiento, degradación o fallo y *por qué*. La estrategia se basa en tres
pilares complementarios (**métricas → tendencias, logs → contexto, traces →
recorrido**) con **SLIs/SLOs** negociables y **alertas por SLO**.

### Mapa de señales (qué se captura, qué detecta y por qué)

| Señal | Fuente (métrica/log/trace) | Problema que identifica | Por qué es útil |
| --- | --- | --- | --- |
| Disponibilidad de instancias | `up{job}` | Servicio caído / crash-loop | Primera línea: proceso muerto o healthcheck fallando |
| Tasa de peticiones HTTP | `http_server_requests_seconds_count` | Incremento/drop de tráfico | Aísla si el problema es de demanda o de capacidad |
| Latencia HTTP y timeout 5xx | `http_server_requests_seconds_*` | Servicio degradado (cola de hilos/threads) | El reto exige transferencias **< 2 s**; la latencia es el SLI de negocio. Los 5xx = errores que llegan al usuario |
| Latencia de procesamiento por tipo | `smartbancs_transaction_duration_seconds_bucket` | Cuál operación se degrada (TRANSFER vs DEPOSIT) | Histograma → percentiles p50/p95/p99; permite preguntar "¿qué tipo de transacción y cuánta latencia?" |
| Errores de negocio | `smartbancs_transactions_total{outcome="error"}` | Fallos lógicos (fondos, cuenta no activa) | Volumen exacto de operaciones rechazadas; cruzar con logs (`request failed`) |
| Volume transaccional | `smartbancs_transactions_total{outcome="success"}` | Throughput, picos de quincena | Benchmarks contra el objetivo de 10 000 tps |
| Planificación de la BD | `hikaricp_connections_pending`, `hikaricp_connections_timeout_total` | **Timeout de conexión** | Indica que el pool JDBC se agotó: queries lentas o locks reteniendo conexiones |
| Salud de PostgreSQL | `pg_stat_database_deadlocks`, `pg_stat_database_numbackends`, commits/rollbacks | **Deadlocks** y saturación de conexiones | `pg_stat_database_deadlocks` sube → orden de bloqueo de filas incorrecto (revisar orden de locks en código y `pg_stat_activity`) |
| JVM / proceso | `jvm_memory_used_bytes`, `process_cpu_usage`, `process_threads` | OOM, GC o CPU pegado | Distingue problemas de la app (memoria/hilos) de la BD |
| Logs JSON estructurados | Loki | Causa raíz específica (SQL, stacktrace, motivo) | Con `traceId` en cada log se puede saltar al trace y reconstruir la petición exacta |
| Trazas distribuidas | Tempo | Recorrido completo de una transacción a través de componentes (API → repositorio → BD) | Timing por span: ¿el 2 s se gasta en BD, en serialización o en IA? |

### SLIs y SLOs definidos para SmartBancs

| SLI (indicador) | Definición | SLO objetivo |
| --- | --- | --- |
| Latencia de transferencia | p95 de `smartbancs_transaction_duration_seconds{type="TRANSFER"}` | < 2 s (exigencia del reto); alerta preventiva a 1.5 s |
| Tasa de error HTTP | 5xx / total de `http_server_requests` | < 1 % |
| Tasa de error transaccional | `outcome="error"` / total de transacciones | < 5 % |
| Éxito del procesamiento | transacciones `success` vs `error`+`idempotent` | ≥ 99.9 % |
| Deadlocks | `increase(pg_stat_database_deadlocks)` | 0 |
| Disponibilidad | `up` de instancias | 99.9 % mensual |

### Alertas implementadas (`observability/prometheus/rules.yml`)

| Alerta | Expresión (resumen) | Severidad | Qué ordena iniciar |
| --- | --- | --- | --- |
| `SmartBancs_InstanciaCaida` | `up == 0` durante 1 m | critical | Verificar proceso/health; revisar build reciente |
| `SmartBancs_LatenciaTransferenciaAlta` | p95 TRANSFER > 1.5 s | warning | Inspeccionar spans de BD en Tempo; `pg_stat_activity` |
| `SmartBancs_TasaErrorHttpAlta` | 5xx > 1 % | warning | Revisar logs `WARN/ERROR` en Loki |
| `SmartBancs_TasaErrorTransaccionesAlta` | errores > 5 % | warning | Detectar regla de negocio rota o datos corruptos |
| `SmartBancs_TimeoutPoolConexiones` | `hikaricp_connections_timeout_total` aumenta | critical | Acciones inmediatas: terminar transacciones largas, revisar pool |
| `SmartBancs_EsperasPoolAlto` | `hikaricp_connections_pending > 5` | warning | Detectar acumulación de esperas antes del timeout |
| `Postgres_Deadlocks` | deadlocks aumentan en 10 m | critical | Deadlock: orden de locks, `pg_stat_activity` |
| `Postgres_ConexionesAltas` | `numbackends > 80` | warning | Sobredimensionar pool o detectar conexiones filtradas |

> **Verificado en operación**: la pila genera métricas, trazas y logs
> correlacionados por `traceId`, y las 8 reglas de alerta están cargadas y
> evaluando en Prometheus (el ciclo métrica → regla → alerta funciona).

### Por qué métricas + logs + traces juntos

- **Las métricas responden "qué está mal" y "desde cuándo"** (tendencia, picos,
  percentiles). Son baratas de almacenar y consultar a largo plazo.
- **Los logs responden "qué pasó exactamente"** (query SQL, motivo de rechazo,
  stacktrace). Son la fuente de la *causa raíz*.
- **Los traces responden "por dónde pasó" una petición** (span por span) y unen
  las piezas: un `traceId` en un log de error permite abrir el recorrido completo
  en Tempo y ver si el tiempo se consumió en la BD o en otro componente.

En conjunto: métrica alerta (p. ej. `TimeoutPoolConexiones`), log detalla la
petición afectada y la traza muestra el span donde se superó el tiempo de espera.

## Verificación rápida (end-to-end)

```bash
# 1) Métrica de negocio expuesta
curl -s localhost:8080/actuator/prometheus | grep smartbancs_transactions

# 2) Prometheus scrapea ambos servicios
curl -s localhost:9090/api/v1/targets | jq '.data.activeTargets[].health' # up up up

# 3) Genera tráfico
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"DEPOSIT","amount":100,"currency":"PEN","creditAccountId":"a0000000-0000-0000-0000-000000000002"}'

# 4) Trace en Tempo (últimas 10)
curl -s "localhost:3200/api/search?start=$(($(date +%s)-300))&end=$(date +%s)&limit=10"

# 5) Logs en Loki
curl -s -G localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={container="smartbancs-api"}' --data-urlencode 'limit=3'
```

## Decisión de diseño (por qué este stack)

- **Sin agentes**: la instrumentación es la oficial de Spring Boot
  (Micrometer/Micrometer Tracing); nada que desplegar dentro del JVM.
- **Un solo comando**: `docker compose up` levanta todo el stack; es
  reproducible en CI y para la defensa.
- **Estándar del mercado**: stack Grafana (GRAFLO) ampliamente conocido, con
  OpenTelemetry como formato de trazas.
- **Coste por defecto bajo**: en producción se reduciría
  `sampling.probability` (p. ej. 0.05) y la retention de Loki/Tempo.