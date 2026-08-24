# Measurements: the full narrative

The README carries the headline numbers; this file is the complete record with
conditions and commentary, kept verbatim as the work progressed so that a
number can always be traced to the run that produced it. Raw data:
[`measurements.edn`](measurements.edn). The SnapStart/CRaC results have their
own file: [`snapstart-crac.md`](snapstart-crac.md).

All measurements in this file come from the **AWS Lambda Runtime Interface
Emulator + MinIO on a laptop**, unless a section says otherwise. Field results
from real AWS are reported by a third party and summarized in the README.

## The measurements (RIE + MinIO)

All of these come from the prototype this repo was extracted from
([datahike-saas-starter](https://github.com/replikativ/datahike-saas-starter),
`doc/results/coldstart.edn`), measured against the **AWS Lambda Runtime Interface
Emulator + MinIO on a laptop**. Full data with conditions and commentary:
[`doc/measurements.edn`](doc/measurements.edn).

These are not real-AWS numbers. Numbers on real Lambda against real S3 differ —
most obviously in per-GET latency, which is the term everything else here is
built out of; EACL's field report (README) is the real-AWS data point.

### A Lambda has three kinds of "start" and they cost wildly different amounts

Clojure-only image, reader function, one tenant (201 records):

| phase | | time | GETs |
|---|---|--:|--:|
| **INIT** | container boots: JVM, namespaces, pool | 238 ms | 0 |
| **FIRST** | first invocation for a tenant here: connection opens, node cache empty | 3218 ms | 18 |
| **WARM** | every invocation after | **18.7 ms** | 1 |

Only the middle row is what people mean by "cold start", and the JVM is not what
makes it expensive — 238 ms of that 3456 ms is the JVM. The rest is round trips to
the object store, one blocking GET at a time.

That last GET never goes away on a reader: it is the branch-head re-read that makes
a reader *follow* the writer. It is the cost of having no coordination service.

**How much of FIRST is round trips and how much is class loading has to be
measured, not assumed.** Running this repo's own (much smaller) example through the
same rig: the reader's first invocation took **2774 ms for 4 GETs**, and opening a
*second* cold tenant in the same warm container took **78.8 ms for 3 GETs** — one
GET apart, 35× the time. Against a localhost store with a one-note tenant there is
almost nothing to fetch, so nearly all of that first invocation is datahike's
namespaces loading and compiling. It is the same term that appears as the 4190 ms in
the polyglot table below.

The two costs need different fixes and are not substitutes: SnapStart removes the
class loading, warming removes the round trips.

### Warming at INIT moves the work off the request path — and is a wash without SnapStart

`d/warm-db` walks the index breadth-first and fetches each level concurrently
instead of discovering it one blocking round trip at a time. Called during INIT:

| | INIT | FIRST invoke | first invoke GETs |
|---|--:|--:|--:|
| no warm | 238 ms | 3218 ms | 18 |
| warm at INIT | 3495 ms | **205 ms** | **1** |

**15.7x faster first request. And a slightly slower cold path overall:**
238 + 3218 = **3456 ms** against 3495 + 205 = **3700 ms**, because the warm fetches
more (24 GETs) than the query needed (18).

Warming at INIT pays **only when INIT is amortised**:

- under **SnapStart**, where INIT is paid once per *published version* rather than
  per container, and the snapshot captures deserialized nodes already in the heap;
- or in a container that serves **many invocations** — which is exactly the property
  Cloud Run has by default and classic Lambda does not.

So it is off by default (`DHS_WARM_TENANTS=""`). Do not enable it blind.

**Measured again in this repo, it came out the other way — and the reason is worth
more than either number.** Two fresh reader containers, identical but for
`DHS_WARM_TENANTS`, on a 400-note tenant (RIE + MinIO on localhost):

| | INIT | FIRST invoke | first GETs | cold path |
|---|--:|--:|--:|--:|
| no warm | 63 ms | 2281 ms | 15 | 2344 ms |
| warm at INIT | 539 ms | **504 ms** | **1** | **1044 ms** |

A clear win, where the prototype measured a slight loss. Two differences, both of
which you can check for your own workload:

- **Over-fetch.** The prototype's warm fetched 24 GETs where the query needed 18,
  at +20 ms RTT, and paid for the extra six. Here the warm fetched exactly the 15
  the query would have — `d/warm-db` reported `{:fetched 11 :rounds 1 :by-index
  {:eavt 6 :aevt 5}}` — so there was nothing to lose. Tune `:depth`/`:budget` and
  watch that report.
- **Class loading again.** Walking the index at INIT loads the connect/fetch
  namespaces there, leaving the first invocation only the query path to load: 2281
  ms → 504 ms *on one GET*. That saving is not object-store work at all and is
  invisible in a GET count.

On real S3, with real RTT, the over-fetch term grows and this can flip back. The
honest rule is not "warm always pays" or "warm never pays" — it is *measure it, and
watch whether the warm fetches more than the query does*.

### Writes

| | PUTs | time |
|---|--:|--:|
| steady-state commit (unfenced, datahike 0.8.1779) | 1–2 | 50–70 ms |
| steady-state commit (**fenced**, datahike 0.8.1792) | 1–2 | **66 ms** |
| a tenant's **first** write | ~16 | — |

The fence costs one branch-head GET per commit batch (the shared writer re-reads
before applying) plus the conditional PUT, and at localhost RTT that is invisible —
66 ms sits inside the old unfenced band. On real S3 budget ~+1 GET (≈20 ms) per
batch; transactions that queue while one commits share a single head read.

The first write is `create-database` + schema install (+ migrations, in the source
prototype). It is not steady state, and it is worth pre-creating tenants if first-
request latency matters.

### The polyglot path: SQL over object storage, in one Lambda

A stock `psycopg` handler and a JVM running
[pg-datahike](https://github.com/replikativ/pg-datahike) as an **external
extension**, sharing one execution environment. The handler connects to
`127.0.0.1:5432` and never learns that the "Postgres" is Datahike over a bucket. A
tenant *is* a PG database name, so `postgresql://localhost/acme` routes to that
tenant's connection with no `WHERE tenant_id` anywhere.

| | time |
|---|--:|
| extension init, total | **7311 ms** |
| ├─ tenant pool | 51 ms |
| ├─ tenant open | 3070 ms |
| └─ pg-datahike **class loading** | 4190 ms |
| handler lazy connect | 265 ms |
| first read | 397 ms |
| **warm read** | **8.52 ms** |
| write 20 rows | 2350 ms (~59 ms/statement) |

The 4190 ms term was originally mislabelled "socket bind". Measured directly,
`pg/start-server` takes **23 ms cold and 1 ms warm** — the 4.2 s is
`requiring-resolve` loading the pg-datahike namespace tree. That correction changes
the fix: class loading is **not lazy-able** (you need the code to serve SQL) but it
is exactly what a heap snapshot removes. SnapStart, native-image, or a long-lived
container are the levers; restructuring pg-datahike is not. It is also why the
Clojure-only path is 238 ms — no pg-datahike to load.

---

## The reader's 2 PUTs on connect — root-caused, fixed upstream

What was measured, before the fix: Measured in this repo
(see `:this-repo` in `doc/measurements.edn`): opening a tenant costs **2 PUTs for
every role**, writer and reader alike, on a database that already exists with no
schema change and no transaction. The only object that changes is
`<store-id>_.konserve-metadata`, the per-store marker konserve's store-connect path
rewrites. It is not the branch head, so it does not endanger a commit — but it has
two practical consequences:

- **a reader given read-only object-store credentials cannot connect today.** Do not
  hand a reader function a read-only IAM policy expecting it to work.
- a cold tenant open is 2 PUTs, not 0, in any cost model.

**Root cause, found with a request-counting proxy** (replikativ/datahike-serverless#6):
both PUTs hit the same marker key. konserve's `connect-default-store` called
`-create-store` unconditionally on every connect — "auto-create on connect"
implemented as "create on connect" — and datahike connects the store twice
(a probe in `database-exists?`, then `connect`). Fixed in
[konserve#179](https://github.com/replikativ/konserve/pull/179): connect probes
first and creates only what is missing, so an existing store's connect is a
HEAD, read-only credentials work, and a cold tenant open is 0 PUTs. The
end-to-end re-verification on read-only IAM lands with the release.

## The custodian gap

Datahike's storage GC (`d/gc-storage`) must run in a **writer** process. Without
one the bucket only grows: measured on one tenant, 197 objects of which 40 were
live after GC — **80% garbage** — and a third-party production bucket was found
at **16× its live state** for want of a custodian. Since writers are fenced
(datahike ≥ 0.8.1792) the custodian no longer has to be *the* writer: any
scheduled writer-role process can sweep next to live functions, and the
datahike 0.8.1801 GC round (durable roots, the sweep floor for shared writers)
is the machinery it needs. Tracked in
[#2](https://github.com/replikativ/datahike-serverless/issues/2).
