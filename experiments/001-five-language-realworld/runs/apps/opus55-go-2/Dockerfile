# Build stage: compile a static, optimized release binary.
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/realworld .

# Runtime stage: minimal image; the schema is created on startup.
FROM alpine:3.20
RUN adduser -D -u 10001 app && mkdir /data && chown app /data
COPY --from=build /out/realworld /usr/local/bin/realworld
USER app
ENV PORT=8000 \
    DATABASE_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8000
CMD ["realworld"]
