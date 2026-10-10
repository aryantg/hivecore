# relayd: the HiveCore relay (spec 5.3, 20.1). Multi-stage: build with the Go toolchain, run on distroless.
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/relayd ./cmd/relayd

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/relayd /relayd
# Plain HTTP/WebSocket on 8080; Caddy (see DEPLOY.md) terminates TLS in front of it.
EXPOSE 8080
ENV RELAY_LISTEN=:8080
USER nonroot:nonroot
ENTRYPOINT ["/relayd"]
