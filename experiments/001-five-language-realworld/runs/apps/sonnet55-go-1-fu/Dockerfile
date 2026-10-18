# syntax=docker/dockerfile:1
FROM golang:1.26 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/realworld . && mkdir -p /out/data

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/realworld /realworld
COPY --from=build /out/data /data
ENV PORT=8000 DB_PATH=/data/realworld.db
EXPOSE 8000
ENTRYPOINT ["/realworld"]
