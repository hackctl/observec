FROM node:21-alpine AS frontendbuilder
COPY observecInterface /observec/observecInterface
WORKDIR /observec/observecInterface
# RUN npm install
# RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17-noble AS enginebuilder
COPY observecEngine /observec/observecEngine
WORKDIR /observec/observecEngine
COPY --from=frontendbuilder /observec/observecInterface/dist /observec/observecEngine/src/main/resources/static
RUN mvn clean package -DskipTests

FROM prom/prometheus:v2.55.1 AS prometheus
FROM quay.io/prometheus/blackbox-exporter:v0.25.0 AS blackbox
FROM prom/alertmanager:v0.27.0 AS alertmanager

FROM maven:3.9.9-eclipse-temurin-17-noble AS observec

RUN mkdir -p /observec/bin \
    && git config --system --add safe.directory /observec/observecData

COPY --from=prometheus /bin/prometheus /observec/bin/prometheus
COPY --from=prometheus /bin/promtool /observec/bin/promtool
COPY --from=blackbox /bin/blackbox_exporter /observec/bin/blackbox_exporter
COPY --from=alertmanager /bin/alertmanager /observec/bin/alertmanager
COPY --from=alertmanager /bin/amtool /observec/bin/amtool

ENV PATH="/observec/bin:${PATH}"

COPY --from=enginebuilder /observec/observecEngine/target/observec.jar /observec/observec.jar

EXPOSE 8080 8081 9090 9115 9093

ENTRYPOINT ["java", "-jar", "/observec/observec.jar"]


