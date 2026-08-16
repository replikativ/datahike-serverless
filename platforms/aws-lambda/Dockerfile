# The Clojure-only Lambda image.
#
# Two stages so the local rig and a real deployment share ONE artifact: the build
# stage produces the AOT'd uberjar (the adapter needs :gen-class — the runtime loads
# a handler CLASS by name), and the runtime stage is AWS's own base image.
#
# That base image ships the Runtime Interface Emulator, so this container behaves
# the same locally as on Lambda:
#   POST http://localhost:9101/2015-03-31/functions/function/invocations
# which is what bin/demo drives.
#
# Build from the REPO ROOT:
#   docker build -f platforms/aws-lambda/Dockerfile -t datahike-serverless-lambda .
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app
COPY deps.edn build.clj ./
RUN clojure -P -T:build uber || true
COPY . .
RUN clojure -T:build uber

FROM public.ecr.aws/lambda/java:21
COPY --from=build /app/target/datahike-serverless-lambda.jar ${LAMBDA_TASK_ROOT}/lib/
# A RequestStreamHandler is named by its class alone.
CMD [ "datahike_serverless.platform.aws_lambda" ]
