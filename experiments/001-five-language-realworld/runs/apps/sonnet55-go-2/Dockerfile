# syntax=docker/dockerfile:1
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/realworld .

FROM alpine:3.22
RUN adduser -D -h /data app && mkdir -p /data && chown app /data
USER app
WORKDIR /data
COPY --from=build /out/realworld /usr/local/bin/realworld
ENV PORT=8000 DB_PATH=/data/realworld.db
EXPOSE 8000
CMD ["realworld"]
