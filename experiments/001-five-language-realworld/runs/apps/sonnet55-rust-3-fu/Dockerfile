FROM rust:1 AS build
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release

FROM debian:stable-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /data
COPY --from=build /app/target/release/realworld /usr/local/bin/realworld
ENV PORT=8000 DATABASE_URL=sqlite:///data/realworld.db
EXPOSE 8000
CMD ["realworld"]
