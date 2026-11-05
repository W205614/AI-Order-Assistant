FROM maven:3.9-eclipse-temurin-21@sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=3 -Daether.connector.http.retryHandler.count=3 -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0 AS runtime
WORKDIR /app
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/* \
    && apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 upgrade -y \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
COPY --from=build /app/target/classes/META-INF/sbom/application.cdx.json runtime-bom.json
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app && chown app:app /app
USER 10001:10001
EXPOSE 9090
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
