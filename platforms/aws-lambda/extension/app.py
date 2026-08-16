"""The customer's handler: an ordinary psycopg app that knows nothing about any of this.

It connects to Postgres on localhost. That works because extensions share the
handler's network namespace -- the "Postgres" is a JVM running pg-datahike over
object storage, in the same execution environment.

WHY THE CONNECTION IS LAZY, and not opened at module level:

  This is a REQUIREMENT, not a style preference, and getting it wrong killed every
  early attempt.

  Function init has a HARD ~10s budget on Lambda; exceed it and the platform kills
  and restarts the execution environment. Extension init and Runtime init run
  CONCURRENTLY -- Lambda waits for both to signal `Next`, but it does not hold the
  runtime while an extension boots -- so a module-level connect both races the JVM
  and then blocks Function init waiting for it. Measured: pg-datahike takes ~7.3s to
  be ready (tenant open 3.07s, namespace class loading 4.19s), which alone is most
  of the budget.

  Connecting on first INVOKE instead moves that wait into the invoke phase, whose
  timeout is the function's (seconds to minutes). The first request pays it; every
  later request on the same container pays nothing (warm read measured at 8.52ms).

  This is not a local workaround. It is required on AWS for the same reason.
"""
import json, os, threading, time
import psycopg

DSN  = os.environ.get("PG_DSN", "postgresql://alice@127.0.0.1:5432/acme")
_LOCK = threading.Lock()
_CONN = None
_CONNECT_MS = None

def _conn():
    """Connect once per execution environment, on first use."""
    global _CONN, _CONNECT_MS
    if _CONN is not None and not _CONN.closed:
        return _CONN
    with _LOCK:
        if _CONN is not None and not _CONN.closed:
            return _CONN
        t0 = time.perf_counter()
        last = None
        # The extension may still be booting; retry inside the INVOKE budget.
        for _ in range(240):
            try:
                c = psycopg.connect(DSN, autocommit=True, connect_timeout=5)
                break
            except Exception as e:
                last = e; time.sleep(0.25)
        else:
            raise RuntimeError(f"pg-datahike extension never became reachable: {last}")
        with c.cursor() as cur:
            cur.execute("""CREATE TABLE IF NOT EXISTS note (
                             id bigint PRIMARY KEY, title text, body text)""")
        _CONNECT_MS = (time.perf_counter() - t0) * 1000
        print(f"[fn] LAZY-CONNECT {_CONNECT_MS:.0f}ms", flush=True)
        _CONN = c
        return _CONN

def handler(event, context):
    t0 = time.perf_counter()
    op = (event or {}).get("op", "read")
    conn = _conn()
    with conn.cursor() as c:
        if op == "write":
            n = int(event.get("n", 1))
            for i in range(n):
                # The natural upsert -- which is what an ORM emits. This was broken
                # until pg-datahike 0.1.62: parameterised `INSERT ... ON CONFLICT`
                # failed with "class ParamRef cannot be cast to java.lang.Number".
                # Fixed upstream (PR #30). NOT yet re-verified in this image -- the
                # last measured run used 0.1.61 with a DELETE + plain INSERT
                # workaround. See the README's status table.
                c.execute("INSERT INTO note (id,title,body) VALUES (%s,%s,%s) "
                          "ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title",
                          (i, f"note {i}", "lorem ipsum " * 10))
            result = {"wrote": n}
        else:
            c.execute("SELECT id, title FROM note ORDER BY id LIMIT 20")
            result = {"rows": [list(r) for r in c.fetchall()]}
    ms = (time.perf_counter() - t0) * 1000
    print(f"[fn] INVOKE op={op} {ms:.2f}ms", flush=True)
    return {"statusCode": 200, "ms": round(ms, 2),
            "connect_ms": round(_CONNECT_MS) if _CONNECT_MS else None,
            "body": json.dumps(result)}
