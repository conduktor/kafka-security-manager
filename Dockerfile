FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
# Refresh security fixes that landed after the pinned JRE image was built.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libfreetype6 libpng16-16t64 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/ksm
ARG JAR=target/kafka-security-manager-2.0.0-SNAPSHOT.jar
COPY --chown=10001:10001 ${JAR} lib/kafka-security-manager.jar
COPY --chown=10001:10001 bin/ bin/
COPY --chown=10001:10001 example/ example/
USER 10001:10001
ENTRYPOINT ["/opt/ksm/bin/kafka-security-manager"]
