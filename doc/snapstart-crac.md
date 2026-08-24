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
