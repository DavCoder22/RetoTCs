# Declaración de Uso de Inteligencia Artificial

> Requisito del reto: *"Si se utilizó IA en el desarrollo de cualquier parte del
> reto, el candidato deberá indicar las herramientas empleadas, cómo se
> utilizaron y en qué componentes, actividades o entregables se aplicaron."*

## Herramientas empleadas

| Herramienta | Tipo | Uso |
| --- | --- | --- |
| **Big Pickle** (agente de codificación de **opencode**) | Asistente de programación basado en LLM | Generación de código, configuración e instrumentación (observabilidad → agente de IA); revisión y documentación |
| **Agente de planificación de opencode** | Planificador | Diseño de la **estructura general del mock** (flujo transaccional y servicio de recomendaciones) |
| **Documentación oficial de Spring Boot / FastAPI / Grafana / Prometheus** | Fuente de verdad | Validación de configuraciones (actuator, Micrometer, OpenTelemetry, alertas, endpoints) |
| (Opcional) **Traductores/intérpretes automáticos** | Herramienta | Revisión gramatical de la documentación en español |

> La lista anterior refleja lo declarado por el candidato. Si no se usó alguna
> herramienta, tacharla; si se usó otra (p. ej. ChatGPT, GitHub Copilot, etc.),
> añadirla aquí.

## Cómo se utilizó

- **Asistencia en código (MVP)**: se generaron las funciones comprendidas
  entre la **observabilidad** y la **sección del agente de IA** (que quedó en
  **Python/FastAPI**): configuración de Spring Boot (actuator, tracing, logs
  JSON), métricas de negocio (`TransactionMetrics`), métricas propias del
  agente de IA (`smartbancs_ai_*`), reglas de alerta PromQL y archivos del
  stack de observabilidad. El candidato **revisó, validó contra la
  documentación oficial y ajustó** cada pieza según los requisitos del reto.
- **Apoyo en diseño técnico**: se contrastaron patrones (outbox, doble partida,
  `SELECT … FOR UPDATE`, idempotencia) con buenas prácticas conocidas.
- **Estructura del mock**: la organización general del mock (transaccional y del
  agente de IA) se diseñó con el **agente de planificación de opencode**.
- **Redacción de documentación**: los documentos técnicos se redactaron con
  apoyo del asistente y fueron **revisados y corregidos por el candidato**
  (arquitectura, decisiones, post mortem, observabilidad).

## En qué componentes / entregables se aplicó

1. **Código del MVP**: configuración de Spring Boot (actuator, tracing, logs
   JSON), métricas de negocio (`TransactionMetrics`), reglas de alerta de
   Prometheus, archivos del stack de observabilidad y el **agente de IA en
   Python/FastAPI** (endpoints asíncronos, proveedor OpenRouter con fallback a
   mock avanzado y métricas `smartbancs_ai_*`). Todo se compiló/ejecutó
   (`./mvnw clean package` y `docker compose up`) y se verificó de punta a
   punta: transacción → métrica → alerta, logs con `traceId` y traza en Tempo,
   y petición al agente de IA con sus métricas scrapedas por Prometheus.
2. **Docencia y evidencia**: documentación en `docs/` (ARQUITECTURA,
   OBSERVABILIDAD, INCIDENTE_Y_POST_MORTEM, GUIA_DEMO_SWAGGER), README en
   español, scripts de demo y evidencia de funcionamiento.
3. **No se usó IA para**: decisiones de seguridad (credenciales, secretos),
   cálculos contables del ledger, ni para generar datos financieros falsos.

## Verificación humana

- Cada pieza generada por IA fue **revisada, compilada, ejecutada y/o probada**
  por el candidato antes de considerar cerrada (build local, smoke tests,
  verificación de métricas, trazas, logs y alertas en el stack real).
- La responsabilidad final sobre arquitectura, código, seguridad y contenido
  del repositorio es del candidato.

---

*Fecha: 2026-09-20 · Candidato: David Malquin (DavCoder22)*