# Observabilidad (metrics · logs · traces)

> Implementación del entregable **Observability (metrics, logs, traces)** del
> reto. El stack se levanta con el mismo `docker compose` y no requiere
> agentes externos: las dos aplicaciones (API y AI service) se auto-instrumentan
> con **Micrometer** (Spring Boot) y exportan a **Prometheus**, **Tempo** y
> **Loki**, todo visible en **Grafana**.

## Arquitectura e integración

```
                        ┌──────────────────────────────────────────────────┐
                        │                    smartbancs                      │
   Navegador/ops ------ │  │  api:8080 │  ai-service:8081                 │
                        │  │   │  │     │      │                          │
                        │  │  /actuator/prometheus                        │
                        │  │  OTLP HTTP (trazas)  ──► tempo:4318 ──► Tempo│
                        │  │  JSON logs (stdout)  ──► promtail ──► Loki    │
                        │  └──────────────────────────────────────────────┘
                                    │ scrape :9090/targets
                        ┌───────────▼───────────┐   datasources
                        │   Prometheus:9090     ├──────►  Grafana:3001
                        └───────────────────────┘
```

Las piezas nuevas se agrupan en la carpeta **`observability/`**:

| Servicio | Imagen | Puerto host | Rol |
| --- | --- | --- | --- |
| `prometheus` | `prom/prometheus:v2.53.1` | `9090` | Almacena y consulta métricas (scraping de `/actuator/prometheus`) |
| `tempo` | `grafana/tempo:2.6.1` | `3200` (UI/API), `4317` gRPC, `4318` HTTP | Receptor **OTLP** y almacén de traces |
| `loki` | `grafana/loki:3.3.2` | `3100` | Almacén de logs (retention 7 días) |
| `promtail` | `grafana/promtail:3.3.2` | — (interno) | Descubre contenedores `smartbancs-*` y envía sus logs JSON a Loki |
| `grafana` | `grafana/grafana:11.4.0` | `3333` | Dashboards y Explore (Prometheus + Tempo + Loki) |

Grafana se provisiona automáticamente (datasources + dashboard) desde
`observability/grafana/provisioning/` y `observability/grafana/dashboards/`.

## Instrumentación de las aplicaciones

Ambos servicios (`smartbancs-api` y `ai-service`) llevan la misma
instrumentación estándar de Spring Boot 3 + Micrometer:

1. **Dependencias** (en los POM de `smartbancs-api` y `ai-service`):
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

## Accesos

| Recurso | URL | Credenciales por defecto |
| --- | --- | --- |
| Grafana | http://localhost:3333 | `admin` / `admin` (configurable: `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD`) |
| Dashboard "SmartBancs - Observabilidad" | http://localhost:3333/d/smartbancs-overview | — |
| Prometheus | http://localhost:9090 | — |
| Tempo | http://localhost:3200 | — |
| Loki | http://localhost:3100 | — |
| Métricas API | http://localhost:8080/actuator/prometheus | — |
| Métricas AI service | http://localhost:8081/actuator/prometheus | — |

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