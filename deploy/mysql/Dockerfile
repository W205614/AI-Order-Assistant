# syntax=docker/dockerfile:1.7
# Rebuild gosu 1.20 with the patched Go standard library and x/sys dependency.
FROM golang:1.27.3-alpine@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS gosu-build
WORKDIR /src/gosu
ADD --checksum=sha256:2b70da8a5480fafedc16bb1fc91b572f064d03df736cbd91a616af2f1b66ab51 https://codeload.github.com/tianon/gosu/tar.gz/29b939aa5bd4dc588af765b8248f6e39150d2565 /tmp/gosu.tar.gz
RUN tar -xzf /tmp/gosu.tar.gz --strip-components=1 -C /src/gosu
COPY go.mod go.sum ./
RUN go mod download \
    && CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false -ldflags='-s -w' -o /out/gosu .

FROM scratch AS runtime-rpms
ADD --checksum=sha256:b2b6b4e6c92fc6d15ff2bf28d1142fa54cf5dd43c69e2d2f2513c5b301061904 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/glibc-2.34-284.0.1.el9_8.4.x86_64.rpm /runtime-rpms/glibc-2.34-284.0.1.el9_8.4.x86_64.rpm
ADD --checksum=sha256:b98ad2cda8c4e6d84d0f033372fe3a4010662173a65b923ecd6f5f011b723e12 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/glibc-common-2.34-284.0.1.el9_8.4.x86_64.rpm /runtime-rpms/glibc-common-2.34-284.0.1.el9_8.4.x86_64.rpm
ADD --checksum=sha256:9febe6bc5f4ec4fc5bd42853c9ab2c97bf0e954e85eba75de15c4621f97c56e9 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/glibc-minimal-langpack-2.34-284.0.1.el9_8.4.x86_64.rpm /runtime-rpms/glibc-minimal-langpack-2.34-284.0.1.el9_8.4.x86_64.rpm
ADD --checksum=sha256:7300400c68f72c78650a0d76cf233deb8860a32771c5b0bb8bc4ee509ac6bb22 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/curl-7.76.1-40.el9_8.9.x86_64.rpm /runtime-rpms/curl-7.76.1-40.el9_8.9.x86_64.rpm
ADD --checksum=sha256:a522b6ab35fe1118a580537a375ab4f9d41f94b00411497f1d6cdb5e35353dfd https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/libcurl-7.76.1-40.el9_8.9.x86_64.rpm /runtime-rpms/libcurl-7.76.1-40.el9_8.9.x86_64.rpm

FROM mysql:26.10.0@sha256:3819b2b23835c4a73c8b022fafc33c8095c0f81c929a8b7a84613d2fba6c52f5
LABEL org.opencontainers.image.base.name="mysql:8.4.13@sha256:a0225d1ea6631c8bc2385dc4c2faba17bbf1834ecf369e1659a9a382bd8d5dd7" \
      io.ai-order.gosu.source="https://github.com/tianon/gosu/tree/29b939aa5bd4dc588af765b8248f6e39150d2565" \
      io.ai-order.gosu.go-version="1.27.3"
# mysql-shell is unused by the official entrypoint, MySQL server and backup client.
# Uninstall through RPM tooling; keep the package database available to vulnerability scanners.
# Apply the signed, pinned BaseOS fixes offline to preserve MySQL 8.4.13.
RUN --mount=from=runtime-rpms,source=/runtime-rpms,target=/runtime-rpms,ro \
    microdnf --disablerepo='*' remove -y mysql-shell \
    && rpm --checksig --verbose /runtime-rpms/*.rpm \
    && rpm -Uvh --test /runtime-rpms/*.rpm \
    && rpm -Uvh /runtime-rpms/*.rpm \
    && microdnf clean all \
    && ! rpm -q mysql-shell \
    && command -v mysqld \
    && command -v mysql \
    && command -v mysqldump \
    && command -v mysqladmin
COPY --from=gosu-build --chmod=0755 /out/gosu /usr/local/bin/gosu
COPY --chmod=0644 provision-users.sql /usr/local/share/ai-order/provision-users.sql
COPY --chmod=0644 init-users.sh /docker-entrypoint-initdb.d/01-ai-order-users.sh
RUN chmod 755 /usr/local/share/ai-order
RUN gosu --version \
    && test "$(gosu mysql id -u)" = "$(id -u mysql)" \
    && mysqld --version \
    && mysql --version \
    && mysqldump --version \
    && mysqladmin --version
