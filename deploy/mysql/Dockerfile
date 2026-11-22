# syntax=docker/dockerfile:1.7
# Rebuild gosu 1.20 with the patched Go standard library and x/sys dependency.
FROM golang:1.27.4-alpine@sha256:0a5f7fe796599ff487605987432e7619ae45beeb2fba4c2ce0b907879932aebf AS gosu-build
WORKDIR /src/gosu
ADD --checksum=sha256:2b70da8a5480fafedc16bb1fc91b572f064d03df736cbd91a616af2f1b66ab51 https://codeload.github.com/tianon/gosu/tar.gz/29b939aa5bd4dc588af765b8248f6e39150d2565 /tmp/gosu.tar.gz
RUN tar -xzf /tmp/gosu.tar.gz --strip-components=1 -C /src/gosu
COPY go.mod go.sum ./
RUN go mod download \
    && CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false -ldflags='-s -w' -o /out/gosu .

FROM scratch AS runtime-rpms
ADD --checksum=sha256:23638b167b22d02f086de25c38a2d6d9ec4f0c436ff0e84976d115262c5f2e36 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/curl-7.76.1-44.el9_9.1.x86_64.rpm /runtime-rpms/curl-7.76.1-44.el9_9.1.x86_64.rpm
ADD --checksum=sha256:055159674ff959959d02f694406faccbf23c1be08786d7ce72111f42de5f7618 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/glib2-2.68.4-29.el9_9.1.x86_64.rpm /runtime-rpms/glib2-2.68.4-29.el9_9.1.x86_64.rpm
ADD --checksum=sha256:18404803e37c8f17b0f32ffac1a248e60879163017fd1960c0ead1a6f10704c8 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/glibc-2.34-290.0.1.el9_9.x86_64.rpm /runtime-rpms/glibc-2.34-290.0.1.el9_9.x86_64.rpm
ADD --checksum=sha256:c94e248af31314d2ed41596220c45d038f48d88103cd141c278109667d2e58ce https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/glibc-common-2.34-290.0.1.el9_9.x86_64.rpm /runtime-rpms/glibc-common-2.34-290.0.1.el9_9.x86_64.rpm
ADD --checksum=sha256:9a31fdadfdf9b1c6151b135512d3818ce987546ee950d225407b360be72d5ea7 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/glibc-minimal-langpack-2.34-290.0.1.el9_9.x86_64.rpm /runtime-rpms/glibc-minimal-langpack-2.34-290.0.1.el9_9.x86_64.rpm
ADD --checksum=sha256:683ac9421e2990abd3acfeb9178bed78864db9d32baed0000d0f088ff1db5501 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/krb5-libs-1.21.1-10.0.1.el9_9.2.x86_64.rpm /runtime-rpms/krb5-libs-1.21.1-10.0.1.el9_9.2.x86_64.rpm
ADD --checksum=sha256:a813cd0981f32be4fc91ee23093d23467dc011cc7d9dcf4166325580e6bc862b https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/libblkid-2.37.4-28.0.1.el9_9.1.x86_64.rpm /runtime-rpms/libblkid-2.37.4-28.0.1.el9_9.1.x86_64.rpm
ADD --checksum=sha256:037397cf7078fc04a24b3a5a5bbda620d82adaea68e8df203e6731fc44b40ba8 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/libcurl-7.76.1-44.el9_9.1.x86_64.rpm /runtime-rpms/libcurl-7.76.1-44.el9_9.1.x86_64.rpm
ADD --checksum=sha256:de6281b1c12cf0133d946455fd417bc7ea1610db6ff1eea3c20640d92c8ba010 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/libmount-2.37.4-28.0.1.el9_9.1.x86_64.rpm /runtime-rpms/libmount-2.37.4-28.0.1.el9_9.1.x86_64.rpm
ADD --checksum=sha256:c5b2f13377c8d2be5d2eb7408fee5b57e99bc34d185d021526dae1a585eba041 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/libsmartcols-2.37.4-28.0.1.el9_9.1.x86_64.rpm /runtime-rpms/libsmartcols-2.37.4-28.0.1.el9_9.1.x86_64.rpm
ADD --checksum=sha256:8db6da6e9f7613dedf06a2becf5202c3e5ab02fdb0380366ea514cf7a68d40ce https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/libuuid-2.37.4-28.0.1.el9_9.1.x86_64.rpm /runtime-rpms/libuuid-2.37.4-28.0.1.el9_9.1.x86_64.rpm
ADD --checksum=sha256:865577de2da2d184bab0513f90f299e90290870ceaa5eabca274c1c074267248 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/libxml2-2.9.13-18.el9_9.1.x86_64.rpm /runtime-rpms/libxml2-2.9.13-18.el9_9.1.x86_64.rpm
ADD --checksum=sha256:04ad86cd91eba58771ac488bb609a72ebb7888b512deedb60c12865fa42730e2 https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/openssl-3.5.9-1.0.1.el9_9.x86_64.rpm /runtime-rpms/openssl-3.5.9-1.0.1.el9_9.x86_64.rpm
ADD --checksum=sha256:f659af5819a46f3fe6ab23ed7b7c0a3429bd8727eb5847d939cc92753208f1cc https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/openssl-libs-3.5.9-1.0.1.el9_9.x86_64.rpm /runtime-rpms/openssl-libs-3.5.9-1.0.1.el9_9.x86_64.rpm
ADD --checksum=sha256:fb8ea9e1c937ef1e12e3b4553922db97c0ade4c3126badee2311e8b2b2e3106b https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/rpm-4.16.1.3-43.el9_9.x86_64.rpm /runtime-rpms/rpm-4.16.1.3-43.el9_9.x86_64.rpm
ADD --checksum=sha256:32d348be4e546b191409f4374ef5c9cacf874c185136956b93e86977cf0ec9ad https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/rpm-libs-4.16.1.3-43.el9_9.x86_64.rpm /runtime-rpms/rpm-libs-4.16.1.3-43.el9_9.x86_64.rpm
ADD --checksum=sha256:9661b1dfe6139d1b130e9762f2194630fae561225646f0fbe0a78bb24f908bff https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/xz-5.2.5-8.el9_9.1.x86_64.rpm /runtime-rpms/xz-5.2.5-8.el9_9.1.x86_64.rpm
ADD --checksum=sha256:4b6bb6cf0294fa041116672550e986f5f8ea572a1ec59de5f49f3ffc85afc56f https://yum.oracle.com/repo/OracleLinux/OL9/baseos/latest/x86_64/getPackage/xz-libs-5.2.5-8.el9_9.1.x86_64.rpm /runtime-rpms/xz-libs-5.2.5-8.el9_9.1.x86_64.rpm

FROM mysql:8.4.13@sha256:a0225d1ea6631c8bc2385dc4c2faba17bbf1834ecf369e1659a9a382bd8d5dd7
LABEL org.opencontainers.image.base.name="mysql:8.4.13@sha256:a0225d1ea6631c8bc2385dc4c2faba17bbf1834ecf369e1659a9a382bd8d5dd7" \
      io.ai-order.gosu.source="https://github.com/tianon/gosu/tree/29b939aa5bd4dc588af765b8248f6e39150d2565" \
      io.ai-order.gosu.go-version="1.27.4"
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
