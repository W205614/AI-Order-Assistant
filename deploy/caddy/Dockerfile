FROM golang:1.27.3-alpine@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOMAXPROCS=2
COPY go.mod go.sum ./
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    apk add --no-cache patch && go mod download
COPY patches ./patches
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=ai-order-go-build,target=/root/.cache/go-build \
    go mod verify \
    && cp -a /go/pkg/mod/github.com/go-jose/go-jose/v3@v3.0.5 /patched-jose \
    && chmod -R u+w /patched-jose \
    && cd /patched-jose \
    && patch -p1 < /src/patches/go-jose-v3-CVE-2026-116587.patch \
    && cp /src/patches/security_backport_test.go . \
    && cd /src \
    && go mod edit -replace=github.com/go-jose/go-jose/v3=/patched-jose \
    && go test -mod=readonly github.com/go-jose/go-jose/v3/... golang.org/x/net/html
COPY main.go ./
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=ai-order-go-build,target=/root/.cache/go-build \
    go mod verify \
    && go build -mod=readonly -trimpath -ldflags='-s -w' -o /out/caddy .

# Preserve the upstream image's default command, directories and environment.
FROM caddy:2.11.7-alpine@sha256:e70b164da81ef698b3ad19af799ea6086ce6d9e48ed4079eb9fe26f6fbf14ee5
RUN apk upgrade --no-cache
COPY --from=build /out/caddy /usr/bin/caddy
LABEL com.ai-order.security.go-jose-backport="a0111f82742fa92bceec9ede40845e73319e6230"
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF \
    com.ai-order.security.x-net="v0.62.0"
