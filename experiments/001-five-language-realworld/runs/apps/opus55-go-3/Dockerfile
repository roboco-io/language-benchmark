# Build stage: compile a static, stripped release binary.
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/server .

# Runtime stage: minimal image with a writable data directory.
FROM alpine:3
RUN addgroup -S app && adduser -S -G app app && mkdir -p /data && chown app:app /data
COPY --from=build /out/server /usr/local/bin/server
USER app
ENV PORT=8000 \
    DATABASE_PATH=/data/realworld.db \
    GIN_MODE=release
VOLUME /data
EXPOSE 8000
CMD ["/usr/local/bin/server"]
