FROM eclipse-temurin:25-jre-noble@sha256:49fa0d811061f2b684eb8d32048c6697890e2b169e2cd6bd329d01f03848a606
# Refresh security fixes that landed after the pinned JRE image was built.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade ca-certificates libfreetype6 libpng16-16t64 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/ksm
ARG JAR=target/kafka-security-manager-2.0.0.jar
COPY --chown=10001:10001 ${JAR} lib/kafka-security-manager.jar
COPY --chown=10001:10001 bin/ bin/
COPY --chown=10001:10001 example/ example/
USER 10001:10001
ENTRYPOINT ["/opt/ksm/bin/kafka-security-manager"]
