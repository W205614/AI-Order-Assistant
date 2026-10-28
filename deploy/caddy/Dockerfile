FROM golang:1.27.3-alpine@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
RUN go mod verify \
    && go build -mod=readonly -trimpath -ldflags='-s -w' -o /out/caddy .

# Preserve the upstream image's default command, directories and environment.
FROM caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f
RUN apk upgrade --no-cache
COPY --from=build /out/caddy /usr/bin/caddy
