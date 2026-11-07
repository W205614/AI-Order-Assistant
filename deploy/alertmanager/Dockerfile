FROM golang:1.27.3-alpine@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOMAXPROCS=2
ADD --checksum=sha256:81e69f87954aedb557ed67d9ef0218a1c0c9b9e0c3184c9ddffe07bd247239fc https://codeload.github.com/prometheus/alertmanager/tar.gz/e8349048265ccc0871c3d0d668b05eacc68baf50 /tmp/source.tar.gz
ADD --checksum=sha256:3d6b13c5af094724e0bed8f757399e08af44d40d6c6cd87392a1f8ee6ed1e457 https://github.com/prometheus/alertmanager/releases/download/v0.34.2/alertmanager-web-ui-0.34.2.tar.gz /tmp/ui.tar.gz
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=ai-order-go-build,target=/root/.cache/go-build \
    tar -xzf /tmp/source.tar.gz --strip-components=1 \
    && tar -xzf /tmp/ui.tar.gz -C ui/app \
    && go get golang.org/x/net@v0.62.0 \
    && go mod download \
    && go mod verify \
    && go test -mod=readonly golang.org/x/net/html
RUN --mount=type=cache,id=ai-order-go-mod,target=/go/pkg/mod \
    --mount=type=cache,id=ai-order-go-build,target=/root/.cache/go-build \
    go build -mod=readonly -p 2 -tags netgo -trimpath \
    -ldflags='-s -w -X github.com/prometheus/common/version.Version=0.34.2 -X github.com/prometheus/common/version.Revision=e8349048265ccc0871c3d0d668b05eacc68baf50 -X github.com/prometheus/common/version.Branch=security-xnet0.62.0' \
    -o /out/ ./cmd/alertmanager ./cmd/amtool

# Retain the upstream user, storage layout, entrypoint and default configuration.
FROM prom/alertmanager:v0.34.2@sha256:39d8bb3966c694a124428a3a88fbcffd40bd069129ecd47c059f8a3f17c55f3b
COPY --from=build /out/alertmanager /bin/alertmanager
COPY --from=build /out/amtool /bin/amtool
ARG VCS_REF=development
LABEL org.opencontainers.image.revision=$VCS_REF \
    com.ai-order.security.x-net="v0.62.0"
