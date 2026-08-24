# SnapStart / CRaC: cold starts measured, and what actually moves them

The rig: the app under a CRaC JDK (Zulu 21 + criu), checkpointed the way Lambda
SnapStart does — after INIT, before the socket binds — restored and timed to
the first *correct* response. Store: MinIO behind toxiproxy at +20 ms per
request (object-store-shaped RTT). Tenant: ~1000 notes. Driver:
`dev/crac_harness.clj`.

## The numbers

| scenario | first correct response |
|---|--:|
| cold JVM, no AOT (source compile) | ~47 s |
| cold AOT container (RIE rig, earlier) | ~2.3–3.5 s |
| CRaC restore, image **fresh** | **67–95 ms** |
| CRaC restore, image **300 notes stale**, naive | 3.8–4.7 s |
| … + data re-warm on restore only | 5.8–6.6 s (!) |
| … + **code + data warm at checkpoint, delta re-warm on restore** | **0.94–1.16 s** |

(First-ever restore of an image on a host adds ~2 s of page-cache warming, once.)

## The three findings that matter

**1. A snapshot's cache never goes stale.** Datahike's nodes are immutable and
content-addressed, so every node the image carries is valid forever — a
restored reader needs one branch-head GET plus exactly the delta committed
since the checkpoint. "Redeploy the warm Lambda daily" is therefore sound: the
cost of staleness is proportional to a day's writes, not to the database.

**2. The class graph is part of the image, and forgetting it costs more than
cold data.** The naive stale restore (3.8–4.7 s) was NOT mostly storage: a
reader checkpointed without ever running a query ships an image whose first
request loads the whole query-engine class graph — measured as a ~5 s tail
that no data warm could touch, which is why "re-warm on restore" alone made
things *worse* (5.8–6.6 s: same class loading, plus the warm). The fix is one
synthetic request through the real handler before the checkpoint. Warm the
code path per PUBLISH; warm the data delta per RESTORE.

**3. The delta re-warm belongs on restore, at `:with-leaves`.** After restore,
`warm-db` fetches the delta in concurrent waves (240 ms for ~300 notes at
+20 ms) instead of the first query discovering it serially (~248 round trips,
~5 s). `:interior` is not enough here — the leaves are what the query reads.

## Scale: 50k notes (~200k datoms, 1520 store objects)

Re-run against a tenant 50x larger (cache 16384 entries, warm budget 6000 —
the tree is 1174 nodes):

| | |
|---|--:|
| warm, cold cache (1174 nodes, width 64) | 2810 ms |
| **warm again, same 1174 restores** | **120 ms** |
| full 50k query, hot | 1124 ms |
| CRaC fresh restore → first response (6.2 MB JSON) | 2.4–3.8 s |
| CRaC stale (+1000 notes) → first response | 3.9–4.3 s (rewarm 1157 ms, listen +1.1 s) |

The double-warm row is the answer to "does warming respect what the image
already holds": the second warm issues the **identical 1174 restores in 120 ms
with zero network** — every one a cache hit. `:fetched` counts restores
issued, hits included; only misses touch S3. Two boundaries to that guarantee:
the cache is ENTRY-counted (size it to the tree, here 16384), and datahike's
budget clamp (0.8x cache) exists precisely so a warm cannot evict what it just
fetched.

At this scale the storage-side cold start is still comfortably inside budget —
restore ~0.3 s + delta rewarm ~1.2 s — and what blows past 2 s is the DEMO
ROUTE, which returns every note in one 6.2 MB response (1.1 s of query CPU
plus serialization, paid on any server however warm). A paginated or point
read serves within ~100 ms of listen. Size budgets to what the first REQUEST
reads, not to the database.

A tiered konserve store (memory/file/LMDB frontend over S3) generalizes the
same property beyond the heap image: the warm populates the near tier, reads
check it first, and — unlike the CRaC heap cache — a file/LMDB tier survives
process death on platforms without snapshots. CRaC makes the heap the tier;
tiered-LMDB is the portable spelling of the same idea. Not yet wired here.

## Operational notes

- **S3 client sockets fail the checkpoint** (5 pooled keep-alives, observed via
  the CheckpointException's suppressed list). No konserve-s3 change needed: the
  SDK's idle reaper closes them after 60 s, so the harness settles 75 s before
  checkpointing. On real SnapStart this is paid once per publish.
- **Multi-tenant bloat**: the image here is ~330–350 MB and almost all of it is
  JVM + loaded code, which every tenant shares; a warmed tenant adds only its
  node-cache bytes. Warming the top-K tenants into the image and letting the
  rest ride on the shared code-warm captures most of the win — finding #2 is
  the biggest term and it is tenant-independent.
- **GET pressure**: a fresh-image restore costs ~1 GET; a stale one costs the
  delta, fetched in waves. This is also the answer to hammering S3 — the
  snapshot is a local, always-valid cache of the entire read set at publish
  time.

## Open follow-ups

- Real AWS SnapStart run (zip-packaged managed runtime — SnapStart does not
  take container images; the harness's checkpoint placement maps 1:1 onto
  `beforeCheckpoint`/`afterRestore` CRaC hooks).
- The "one big S3 value" image for platforms without snapshots: a custodian
  process periodically serializes the store's reachable set into one blob;
  cold open = 1 GET + inflate into the node cache + delta. Same math as the
  CRaC image, portable to Cloud Run/Fly, and it composes with `warm-db` for
  the delta. Not yet built.
- Firecracker locally (KVM present) to measure microVM snapshot restore
  itself; CRaC-on-criu above already bounds the JVM-side term.
