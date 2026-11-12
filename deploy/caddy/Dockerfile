FROM golang:1.27.4-alpine@sha256:0a5f7fe796599ff487605987432e7619ae45beeb2fba4c2ce0b907879932aebf AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOMAXPROCS=2
COPY go.mod go.sum ./
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    go mod download
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=ai-order-go-build,target=/root/.cache/go-build \
    go mod verify \
    && go test -mod=readonly github.com/go-jose/go-jose/v4/... \
        github.com/caddyserver/caddy/v2/modules/caddypki/... \
        github.com/go-chi/chi/v5/middleware go.step.sm/crypto/jose/... golang.org/x/net/html
COPY main.go ./
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=ai-order-go-build,target=/root/.cache/go-build \
    go mod verify \
    && go build -mod=readonly -trimpath -ldflags='-s -w' -o /out/caddy .

# Preserve the upstream image's default command, directories and environment.
FROM caddy:2.11.7-alpine@sha256:e70b164da81ef698b3ad19af799ea6086ce6d9e48ed4079eb9fe26f6fbf14ee5
RUN apk upgrade --no-cache
COPY --from=build /out/caddy /usr/bin/caddy
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF \
    org.opencontainers.image.version="2.11.9" \
    com.ai-order.security.caddy="v2.11.9" \
    com.ai-order.security.go-jose="v4.1.9" \
    com.ai-order.security.certificates="v0.30.3-0.20261105204752-cddde30af36e" \
    com.ai-order.security.x-crypto="v0.58.1-0.20261009181718-2f1f834330b2" \
    com.ai-order.security.x-net="v0.62.0"
