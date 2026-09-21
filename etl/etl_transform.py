#!/usr/bin/env python3
"""SmartBancs ETL — ingesta de lotes de transacciones no homologadas.

Pipeline: EXTRACT (CSV sucio) -> TRANSFORM (limpieza) -> DEDUP (idempotencia)
          -> RESOLVE (accountNumber -> accountId) -> LOAD (POST /transactions/batch)
          -> ANALYZE (fact table optimizada para analítica / IA + reporte JSON).

Usa solo la librería estándar (sin dependencias) para no complicar el despliegue
(script independiente; el despliegue no cambia con Terraform).

Ejemplos:
  python3 etl/etl_transform.py --input etl/sample-data/raw_transactions.csv
  python3 etl/etl_transform.py --input ... --api-url http://localhost:8080 --dry-run
"""
import argparse
import csv
import datetime as dt
import hashlib
import json
import os
import re
import sys
import unicodedata
import urllib.error
import urllib.request
from decimal import Decimal, InvalidOperation

COLUMN_ALIASES = {
    "type": ["tipo", "tipo de operacion", "tipo de oper", "tipo de operación", "type", "operacion", "operación"],
    "amount": ["monto", "monto bruto", "amount", "valor", "importe"],
    "currency": ["moneda", "currency", "divisa"],
    "debit": ["cuenta debito", "cuenta débito", "cta debito", "cta débito", "cuenta dbito", "cta dbito",
              "debit", "debitaccountnumber"],
    "credit": ["cuenta credito", "cuenta crédito", "cta credito", "cta crédito", "cuenta cbito", "cta cbito",
               "credit", "creditaccountnumber"],
    "date": ["fecha", "fecha operacion", "fecha de operacion", "fecha de operación", "date"],
    "reference": ["referencia", "refer", "reference"],
}

TYPE_ALIASES = {
    "deposito": "DEPOSIT", "deposit": "DEPOSIT", "abono": "DEPOSIT",
    "retiro": "WITHDRAWAL", "withdrawal": "WITHDRAWAL", "withdraw": "WITHDRAWAL", "giro": "WITHDRAWAL",
    "transfer": "TRANSFER", "transferencia": "TRANSFER", "transf": "TRANSFER",
    "pago": "PAYMENT", "payment": "PAYMENT", "pay": "PAYMENT",
}

DATE_FORMATS = [
    "%Y-%m-%d", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M:%S",
    "%d/%m/%Y", "%d/%m/%Y %H:%M", "%d-%m-%Y", "%d-%m-%Y %H:%M",
    "%d.%m.%Y", "%d.%m.%Y %H:%M",
]

MINOR_UNIT = Decimal(100)


def norm_key(value):
    if value is None:
        return ""
    text = str(value)
    text = "".join(ch for ch in unicodedata.normalize("NFKD", text) if not unicodedata.combining(ch))
    return re.sub(r"\s+", " ", text.lower().strip())


def map_columns(fieldnames):
    mapping = {}
    for idx, raw in enumerate(fieldnames or []):
        hk = norm_key(raw)
        for canonical, aliases in COLUMN_ALIASES.items():
            if any(norm_key(alias) == hk for alias in aliases):
                mapping[canonical] = idx
                break
    return mapping


def parse_amount(raw):
    if raw is None:
        return None, "amount missing"
    s = str(raw).strip()
    if not s:
        return None, "amount missing"
    s = re.sub(r"[^\d.,-]", "", s)
    if not s or s in ("-", ".", ","):
        return None, "amount unparseable"
    if "," in s and "." in s:
        dec = "." if s.rfind(".") > s.rfind(",") else ","
        s = s.replace(",", "") if dec == "." else s.replace(".", "")
    elif "," in s:
        parts = s.split(",")
        if len(parts) == 2 and len(parts[1]) <= 2:
            s = s.replace(",", ".")
        else:
            s = s.replace(",", "")
    try:
        value = Decimal(s)
    except InvalidOperation:
        return None, "amount unparseable"
    if value <= 0:
        return None, "amount must be positive"
    return value, None


