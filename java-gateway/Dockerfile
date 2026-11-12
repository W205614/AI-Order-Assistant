FROM maven:3.9-eclipse-temurin-21@sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=3 -Daether.connector.http.retryHandler.count=3 -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-alpine@sha256:daf693993291e9efe90b8b4c274b8b5a64ef2968759584d5f3a4cea104a9d93c AS runtime
WORKDIR /app
# The application starts Java directly and uses its default or explicit truststore.
# The unused upstream dynamic-CA entrypoint is the only consumer of this P11 group.
RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && apk del p11-kit-trust p11-kit \
    && test ! -e /usr/lib/libp11-kit.so.0 \
    && rm /__cacert_entrypoint.sh
COPY --from=build /app/target/*.jar app.jar
COPY --from=build /app/target/classes/META-INF/sbom/application.cdx.json runtime-bom.json
RUN addgroup -S -g 10001 app && adduser -D -H -u 10001 -G app app \
    && mkdir -p /app/media && chown app:app /app /app/media
USER 10001:10001
EXPOSE 9090
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
