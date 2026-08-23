# datahike-serverless

Running [Datahike](https://github.com/replikativ/datahike) — a durable Datalog
database whose storage is an object store — from ephemeral compute. One database
per tenant, no server between the function and the bucket, and nothing running when
nobody is asking.

**AWS Lambda is the first platform here, not the only one, and it is the hardest of
them for this workload.** A classic Lambda execution environment serves one request
at a time, so the warm state you paid to build is amortised over a single request
before the next container starts cold again. Platforms with per-instance request
concurrency — Cloud Run, Fly, Container Apps — let one warm cache serve many
requests, which is the dominant cost in every measurement below. Cloud Run is
therefore *expected* to look better here. That expectation is not yet measured, and
this repo will not claim it until it is.

Adding a platform is adding a directory under `platforms/`, not a repo.

> ⚠️ **Experimental.** Reads are safe and unconstrained. Writes are **fenced**
> since datahike `0.8.1792`: the branch head is published with a conditional PUT,
> so overlapping writer environments reject instead of overwrite — measured, two
> JVMs, 60/60 commits survive. See
> [Two requirements](#two-requirements-not-two-tips). Nothing here has been run
> on real AWS.

---

## The measurements

All of these come from the prototype this repo was extracted from
([datahike-saas-starter](https://github.com/replikativ/datahike-saas-starter),
`doc/results/coldstart.edn`), measured against the **AWS Lambda Runtime Interface
Emulator + MinIO on a laptop**. Full data with conditions and commentary:
[`doc/measurements.edn`](doc/measurements.edn).

**Nothing has been run on real AWS.** Numbers on real Lambda against real S3 will
differ — most obviously in per-GET latency, which is the term everything else here
is built out of.

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

## Two requirements, not two tips

### 1. The handler must connect LAZILY, never at module level

Function init has a hard **~10 s budget**; exceed it and the platform kills and
restarts the execution environment. Extension init and Runtime init run
**concurrently** — Lambda waits for both to signal `Next`, but it does not hold the
runtime while an extension boots. pg-datahike needs ~7.3 s to be ready.

A module-level `psycopg.connect` therefore *races* the JVM and then *blocks Function
init* against that budget. This killed every early attempt. Connecting on first
invoke moves the wait into the invoke phase, whose timeout is the function's
(seconds to minutes): the first request pays 265 ms, every later one pays nothing.

This is an AWS requirement, not a local artefact. See
[`platforms/aws-lambda/extension/app.py`](platforms/aws-lambda/extension/app.py).

### 2. Writes are fenced — demand it, don't assume it

Datahike readers are unconstrained: any number, in any number of processes, on the
same bucket, with no coordination. Writers used to be the caveat: two live writers
on one tenant could lose a commit, because the branch head was written
unconditionally. That was
[datahike#878](https://github.com/replikativ/datahike/issues/878), and it is
closed: since `0.8.1792` a `:self` writer defaults to shared ownership — it
re-reads the branch head before each batch and publishes it with a **conditional
PUT** (`If-Match` on S3, evaluated by the store). The loser of a head race is
rejected and its transaction re-applied against the head that moved, bounded by
`:head-conflict-retries` (default 3). Nothing is lost and nothing partially
applied: everything a commit writes before the head flip is immutable and
content-addressed, so a rejected commit leaves collectable orphans, never a
dangling pointer.

Measured here, the deploy-overlap scenario itself: two writer JVMs interleaving 30
transacts each on **one tenant** over MinIO — all 60 report success, all 60
present afterwards. On `0.8.1779` this exact run lost commits.

Two disciplines remain, and they are cheap:

- **Demand the fence.** Datahike fences where the store can compare-and-set and
  *skips silently* where it cannot. This repo's writer config therefore sets
  `:require-fencing` (`:global` on the s3 profile, `:machine` on file — see
  `resources/config.edn`), which makes `connect` refuse a store that cannot fence
  that far instead of running unprotected.
- **Reserved concurrency 1 is now a cost knob, not a correctness one.** A lost
  head race is a wasted apply plus a jittered backoff, so keeping writers
  serialized is still the way to be fast. It is no longer what safety rests on:
  deploys and container replacement overlapping two environments is exactly the
  window the fence closes.

Readers are safe today, at any concurrency, and the reader/writer split is enforced
in code: a reader refuses to create databases or install schema (`pool.clj`), and
refuses writes with a 405 (`app.clj`). That is not politeness — a reader that
installs schema *is* a second writer. The source prototype measured exactly that
bug: a reader performing 2 PUTs on first tenant open, from its migrations.

**A reader still writes 2 objects, and that is not fixed.** Measured in this repo
(see `:this-repo` in `doc/measurements.edn`): opening a tenant costs **2 PUTs for
every role**, writer and reader alike, on a database that already exists with no
schema change and no transaction. The only object that changes is
`<store-id>_.konserve-metadata`, the per-store marker konserve's store-connect path
rewrites. It is not the branch head, so it does not endanger a commit — but it has
two practical consequences:

- **a reader given read-only object-store credentials cannot connect today.** Do not
  hand a reader function a read-only IAM policy expecting it to work.
- a cold tenant open is 2 PUTs, not 0, in any cost model.

---

## Try it

Nothing installed but a JVM and the Clojure CLI:

```bash
clj -M:run                      # plain HTTP server on :8080, file store under data/
curl -XPOST localhost:8080/t/acme/notes \
     -H 'content-type: application/json' \
     -d '{"title":"first","body":"hello"}'
curl localhost:8080/t/acme/notes
```

The Lambda path, locally, against MinIO — no AWS account:

```bash
cd platforms/aws-lambda
./bin/demo                      # build, write, read cold, read warm, print the phases
```

`bin/demo` drives the Runtime Interface Emulator that ships in AWS's own base image,
so the containers behave the way they will on Lambda.

---

## Layout

```
src/datahike_serverless/
  config.clj        store profile (file | s3) and ROLE (writer | reader)
  pool.clj          per-tenant connection registry: LRU-bounded, pinned, read-only-aware
  app.clj           pool + routes -> one ring handler; the INIT-time warm; a jetty main
  example/          the domain: notes. Two endpoints. Replace it.
platforms/
  aws-lambda/
    src/            the adapter: Function-URL payload v2 <-> ring, phase instrumentation
    extension/      the polyglot shape: external extension + a psycopg handler
    Dockerfile      Clojure-only image
    Dockerfile.pg   polyglot image
    docker-compose.yml, bin/demo
doc/measurements.edn
```

`app/build` returns a ring handler **without binding a port**. That is the seam:
the Lambda adapter calls it during INIT and translates events; a Cloud Run adapter
calls `-main` and gets the same handler over HTTP. One wiring, not one per platform.

### Configuration

| env | default | |
|---|---|---|
| `DHS_STORE` | `file` | `file` \| `s3` |
| `DHS_ROLE` | `writer` | `writer` \| `reader` |
| `DHS_STORE_CACHE` | `2048` | node cache entries; bounds how much warmth you can hold |
| `DHS_MAX_HOT` | `64` | tenant connections kept open (LRU beyond) |
| `DHS_WARM_TENANTS` | *(empty)* | comma-separated slugs to warm during INIT |
| `DHS_WARM_DEPTH` | `with-leaves` | `interior` \| `with-leaves` \| an integer |
| `DHS_WARM_BUDGET` | `2000` | max nodes fetched; clamped to 0.8× `DHS_STORE_CACHE` |
| `DHS_LAMBDA_STATS` | *(off)* | `1` logs per-phase GET/PUT counts (not reentrant) |
| `S3_BUCKET`, `S3_ENDPOINT`, `AWS_*` | | leave `S3_ENDPOINT` unset for plain AWS |

`DHS_ROLE` is not a permission flag. It changes the datahike config: a writer gets
`:writer {:backend :self}` (streaming — `@conn` reads its own in-memory atom), a
reader gets a non-streaming backend so **every `@conn` re-reads the branch head**
and follows the writer. A reader left on the default serves a frozen snapshot
forever, silently.

---

## Status: what is verified, what is only ported

| | |
|---|---|
| **Clojure-only Lambda path** | Verified end-to-end in this repo: RIE + MinIO, write through the writer function, cold and warm reads through the reader, phases logged. The headline numbers above are the *source prototype's*, on a 201-record tenant; this repo's own run (one-note tenant, `:this-repo` in `doc/measurements.edn`) confirms the path works and the phase structure holds, and is far too small to restate them. |
| **Uberjar build** | `clj -T:build uber` and `clj -T:build uber-pg` both verified in this repo (the pg jar builds; it has not been *run*). |
| **`d/warm-db` at INIT** | Verified: it runs, reports what it fetched, and removes the GETs from the first invocation — see the table above. |
| **Example app** | Verified through the Lambda adapter on the `file` store, and as a plain HTTP server via `clj -M:run`. |
| **Polyglot (pg-datahike) path** | Verified end-to-end in the **source prototype** against RIE + MinIO — but with **pg-datahike 0.1.61 and a `DELETE`+`INSERT` workaround**. This repo pins 0.1.63 and `app.py` emits the natural parameterised `INSERT … ON CONFLICT` upsert (fixed upstream, PR #30). **That has not been re-verified in this image.** Do not assume it works. ([#4](https://github.com/replikativ/datahike-serverless/issues/4)) |
| **Real AWS** | Never run. No account, no deploy, no SnapStart measurement. |
| **Terraform / IaC** | **Not built.** Required for a real deployment. ([#1](https://github.com/replikativ/datahike-serverless/issues/1)) |
| **GC custodian** | **Not built, and required.** See below. ([#2](https://github.com/replikativ/datahike-serverless/issues/2)) |

### The custodian gap

Datahike's storage GC (`d/gc-storage`) must run **in a writer JVM**, and a fully
serverless deployment has nowhere to put one. Without it the bucket only grows:
measured on one tenant, 197 objects of which 40 were live after GC — **80% garbage**
— and a third-party production bucket was found at **16× its live state** for want
of a custodian.

This is not an optimisation. Plan the custodian into any real deployment: a
scheduled task in a long-lived process, holding the writer role, per tenant.

---

## Related

[**datahike-saas-starter**](https://github.com/replikativ/datahike-saas-starter) is
the companion: the per-tenant model itself — tenant pooling, lazy per-tenant
migrations, export/delete/restore, streaming read replicas, blobs — plus the
economics (cost model, benchmarks, the ladder from a directory on disk to a shared
bucket). This repo is the narrow question of what happens when the compute is
ephemeral; that one is what the compute is *doing*.

Roughly: Lambda is cheaper than an instance below ~1,000 requests/tenant/month and
loses above it, because GB-seconds dominate once traffic is real. The quiet-tenant
regime is also the small-tenant regime, which is why a cold start of a handful of
GETs is tolerable there.

## License

MIT
