# Declaración de Uso de Inteligencia Artificial

> Requisito del reto: *"Si se utilizó IA en el desarrollo de cualquier parte del
> reto, el candidato deberá indicar las herramientas empleadas, cómo se
> utilizaron y en qué componentes, actividades o entregables se aplicaron."*

## Herramientas empleadas

| Herramienta | Tipo | Uso |
| --- | --- | --- |
| **Claude / asistentes de código (opencode)** | Asistente de programación basado en LLM | Autocompletado, revisión de código, generación de código de ejemplo y documentación técnica |
| **Documentación oficial de Spring Boot / Grafana / Prometheus** | Fuente de verdad | Validación de configuraciones (actuator, Micrometer, OpenTelemetry, alertas) |
| (Opcional) **Traductores/intérpretes automáticos** | Herramienta | Revisión gramatical de la documentación en español |

> La lista anterior refleja lo declarado por el candidato. Si no se usó alguna
> herramienta, tacharla; si se usó otra (p. ej. ChatGPT, GitHub Copilot, etc.),
> añadirla aquí.

## Cómo se utilizó

- **Asistencia en código (MVP)**: se generaron fragmentos de configuración
  (p. ej. `application.yml`, `prometheus.yml`, reglas de alerta PromQL,
  `logback-spring.xml`) que el candidato **revisó, validó contra la
  documentación oficial y ajustó** según los requisitos del reto (10 000 tps,
  latencia < 2 s, no saturar a Bancs).
- **Apoyo en diseño técnico**: se contrastaron patrones (outbox, doble partida,
  `SELECT … FOR UPDATE`, idempotencia) con buenas prácticas conocidas.
- **Redacción de documentación**: los documentos técnicos se redactaron con
  apoyo del asistente y fueron **revisados y corregidos por el candidato**
  (arquitectura, decisiones, post mortem, observabilidad).

## En qué componentes / entregables se aplicó

1. **Código del MVP**: configuración de Spring Boot (actuator, tracing, logs
   JSON), métricas de negocio (`TransactionMetrics`), reglas de alerta de
   Prometheus y archivos del stack de observabilidad. Todo el código fue
   compilado (`./mvnw clean package`) y el stack ejecutado y verificado
   (`docker compose up`), incluidas las alertas.
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