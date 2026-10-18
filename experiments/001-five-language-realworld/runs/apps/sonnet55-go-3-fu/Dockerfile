FROM golang:1.26 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/realworld .

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/realworld /realworld
ENV PORT=8000 DATABASE_PATH=/data/realworld.db
VOLUME /data
EXPOSE 8000
ENTRYPOINT ["/realworld"]
