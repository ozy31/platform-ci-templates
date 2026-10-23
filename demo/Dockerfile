# syntax=docker/dockerfile:1

# ---- build stage: cross-compile on the native builder platform ----
FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS build
WORKDIR /src

COPY go.mod ./
RUN go mod download

COPY . .

ARG TARGETOS
ARG TARGETARCH
ARG VERSION=dev
RUN --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/demo .

# ---- runtime stage: no shell, no package manager, non-root ----
FROM gcr.io/distroless/static-debian13:nonroot
COPY --from=build /out/demo /demo
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/demo"]
