FROM node:21-alpine as frontendBuilder
COPY observecInterface /observec/observecInterface
WORKDIR /observec/observecInterface
# RUN npm install
# RUN npm run build

FROM maven:3.9.6-eclipse-temurin-17 as engineBuilder
COPY observecEngine /observec/observecEngine
WORKDIR /observec/observecEngine
COPY --from=frontendBuilder /observec/observecInterface/dist /observec/observecEngine/src/main/resources/static
RUN mvn clean package -DskipTests

FROM prom/prometheus:v2.55.1 AS prometheus
FROM quay.io/prometheus/blackbox-exporter:v0.25.0 AS blackbox
FROM prom/alertmanager:v0.27.0 AS alertmanager

FROM eclipse-temurin:17-jdk-jammy AS observec

RUN apt-get update \
    && apt-get install -y --no-install-recommends bash git \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /observec/bin \
    && git config --system --add safe.directory /observec/observecData

COPY --from=prometheus /bin/prometheus /observec/bin/prometheus
COPY --from=prometheus /bin/promtool /observec/bin/promtool
COPY --from=blackbox /bin/blackbox_exporter /observec/bin/blackbox_exporter
COPY --from=alertmanager /bin/alertmanager /observec/bin/alertmanager
COPY --from=alertmanager /bin/amtool /observec/bin/amtool

ENV PATH="/observec/bin:${PATH}"

COPY --from=engineBuilder /observec/observecEngine/target/observec.jar /observec/observec.jar

EXPOSE 8080 8081 9090 9115 9093

ENTRYPOINT ["java", "-jar", "/observec/observec.jar"]