def parse_date(raw):
    if raw is None:
        return None
    s = str(raw).strip()
    if not s:
        return None
    for fmt in DATE_FORMATS:
        try:
            return dt.datetime.strptime(s, fmt).date().isoformat()
        except ValueError:
            continue
    return None


def normalize_type(raw):
    if raw is None:
        return None
    return TYPE_ALIASES.get(norm_key(raw))


def normalize_currency(raw):
    if raw is None:
        return None
    cur = str(raw).strip().upper()
    return cur if re.fullmatch(r"[A-Z]{3}", cur) else None


def raw_cell(row, idx):
    if idx is None:
        return None
    values = row if isinstance(row, list) else list(row)
    return values[idx] if idx < len(values) else None


def idempotency_key(canonical):
    provided = canonical.get("idempotency_key")
    if provided and str(provided).strip():
        return str(provided).strip()
    basis = "|".join([
        canonical["type"], str(canonical["amount"]), canonical["currency"],
        canonical.get("debit") or "", canonical.get("credit") or "",
        canonical.get("reference") or "",
    ])
    return "raw-" + hashlib.sha256(basis.encode()).hexdigest()[:24]


def fetch_accounts(api_url):
    url = api_url.rstrip("/") + "/accounts"
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            body = json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        print(f"[RESOLVE] ERROR al listar cuentas ({url}): HTTP {exc.code} {exc.read().decode()[:200]}", file=sys.stderr)
        return {}
    return {str(acc.get("accountNumber")): str(acc["id"]) for acc in body if acc.get("accountNumber")}


