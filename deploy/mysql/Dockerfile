# syntax=docker/dockerfile:1.7
# Rebuild the unchanged gosu 1.20 source with the patched Go standard library.
FROM golang:1.27.3-alpine@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS gosu-build
WORKDIR /src/gosu
ADD --checksum=sha256:2b70da8a5480fafedc16bb1fc91b572f064d03df736cbd91a616af2f1b66ab51 https://codeload.github.com/tianon/gosu/tar.gz/29b939aa5bd4dc588af765b8248f6e39150d2565 /tmp/gosu.tar.gz
RUN tar -xzf /tmp/gosu.tar.gz --strip-components=1 -C /src/gosu
COPY go.mod go.sum ./
RUN go mod download \
    && CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false -ldflags='-s -w' -o /out/gosu .

FROM mysql:8.4.13@sha256:a0225d1ea6631c8bc2385dc4c2faba17bbf1834ecf369e1659a9a382bd8d5dd7
LABEL org.opencontainers.image.base.name="mysql:8.4.13@sha256:a0225d1ea6631c8bc2385dc4c2faba17bbf1834ecf369e1659a9a382bd8d5dd7" \
      io.ai-order.gosu.source="https://github.com/tianon/gosu/tree/29b939aa5bd4dc588af765b8248f6e39150d2565" \
      io.ai-order.gosu.go-version="1.27.3"
# mysql-shell is unused by the official entrypoint, MySQL server and backup client.
# Uninstall through RPM tooling; keep the package database available to vulnerability scanners.
RUN microdnf --disablerepo='*' remove -y mysql-shell \
    && microdnf clean all \
    && ! rpm -q mysql-shell \
    && command -v mysqld \
    && command -v mysql \
    && command -v mysqldump \
    && command -v mysqladmin
COPY --from=gosu-build --chmod=0755 /out/gosu /usr/local/bin/gosu
RUN gosu --version \
    && test "$(gosu mysql id -u)" = "$(id -u mysql)" \
    && mysqld --version \
    && mysql --version \
    && mysqldump --version \
    && mysqladmin --version
