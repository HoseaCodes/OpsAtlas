# The observer, as a container.
#
# Phase 10 packaged the control plane and the console and stopped there, which
# was fine while the observer only ever ran from `make dev-observer` on a
# developer's machine. A deployment without it is a catalog rather than a
# monitoring platform: nothing probes, so every environment stays at "never
# probed" forever.

# ---- build -----------------------------------------------------------------
FROM golang:1.27-alpine AS build

WORKDIR /src

# Module files first, so editing a prober does not re-download the module cache.
COPY apps/observer/go.mod apps/observer/go.sum ./
RUN go mod download

COPY apps/observer/ ./

# CGO off, so the binary carries no dynamic linkage and the runtime stage needs
# no toolchain to have produced it. Symbols and DWARF stripped: this binary is
# read from a log line and a metric, never from a core dump on the box.
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/observer ./cmd/observer

# ---- runtime ---------------------------------------------------------------
# Alpine rather than scratch, for two specific reasons rather than habit: the
# probe targets are HTTPS, which needs a CA bundle, and the healthcheck below
# needs something that can make a request. A scratch image would be smaller and
# could not check whether it was alive.
FROM alpine:3.21 AS runtime

RUN apk add --no-cache ca-certificates wget

RUN addgroup -g 1001 -S opsatlas && adduser -S opsatlas -u 1001 -G opsatlas

COPY --from=build /out/observer /usr/local/bin/observer

USER opsatlas

# Its own metrics, not the control plane's. 9090 here and 9091 for Prometheus on
# the host, which is the collision deploy/compose/docker-compose.yml already
# works around.
EXPOSE 9090

# The metrics endpoint is the only thing this process serves, so it is also the
# only honest liveness signal: if it answers, the scheduler is running.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:9090/metrics > /dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/observer"]
