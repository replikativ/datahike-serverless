# datahike-serverless

Run [Datahike](https://github.com/replikativ/datahike) — a durable Datalog
database whose storage is an object store — from ephemeral compute. One
database per tenant, no server between the function and the bucket, and
nothing running when nobody is asking.

The repo is a measured answer to one question: *what does a database on S3
cost when the compute that opens it disappears after every request?* It ships
a small multi-tenant example, the Lambda adapter, and every number it claims,
with the conditions the number was taken under.

## Validated on AWS

**[EACL](https://github.com/theronic/eacl)** (Enterprise Access ControL —
a ReBAC authorization library by Petrus Theron, backed by Datahike) runs its v8
demo on this approach in `us-east-1`, and reports:

- **Lambda starts in 1–3 s**, on a 1024 MB function (down from 1536 MB, −50% CPU)
- **≈ $1/year total**, all in
- no S3 GETs on the request path; a durable cache written to S3 Express on
  shutdown; snapshot-based consistency modes (`minimize-latency` /
  `at-least-as-fresh` / `at-exact-snapshot` / `fully-consistent`) — the last two
  are Datahike's `as-of` and `sync` surfacing as product features

Those are EACL's numbers on real Lambda against real S3, against a deliberately
pathological schema; this repo's own measurements below are from the Lambda
Runtime Interface Emulator and MinIO on a laptop, with latency injected where it
matters. The two agree on the shape: the JVM is not the cost, round trips and
class loading are, and both are removable.

## The numbers at a glance

Reader function, one tenant, RIE + MinIO — a Lambda has three kinds of "start":

| phase | | time | GETs |
|---|---|--:|--:|
| **INIT** | container boots: JVM, namespaces, pool | 238 ms | 0 |
| **FIRST** | first invocation for a tenant: connection opens, cache empty | 3218 ms | 18 |
| **WARM** | every invocation after | **18.7 ms** | 1 |

Only the middle row is a "cold start", and the JVM is 238 ms of it. The rest
is round trips, one blocking GET at a time — which `d/warm-db` turns into
concurrent waves (first invocation 2281 ms → 504 ms on this repo's tenant).

**Snapshots (SnapStart / CRaC) change the game.** Checkpoint after INIT — data
warm *and* one request through the real handler — and restore. 50k-note
tenant, S3 at +20 ms, image **1000 notes stale**:

| | first *correct* response |
|---|--:|
| cold JVM, no AOT | ~47 s |
| cold AOT container | ~2.3–3.5 s |
| snapshot restore, fresh image | **67–95 ms** |
| snapshot restore, 1000 notes stale | **0.94–1.12 s** |

A snapshot's node cache never goes stale (nodes are immutable and
content-addressed — staleness costs the delta, not the database), the class
graph must be *in* the image (a checkpoint that never ran a query ships a ~5 s
first-request tail no data warm can touch), and the delta re-warm belongs on
restore. Runbook and phases: [doc/snapstart-crac.md](doc/snapstart-crac.md).

**Writes are fenced.** Two writer environments overlapping — every deploy —
cannot lose a commit: the branch head is published with a conditional PUT
(datahike ≥ 0.8.1792), and this repo *demands* it (`:require-fencing`). Measured:
two JVMs, 30 transacts each on one tenant, 60/60 survive. A fenced commit costs
66 ms at local RTT, inside the unfenced band.

Everything above, with conditions and the runs that didn't go the expected way:
[doc/measurements.md](doc/measurements.md) · raw data
[doc/measurements.edn](doc/measurements.edn).

## Try it

Nothing installed but a JVM and the Clojure CLI:

```bash
clj -M:run                      # HTTP server on :8080, file store under data/
curl -XPOST localhost:8080/t/acme/notes \
     -H 'content-type: application/json' \
     -d '{"title":"first","body":"hello"}'
curl localhost:8080/t/acme/notes            # newest first, ?limit= up to 1000
```

The Lambda path, locally, against MinIO — no AWS account:

```bash
cd platforms/aws-lambda
./bin/demo                      # build, write, read cold, read warm, print the phases
```

`bin/demo` drives the Runtime Interface Emulator from AWS's own base image, so
the containers behave the way they will on Lambda. Deploying for real:
[platforms/aws-lambda/README.md](platforms/aws-lambda/README.md).

## How it works

**Roles.** `DHS_ROLE=writer` transacts in-process with a fenced shared writer.
`DHS_ROLE=reader` gets a remote writer backend, so every `@conn` re-reads the
branch head and *follows* the writer with no connection to it — that one GET is
the price of having no coordination service. A reader refuses to create
databases, install schema, or accept writes (405): a reader that installs schema
*is* a second writer.

**Warming.** `DHS_WARM_TENANTS` names tenants to warm during INIT with
`d/warm-db`, a budget-bounded breadth-first walk that fetches each index level
concurrently. It pays when INIT is amortised — under SnapStart, or on a platform
whose instances serve many requests — and the report it returns
(`:fetched`, `:by-level`, `:budget-exhausted?`) tells you whether it over-fetched
relative to your queries. Off by default; measure before enabling.

**Snapshots.** `dev/crac_harness.clj` checkpoints the way SnapStart does: after
INIT, before the socket binds, with the code path exercised. On restore it
re-warms the delta and then serves. The same shape maps onto Lambda SnapStart's
`beforeCheckpoint`/`afterRestore` hooks (zip-packaged runtime; SnapStart does
not take container images).

**The polyglot shape.** A stock `psycopg` handler and a JVM running
[pg-datahike](https://github.com/replikativ/pg-datahike) as an external Lambda
extension: `postgresql://localhost/acme` routes to that tenant's connection and
the handler never learns the "Postgres" is Datahike over a bucket. Its whole
init cost (~7 s, almost all class loading) is exactly what a snapshot absorbs.

### Two requirements, not two tips

1. **Connect lazily, never at module level** (polyglot path). Function init has
   a hard ~10 s budget and extension init runs concurrently with it; a
   module-level `psycopg.connect` races the JVM and then blocks init against
   that budget. Connect on first invoke: the first request pays ~265 ms, later
   ones nothing. An AWS requirement, not a local artefact.
2. **Demand the fence.** Datahike fences where the store can compare-and-set
   and *skips silently* where it cannot. Set `:require-fencing` (`:global` on
   S3, `:machine` on a file store) so `connect` refuses an unfenceable store.
   Reserved concurrency 1 is a cost knob — a lost head race is a wasted apply —
   not a correctness one.

## Configuration

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

## Layout

```
src/datahike_serverless/
  config.clj        store profile (file | s3) and ROLE (writer | reader)
  pool.clj          per-tenant connection registry: LRU-bounded, pinned, read-only-aware
  app.clj           pool + routes -> one ring handler; the INIT-time warm; a jetty main
  example/          the domain: notes. Two endpoints, paginated. Replace it.
dev/crac_harness.clj  the SnapStart-shaped checkpoint/restore rig
platforms/aws-lambda/
  src/              the adapter: Function-URL payload v2 <-> ring, phase instrumentation
  extension/        the polyglot shape: external extension + a psycopg handler
  Dockerfile, Dockerfile.pg, docker-compose.yml, bin/demo
doc/
  measurements.md   the full narrative     measurements.edn  raw data
  snapstart-crac.md the snapshot results and runbook
```

`app/build` returns a ring handler **without binding a port**. That is the
seam: the Lambda adapter calls it during INIT and translates events; a Cloud
Run adapter would call `-main` and get the same handler over HTTP.

## Status

| | |
|---|---|
| **Real AWS** | Validated in the field by [EACL](https://github.com/theronic/clj-eacl) (us-east-1, 1024 MB, 1–3 s starts, ≈$1/year). This repo's own deploy still needs IaC ([#1](https://github.com/replikativ/datahike-serverless/issues/1)). |
| **Clojure-only Lambda path** | Verified end to end: RIE + MinIO, writer and reader functions, phases logged. |
| **Fenced writes** | Verified: two overlapping writer JVMs, 60/60 commits survive. |
| **Warming, snapshots** | Verified and measured; see the docs linked above. |
| **Read-only reader credentials** | Root-caused (konserve wrote its store marker on every connect) and fixed in [konserve#179](https://github.com/replikativ/konserve/pull/179); re-verification on read-only IAM lands with the release ([#6](https://github.com/replikativ/datahike-serverless/issues/6)). |
| **Polyglot (pg-datahike) path** | Builds; last verified end to end on pg-datahike 0.1.61 with a workaround. The pinned version and the natural upsert have not been re-run in this image, and pg-datahike has moved far since — re-verify before relying on it ([#4](https://github.com/replikativ/datahike-serverless/issues/4)). |
| **GC custodian** | Not built, and required: without it the bucket only grows (measured 80% garbage on one tenant). Fencing makes it a scheduled job rather than a privileged one ([#2](https://github.com/replikativ/datahike-serverless/issues/2)). |
| **Cloud Run profile** | Not built; expected to win on the term that dominates here, and not claimed until measured ([#7](https://github.com/replikativ/datahike-serverless/issues/7)). |

Adding a platform is adding a directory under `platforms/`, not a repo.

## Related

[**datahike-saas-starter**](https://github.com/replikativ/datahike-saas-starter)
is the companion: the per-tenant model itself — pooling, lazy per-tenant
migrations, export/delete/restore, streaming read replicas, blobs — plus the
economics. Roughly: Lambda is cheaper than an instance below ~1,000
requests/tenant/month and loses above it, because GB-seconds dominate once
traffic is real. The quiet-tenant regime is also the small-tenant regime, which
is why a cold start of a handful of GETs is tolerable there. This repo is the
narrow question of what happens when the compute is ephemeral.

## License

MIT
