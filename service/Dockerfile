# One image for every lab service. Stage 1 builds every deployable with the Gradle wrapper, stage 2
# keeps only the installed distributions, and docker-compose picks the launcher per service.
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /src
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle build.gradle gradle.properties ./
COPY core core
COPY shared shared
COPY domain domain
COPY api api
COPY worker worker
COPY loadgen loadgen
COPY noisy noisy
COPY pgobserver pgobserver
COPY tool tool

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew installDist --no-daemon --console=plain -x test -x ktlintCheck \
    && mkdir -p /out \
    && for d in api/*/build/install/* worker/*/build/install/* loadgen/build/install/* noisy/build/install/* pgobserver/build/install/* tool/*/build/install/*; do cp -r "$d" /out/; done

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl iproute2 && rm -rf /var/lib/apt/lists/*

WORKDIR /run
COPY --from=build /out /run/app
# The controller runs it through docker exec in the api container, which has NET_ADMIN.
COPY --chmod=755 docker/netem.sh /run/netem.sh

# MaxRAMPercentage keeps the heap under the container limit; the lab's memory-leak fault allocates
# off heap on purpose, so the kernel OOM killer ends the process, not a Java OutOfMemoryError.
ENV JAVA_OPTS="-XX:InitialRAMPercentage=25.0 \
  -XX:MaxRAMPercentage=50.0 \
  -XX:MaxMetaspaceSize=128m \
  -XX:ReservedCodeCacheSize=64m \
  -XX:+UseSerialGC \
  -Xss512k"

ENV SERVICE_LAUNCHER=/run/app/labctl/bin/labctl

CMD ["bash", "-c", "exec \"$SERVICE_LAUNCHER\""]
