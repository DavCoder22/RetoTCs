# Incidente Crítico Simulado y Post Mortem

> Entregables **3.5 Operaciones (incidente simulado)** y **3.6 Gestión de
> incidentes TI (escalamiento y post mortem)** del reto.
> Escenario: durante un pico transaccional de quincena, los usuarios reportan
> que las transferencias no se completan. El monitoreo alerta un incremento
> severo de latencia, múltiples timeouts en la conexión con la base de datos y
> posibles deadlocks en las tablas principales.

---

## 1. Señales detectadas (práctico: qué se configuró para detectarlo)

La instrumentación implementada permite reconstruir el incidente con datos del
propio stack:

| Señal | Métrica / fuente | Momento del incidente |
| --- | --- | --- |
| Latencia alta | `histogram_quantile(0.95, rate(smartbancs_transaction_duration_seconds_bucket[5m]))` | p95 de TRANSFER supera 1.5 s (alerta `SmartBancs_LatenciaTransferenciaAlta`) |
| Timeouts de conexión BD | `increase(hikaricp_connections_timeout_total[5m]) > 0` | Alerta crítica `SmartBancs_TimeoutPoolConexiones`: el pool JDBC no puede dar conexiones |
| Esperas en pool | `hikaricp_connections_pending > 5` | Peticiones acumulándose esperando conexión (`SmartBancs_EsperasPoolAlto`) |
| Deadlocks | `increase(pg_stat_database_deadlocks{datname="smartbancs"}[10m]) > 0` | Alerta crítica `Postgres_Deadlocks` (counters de PostgreSQL vía postgres-exporter) |
| Saturación de conexiones | `pg_stat_database_numbackends` | Backends altos (alerta `Postgres_ConexionesAltas`) |
| Errores que llegan al usuario | `http_server_requests_seconds_count{status=~"5.."}` | Tasa 5xx > 1 % (`SmartBancs_TasaErrorHttpAlta`) |
| Causa raíz (contexto) | Logs Loki `request failed with status 504/500…` with `traceId` | Saltar del log al trace completo en Tempo |
| Recorrido exacto | Trazas Tempo: span de BD (JPA) dentro del trace de `/transactions` | Probar dónde se consume el tiempo |

**Validación realizada en el repo**: inyectando un deadlock controlado
(advisory locks) se confirmó que `pg_stat_database_deadlocks` sube y la alerta
`Postgres_Deadlocks` pasa a estado **firing** en Prometheus.

### Comandos de diagnóstico (practicables en el stack)

```bash
# 1) ¿Quién está bloqueando o ejecutando queries largas?
docker exec -it smartbancs-postgres psql -U smartbancs -d smartbancs -c \
  "SELECT pid, state, duration, wait_event_type, wait_event, query
     FROM pg_stat_activity WHERE state <> 'idle' AND pid <> pg_backend_pid();"

# 2) ¿Hay transacciones ocioso-abiertas (locks retenidos)?
docker exec -it smartbancs-postgres psql -U smartbancs -d smartbancs -c \
  "SELECT pid, now() - xact_start AS tx_age, state, wait_event_type FROM pg_stat_activity
    WHERE state IN ('active','idle in transaction') ORDER BY tx_age DESC;"

# 3) ¿Qué filas están bloqueadas?
docker exec -it smartbancs-postgres psql -U smartbancs -d smartbancs -c \
  "SELECT pid, locktype, relation::regclass, mode FROM pg_locks WHERE not granted;"

# 4) Deadlocks acumulados
curl -s localhost:9187/metrics | grep '^pg_stat_database_deadlocks' | grep smartbancs

# 5) Trazas de la transacción afectada (Tempo)
curl -s "localhost:3200/api/search?start=$(($(date +%s)-600))&end=$(date +%s)&limit=20" \
   | grep -o '"rootTraceName":"[^"]*"'

# 6) Logs con traceId del error
curl -s -G localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={container="smartbancs-api"} | json | level="ERROR"' --data-urlencode 'limit=20'
```

---

## 2. Acciones inmediatas (teórico — priorizado)

Objetivo: **estabilizar en minutos** mientras se encuentra la causa raíz.

### Fase A — Contención (0–5 min)

1. **Finalizar conexiones / transacciones que acaparan el pool**:
   ```sql
   SELECT pg_terminate_backend(pid)
     FROM pg_stat_activity
     WHERE state = 'idle in transaction' AND now() - xact_start > interval '5 minutes';
   ```
   Libera locks y conexiones de inmediato.
2. **Reducir presión sobre la BD**: activar *rate limiting* / *circuit breaker*
   en la API (degradar a lector para consultas no críticas), limitar el
   `hikaricp.maximum-pool-size` temporal para evitar que todos los hilos
   bloqueen con timeouts encadenados.
3. **Balancear / escalar horizontal**: duplicar réplicas del `smartbancs-api`
   (stateless) para repartir la carga mientras la BD se estabiliza.
4. **Aislar el relayer de Bancs**: si el outbox se atasca por backpressure del
   core, pausar la entrega para no encadenar otro sistema.

### Fase B — Mitigación (5–30 min)

5. Identificar la **query problemática** con `pg_stat_statements`/`EXPLAIN
   ANALYZE` sobre las consultas de `lockedAccount()` (SELECT … FOR UPDATE) y
   del ledger; crear/ajustar **índices** (p. ej. (debit_account_id, created_at)
   y (credit_account_id, created_at)).
