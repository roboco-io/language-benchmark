# syntax=docker/dockerfile:1

# ---- Build stage: static release binary (pure Go, no cgo) ----
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/realworld .

# ---- Runtime stage ----
FROM alpine:3.22
RUN adduser -D -u 10001 app && mkdir -p /data && chown app /data
COPY --from=build /out/realworld /usr/local/bin/realworld
USER app
ENV PORT=8080 \
    DATABASE_PATH=/data/realworld.db
VOLUME ["/data"]
EXPOSE 8080
# The schema is created automatically on startup when the database is empty.
ENTRYPOINT ["/usr/local/bin/realworld"]
