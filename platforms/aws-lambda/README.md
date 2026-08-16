# platform: AWS Lambda

Two images over the same application code:

- **`Dockerfile`** — Clojure-only. The adapter translates Function-URL payload v2
  into ring and back. This is the verified path.
- **`Dockerfile.pg`** — polyglot: a Python `psycopg` handler plus a JVM running
  pg-datahike as an **external extension** in the same execution environment. Less
  verified; read the status table in the root README before trusting it.

## Local

```bash
docker compose up -d --build     # minio + writer (:9101) + reader (:9102)
./bin/demo                       # write, read cold, read warm, print the phases
docker compose --profile pg up -d --build    # polyglot image on :9103
```

Invoke by hand:

```bash
curl -XPOST localhost:9101/2015-03-31/functions/function/invocations \
  -d '{"version":"2.0","rawPath":"/health","rawQueryString":"",
       "requestContext":{"http":{"method":"GET"}},"isBase64Encoded":false}'
```

### RIE is faithful here — and one wrapper you must not add

AWS's base image ships the Runtime Interface Emulator, and RIE embeds the **same
`rapid` orchestrator** Lambda uses. It enables extensions by default
(`rie/run.go`, `SetExtensionsFlag(true)`) and runs `doInitExtensions` before
`doRuntimeBootstrap` (`rapid/handlers.go`).

So **no entrypoint wrapper is needed** to start extensions locally — and adding one
is actively harmful: exporting `AWS_LAMBDA_RUNTIME_API` makes
`/lambda-entrypoint.sh` skip RIE entirely, because it concludes it is already
running on Lambda. An earlier version of this shipped exactly that wrapper. It was
wrong.

The one real gap: **RIE does not forward agent stdout** to the container log. Probe
the socket to decide whether the extension is ready; never `docker logs | grep`.
The extension wrapper here does exactly that, and the JVM's `PG-READY …` line
exists only so the breakdown is recoverable from a local run.

## Deploying to real AWS

**Not done, and not scripted.** There is no Terraform here yet
([issue](https://github.com/replikativ/datahike-serverless/issues)). What a
deployment has to get right, from the measurements:

- **Two functions, not one.** A reader function with no concurrency limit; a writer
  function at `reserved_concurrent_executions = 1` — which narrows the two-writer
  window without closing it, until datahike#878. Point them at the same bucket.
- **Function URLs, not API Gateway.** API Gateway is a large share of a serverless
  bill and buys nothing this app needs.
- **The role env var matters.** `DHS_ROLE=reader` is what makes a reader re-read the
  branch head on every deref. Without it the reader serves a frozen snapshot and
  claims write authority over the writer's bucket.
- **SnapStart is the lever for INIT**, and it is the only condition under which
  `DHS_WARM_TENANTS` is unambiguously worth enabling — the snapshot is taken after
  INIT, so warmed state is restored with the container. Unmeasured here.
- **IAM**: the function needs `s3:GetObject` / `PutObject` / `DeleteObject` /
  `ListBucket` on the tenant bucket. **Including the reader** — a read-only policy
  looks like the obvious second line of defence for the reader/writer split, and it
  does not work today: connecting to a tenant writes the per-store
  `…_.konserve-metadata` marker (2 PUTs, measured, every role). See the root README.
- **Nothing collects garbage.** See the custodian section in the root README.

## Files

| | |
|---|---|
| `src/…/aws_lambda.clj` | the adapter: INIT `defonce`, event↔ring, phase instrumentation |
| `src/…/aws_lambda/pg_extension.clj` | the JVM half of the extension: pool → PG server on loopback |
| `extension/pg-datahike` | the external extension: registers SHUTDOWN only, boots the JVM, waits for the socket before its first `next` |
| `extension/app.py` | a stock psycopg handler that connects **lazily** — read the docstring before changing it |