6. Si el deadlock persiste, reordenar los locks en código: **bloquear siempre
   las filas en el mismo orden** (p. ej. por `account_id` ordenado) en
   `processTransfer`, eliminando el ciclo AB–BA.
7. Subir temporalmente `max_connections`/pool si la causa es capacidad física.

### Fase C — Restauración

8. Monitorear p95 < 2 s, 5xx < 1 %, `pg_stat_database_deadlocks` estable en 0
   durante 15 min antes de declarar restaurado el servicio.
9. Reactivar relayer de Bancs y reconciliar la cola atrasada.

---

## 3. Escalamiento (cuándo y a quién)

| Nivel | Criterio de activación | Acción |
| --- | --- | --- |
| **N1 – Soporte/monitoreo (24/7)** | Alerta dispara (`warning`) | Confirmar señal, triage inicial, crear el documento inicial del incidente |
| **N2 – Ingeniería de plataforma** | Impacto en usuarios / `critical` | Diagnóstico de BD, killer de conexiones, ajustes de pool/índices |
| **N3 – Equipo de negocio + Arquitectura** | > SLO superado o afectación de fondos | Decisión de *freeze* de deploys, comunicación a stakeholders, escalamiento de infraestructura |
| **Comité de crisis** | Riesgo severo a nivel de organización | Comunicación pública, pago de SLA, postura regulatoria |

Protocolo de comunicación: alertas → Slack/Teams + pagers; estado en un tablero
de incidentes (state, owner, timeline); updates cada 15 min durante `critical`.

---

## 4. Estructura del informe Post Mortem

Documento único `docs/POST_MORTEM.md` con la siguiente plantilla (formato
estándar SRE/Google):

```markdown
# Post Mortem — <Título> · <fecha> · <severidad>

## 1. Resumen ejecutivo (3 líneas)
   Qué pasó, impacto (usuario/negocio), duración.

## 2. Línea de tiempo (UTC, zona horaria)
   - HH:MM alerta `SmartBancs_LatenciaTransferenciaAlta`
   - HH:MM p95 TRANSFER > 3 s; ratio de éxito cae
   - HH:MM `SmartBancs_TimeoutPoolConexiones` (firing)
   - HH:MM `Postgres_Deadlocks` (firing); inicio investigación
   - HH:MM killer de transacciones `idle in transaction`
   - HH:MM degradación mitigada; HH:MM servicio nominal

## 3. Causa raíz (5 why)
   Por qué el pico degradó la BD -> queries largas con locks de filas
   -> bloqueo cruzado (orden de FOR UPDATE inconsistente) -> pool agotado
   -> timeouts en cadena.

## 4. Impacto
   - Métricas: SLO alcanzado a 67 % durante la quincena; 12 000 transferencias
     retrasadas; p95 3.4 s.
   - Usuarios afectados, montos, RPO/RTO.

## 5. Qué funcionó / qué falló en la detección
   - Funcionó: deadlock counter y pool timeouts se detectaron antes que los
     usuarios.
   - Falló: no había alerta preventiva de espera de locks (pending) - ahora SÍ.

## 6. Acciones correctivas (priorizadas)
   - P0 (código): orden estable de locks en `processTransfer`.
   - P0 (infra): ajustar `hikaricp.maximum-pool-size` + `connection-timeout`.
   - P1 (código): `pg_stat_statements` + refresco de índices para el ledger.
   - P1 (proceso): drill de incidente, revisión de alertas por umbral.
   - P2 (mejora): timer/agente que termina transacciones idle > N min.

## 7. Lecciones y compromiso de seguimiento
```

---

## 5. Acciones preventivas (infraestructura y código)

### Código
| Acción | Justificación |
| --- | --- |
| **Orden consistente de locks en transferencias** (bloquear por id de cuenta ordenado) | Elimina el ciclo AB–BA que genera deadlocks |
| **Timeout de query/statement en Hikari y en JDBC** | Evita que una query lenta congele todo el pool (fail fast) |
| Limitación del tamaño de lote del ledger/idempotencia y uso de índices compuestos | Reduce el trabajo por transacción y el lock scope |
| Logs de advertencia pre-`timeout` (métrica `hikaricp_connections_creation`) | Detecta presión del pool antes del corte |
| Circuit breaker sobre las integraciones (Bancs/IA) | Evita que el fallo externo se propague (bulkhead) |

### Infraestructura
- **PostgreSQL**: `max_connections` correctamente dimensionado; `idle_in_transaction_session_timeout`; `statement_timeout`; `pg_stat_statements` activo; monitoreo de `pg_locks` no concedidos.
- **Pool Hikari**: `maximum-pool-size` dimensionado por núcleos (≈ N×2), `connection-timeout` explícito, métricas de `pending/active/timeout` (ya expuestas).
- **Autoescala** del `smartbancs-api` basada en CPU y `http_server_requests`; réplica de lectura para reportes.
- **Alertas preventivas**: alerts de esperas (`hikaricp_connections_pending`), deadlocks y tasa de error ya implementados en `observability/prometheus/rules.yml`.
- **Drills**: ejecutar el escenario de quincena con carga (script de pruebas existente en `scripts/`) al menos una vez por trimestre y validar que el runbook es accionable.