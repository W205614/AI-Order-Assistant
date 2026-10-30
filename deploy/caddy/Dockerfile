FROM golang:1.27.3-alpine@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
COPY go.mod go.sum ./
RUN apk add --no-cache patch && go mod download
COPY patches ./patches
RUN go mod verify \
    && cp -a /go/pkg/mod/github.com/go-jose/go-jose/v3@v3.0.5 /patched-jose \
    && chmod -R u+w /patched-jose \
    && cd /patched-jose \
    && patch -p1 < /src/patches/go-jose-v3-CVE-2026-116587.patch \
    && cp /src/patches/security_backport_test.go . \
    && cd /src \
    && go mod edit -replace=github.com/go-jose/go-jose/v3=/patched-jose \
    && go test -mod=readonly github.com/go-jose/go-jose/v3/...
COPY main.go ./
RUN go mod verify \
    && go build -mod=readonly -trimpath -ldflags='-s -w' -o /out/caddy .

# Preserve the upstream image's default command, directories and environment.
FROM caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f
RUN apk upgrade --no-cache
COPY --from=build /out/caddy /usr/bin/caddy
LABEL com.ai-order.security.go-jose-backport="a0111f82742fa92bceec9ede40845e73319e6230"
