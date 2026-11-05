FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=3 -Daether.connector.http.retryHandler.count=3 -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037 AS runtime
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