def post_batch(api_url, batch_id, items):
    url = api_url.rstrip("/") + "/transactions/batch"
    payload = json.dumps({"batchId": batch_id, "items": items}).encode()
    req = urllib.request.Request(url, data=payload, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode()[:400]


def build_fact_rows(batch_id, sent_records):
    fact = []
    for rec in sent_records:
        if rec["_status"] != "loaded":
            continue
        tx_id = rec["_transaction_id"]
        amount_minor = int((rec["amount"] * MINOR_UNIT).to_integral_value())
        legs = []
        if rec["type"] in ("WITHDRAWAL", "PAYMENT"):
            legs.append(("DEBIT", rec.get("debit")))
        elif rec["type"] == "DEPOSIT":
            legs.append(("CREDIT", rec.get("credit")))
        elif rec["type"] == "TRANSFER":
            legs.append(("DEBIT", rec.get("debit")))
            legs.append(("CREDIT", rec.get("credit")))
        for direction, account in legs:
            fact.append({
                "batch_id": batch_id,
                "transaction_id": tx_id,
                "ledger_direction": direction,
                "type": rec["type"],
                "account_number": account,
                "amount_minor_units": amount_minor,
                "currency": rec["currency"],
                "booked_date": rec.get("date") or dt.date.today().isoformat(),
                "reference": rec.get("reference") or "",
            })
    return fact


def write_report(output_dir, report):
    path = os.path.join(output_dir, "etl_report.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, ensure_ascii=False)
    return path


def main():
    parser = argparse.ArgumentParser(description="ETL SmartBancs: transforma y carga un lote CSV")
    parser.add_argument("--input", required=True, help="CSV crudo / sin homologar")
    parser.add_argument("--api-url", default="http://localhost:8080", help="URL base de smartbancs-api")
    parser.add_argument("--output-dir", default="etl/output", help="Directorio de salida (fact + reporte)")
    parser.add_argument("--batch-id", default=None, help="Identificador del lote (default: basado en el CSV)")
    parser.add_argument("--chunk-size", type=int, default=50, help="Ítems por POST /transactions/batch")
    parser.add_argument("--dry-run", action="store_true", help="Solo transforma/reporta; no publica nada")
    parser.add_argument("--verbose", action="store_true", help="Muestra cada fila procesada")
    args = parser.parse_args()

    input_path = args.input
    if not os.path.isfile(input_path):
        print(f"ERROR: no existe el archivo de entrada '{input_path}'", file=sys.stderr)
        sys.exit(2)

    batch_id = args.batch_id or ("batch-" + os.path.splitext(os.path.basename(input_path))[0])
    os.makedirs(args.output_dir, exist_ok=True)

    print(f"== SmartBancs ETL ==  batch_id={batch_id}  api={args.api_url}  dry_run={args.dry_run}")

    with open(input_path, "r", encoding="utf-8-sig", newline="") as fh:
        reader = csv.reader(fh)
        header = next(reader, None)
        if header is None:
            print("ERROR: CSV vacío", file=sys.stderr)
            sys.exit(2)
        cols = map_columns(header)
        rows = [row for row in reader]

    raw_count = len(rows)
    blank_count = sum(1 for r in rows if not any(cell.strip() for cell in r))
    print(f"[EXTRACT]  {raw_count} filas leídas ({blank_count} vacías omitidas) de {os.path.basename(input_path)}")

    for canonical, idx in sorted(cols.items(), key=lambda kv: kv[1]):
        print(f"  columna mapeada: {canonical} <- '{header[idx]}'")

    cleaned = []
    dropped = []
    for i, row in enumerate(rows, start=1):
        if not any(cell.strip() for cell in row):
            continue
        field = {key: raw_cell(row, idx) for key, idx in cols.items()}
        type_ = normalize_type(field.get("type"))
        if type_ is None:
            dropped.append({"index": i, "reason": "unknown type", "row": field})
            continue
        amount, amount_err = parse_amount(field.get("amount"))
        if amount is None:
            dropped.append({"index": i, "reason": amount_err, "row": field})
            continue
        currency = normalize_currency(field.get("currency"))
        if currency is None:
            dropped.append({"index": i, "reason": "currency missing or invalid", "row": field})
            continue
        debit = str(field.get("debit") or "").strip() or None
        credit = str(field.get("credit") or "").strip() or None
        if type_ == "DEPOSIT" and not credit:
            dropped.append({"index": i, "reason": "DEPOSIT requires credit account", "row": field})
            continue
        if type_ in ("WITHDRAWAL", "PAYMENT") and not debit:
            dropped.append({"index": i, "reason": f"{type_} requires debit account", "row": field})
            continue
        if type_ == "TRANSFER" and (not debit or not credit):
            dropped.append({"index": i, "reason": "TRANSFER requires debit and credit accounts", "row": field})
            continue
        rec = {
            "index": i,
            "type": type_,
            "amount": amount,
            "currency": currency,
            "debit": debit,
            "credit": credit,
            "date": parse_date(field.get("date")),
            "reference": str(field.get("reference") or "").strip() or None,
            "idempotency_key": idempotency_key({**field, "type": type_, "amount": amount,
                                                "currency": currency, "debit": debit, "credit": credit}),
            "_status": "pending",
        }
        cleaned.append(rec)

    print(f"[TRANSFORM] {len(cleaned)} filas válidas, {len(dropped)} descartadas (nulos/formatos incoherentes)")
    if args.verbose:
        for d in dropped:
            print(f"  drop #{d['index']}: {d['reason']}")

    seen = set()
    duplicates = []
    to_send = []
    for rec in cleaned:
        if rec["idempotency_key"] in seen:
            duplicates.append({"index": rec["index"], "reason": "duplicate idempotencyKey (omitido)"})
            rec["_status"] = "duplicate"
            continue
        seen.add(rec["idempotency_key"])
        to_send.append(rec)
    print(f"[DEDUP]    {len(to_send)} a enviar, {len(duplicates)} duplicados omitidos por idempotencia")

    if args.dry_run:
        planned = [{"index": r["index"], "type": r["type"], "amount": str(r["amount"]),
                    "currency": r["currency"], "debit": r["debit"], "credit": r["credit"],
                    "date": r["date"]} for r in to_send]
        report = {
            "batch_id": batch_id, "source": input_path, "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "dry_run": True,
            "counts": {"raw_rows": raw_count, "valid": len(cleaned), "duplicates": len(duplicates),
                       "dropped": len(dropped), "to_send": len(to_send)},
            "dropped": dropped, "duplicates": duplicates, "planned": planned,
        }
        write_report(args.output_dir, report)
        print(f"[DRY-RUN]   {len(to_send)} movimientos listos; reporte en {args.output_dir}/etl_report.json")
        return 0

    accounts = fetch_accounts(args.api_url)
    print(f"[RESOLVE]  {len(accounts)} cuentas homologadas (accountNumber -> accountId) en {args.api_url}")

    rejected = []
    items = []
    for rec in to_send:
        errors = []
        if rec["debit"] and rec["debit"] not in accounts:
            errors.append(f"accountNumber not found: {rec['debit']}")
        if rec["credit"] and rec["credit"] not in accounts:
            errors.append(f"accountNumber not found: {rec['credit']}")
        if errors:
            rec["_status"] = "rejected"
            rejected.append({"index": rec["index"], "reason": "; ".join(errors)})
            continue
        items.append(rec)

    print(f"[LOAD]     {len(items)} ítem(s) a publicar en /transactions/batch (chunk={args.chunk_size})")
    sent_records = []
    for start in range(0, len(items), args.chunk_size):
        chunk = items[start:start + args.chunk_size]
        payload = [{
            "type": r["type"], "amount": str(r["amount"]), "currency": r["currency"],
            "debitAccountNumber": r["debit"], "creditAccountNumber": r["credit"],
            "idempotencyKey": r["idempotency_key"], "reference": r["reference"],
        } for r in chunk]
        status, body = post_batch(args.api_url, batch_id, payload)
        if status not in (200, 201):
            print(f"[LOAD] ERROR HTTP {status}: {body}", file=sys.stderr)
            sys.exit(2)
        for result in body.get("results", []):
            pos = int(result["index"]) - 1
            rec = chunk[pos]
            rec["_transaction_id"] = result.get("transactionId")
            rec["_status"] = "loaded" if result.get("accepted") else "rejected"
            if not result.get("accepted"):
                reason = result.get("error") or "rejected by API"
                rejected.append({"index": rec["index"], "reason": reason,
                                 "transactionId": None})
            sent_records.append(rec)

    accepted_count = sum(1 for r in sent_records if r["_status"] == "loaded")
    print(f"[LOAD]     {len(sent_records)} enviadas: {accepted_count} aceptadas, "
          f"{sum(1 for r in sent_records if r['_status']=='rejected')} rechazadas (reglas de negocio)")

    fact = build_fact_rows(batch_id, sent_records)
    fact_path = os.path.join(args.output_dir, "transactions_fact.csv")
    with open(fact_path, "w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=[
            "batch_id", "transaction_id", "ledger_direction", "type",
            "account_number", "amount_minor_units", "currency", "booked_date", "reference"])
        writer.writeheader()
        writer.writerows(fact)

    report = {
        "batch_id": batch_id,
        "source": input_path,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "api_url": args.api_url,
        "accounts_homologated": len(accounts),
        "counts": {
            "raw_rows": raw_count,
            "valid_after_transform": len(cleaned),
            "duplicates": len(duplicates),
            "dropped": len(dropped),
            "sent": len(sent_records),
            "accepted": accepted_count,
            "rejected_by_api_business_rules": len(rejected),
            "fact_legs": len(fact),
        },
        "dropped": dropped,
        "duplicates": duplicates,
        "rejected": rejected,
        "outputs": {"fact_csv": fact_path, "report_json": os.path.join(args.output_dir, "etl_report.json")},
    }
    report_path = write_report(args.output_dir, report)

    print(f"[ANALYZE]  fact table ({len(fact)} leg(s) ledger) en {fact_path}")
    print(f"[ANALYZE]  reporte en {report_path}")
    print("== Resumen de carga ==")
    for row in sent_records:
        print(f"  #{row['index']:<3} {row['type']:<10} {str(row['amount']):>10} {row['currency']} "
              f"debit={row.get('debit')} credit={row.get('credit')} -> {row['_status']}"
              + (f" ({row.get('_transaction_id','')[:8]})" if row["_status"] == "loaded" else ""))
    if rejected:
        print("  Rechazadas:")
        for r in rejected:
            print(f"    # {r['index']}: {r['reason']}")
    if duplicates:
        print(f"  Duplicadas omitidas: {len(duplicates)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())