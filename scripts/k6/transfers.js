// Prueba de estrés de resiliencia: N transferencias de 0.01 entre dos cuentas.
//
// Cada iteración hace un POST /transactions (type=TRANSFER) con idempotencyKey
// UNICA (`testid-vu-iter`), por lo que las iteraciones generan asientos reales
// en el ledger (sin duplicados por idempotencia).
//
// Variables de entorno (k6 run -e CLAVE=VALOR):
//   API_URL        host de la API            (default http://localhost:8080)
//   DEBIT_ACCOUNT  cuenta origen             (default seed a0000...0001)
//   CREDIT_ACCOUNT cuenta destino            (default seed a0000...0002)
//   AMOUNT         monto por transferencia   (default 0.01)
//   TOTAL_ITERS    iteraciones/transferencias (default 10000)
//   VUS            usuarios concurrentes      (default 50)
//   TEST_ID        etiqueta de la corrida     (default stress-<ts>) útil en
//                 Prometheus/Grafana para filtrar {testid="..."}
//
// Métricas a Prometheus (remote write) — el Prometheus del stack ya recibe
// con --web.enable-remote-write-receiver:
//   k6 run --out experimental-prometheus-rw \
//         --env K6_PROMETHEUS_RW_SERVER_URL=http://<host>:9090/api/v1/write transfers.js

import { check, group } from 'k6';
import http from 'k6/http';
import { Trend, Counter, Rate } from 'k6/metrics';

const API = __ENV.API_URL || 'http://localhost:8080';
const DEBIT = __ENV.DEBIT_ACCOUNT || 'a0000000-0000-0000-0000-000000000001';
const CREDIT = __ENV.CREDIT_ACCOUNT || 'a0000000-0000-0000-0000-000000000002';
const AMOUNT = Number(__ENV.AMOUNT || 0.01);
const TOTAL = Number(__ENV.TOTAL_ITERS || 10000);
const VUS = Number(__ENV.VUS || 50);
const TEST_ID = __ENV.TEST_ID || `stress-${Date.now()}`;

const transferencias_ok = new Counter('transferencias_ok');
const transferencias_fail = new Counter('transferencias_fail');
const transfer_exitoso = new Rate('transfer_exitoso');
const transfer_tiempo = new Trend('transfer_tiempo');

export const options = {
    scenarios: {
        transacciones: {
            executor: 'shared-iterations',
            vus: VUS,
            iterations: TOTAL,
            maxDuration: '30m',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
        http_req_duration: ['p(95)<2000'],
        transfer_exitoso: ['rate>0.98'],
    },
    tags: { testid: TEST_ID },
};

// El corte es exacto: shared-iterations reparte TOTAL entre los VUs y detiene
// la corrida cuando la suma llega a TOTAL, sin importar la duración.
export default function () {
    group('transferencia', () => {
        const body = JSON.stringify({
            type: 'TRANSFER',
            amount: AMOUNT,
            currency: 'PEN',
            debitAccountId: DEBIT,
            creditAccountId: CREDIT,
            idempotencyKey: `${TEST_ID}-vu${__VU}-iter${__ITER}`,
            reference: `k6 ${TEST_ID} vu${__VU} iter${__ITER}`,
        });

        const res = http.post(`${API}/transactions`, body, {
            headers: { 'Content-Type': 'application/json' },
            tags: { testid: TEST_ID },
        });

        const ok = check(res, {
            'status 201': (r) => r.status === 201,
        });

        if (ok) {
            transferencias_ok.add(1);
            transfer_exitoso.add(true);
            transfer_tiempo.add(res.timings.duration);
        } else {
            transferencias_fail.add(1);
            transfer_exitoso.add(false);
        }
    });
}