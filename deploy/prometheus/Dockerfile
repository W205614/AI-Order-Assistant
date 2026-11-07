FROM golang:1.27.3-alpine@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOMAXPROCS=2
ADD --checksum=sha256:4636cc523bda30bdf92bf7b05b4491bb3d3038d89ebb3223c7b9a71f23e9e9c9 https://codeload.github.com/prometheus/prometheus/tar.gz/bae3eced0a5369e050e83ea73bc381d7c4b1a719 /tmp/source.tar.gz
ADD --checksum=sha256:adf30f35abf0d23ffd25616afe75d1966a7ceb18ddad4161fe8ad76e28f2b48f https://github.com/prometheus/prometheus/releases/download/v3.15.1/prometheus-web-ui-3.15.1.tar.gz /tmp/ui.tar.gz
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=ai-order-go-build,target=/root/.cache/go-build \
    apk add --no-cache bash gzip \
    && tar -xzf /tmp/source.tar.gz --strip-components=1 \
    && tar -xzf /tmp/ui.tar.gz -C web/ui \
    && bash scripts/compress_assets.sh \
    && go get golang.org/x/net@v0.62.0 \
    && go mod download all \
    && go mod verify \
    && go test -mod=readonly golang.org/x/net/html
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=ai-order-go-build,target=/root/.cache/go-build \
    go build -mod=readonly -p 2 -tags netgo,builtinassets -trimpath \
    -ldflags='-s -w -X github.com/prometheus/common/version.Version=3.15.1 -X github.com/prometheus/common/version.Revision=bae3eced0a5369e050e83ea73bc381d7c4b1a719 -X github.com/prometheus/common/version.Branch=security-xnet0.62.0' \
    -o /out/ ./cmd/prometheus ./cmd/promtool

# Retain the upstream user, storage layout, entrypoint and default configuration.
FROM prom/prometheus:v3.15.1@sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2
COPY --from=build /out/prometheus /bin/prometheus
COPY --from=build /out/promtool /bin/promtool
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF \
    com.ai-order.security.x-net="v0.62.0"
