FROM rust:1-slim AS build
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --release

FROM debian:stable-slim
WORKDIR /app
COPY --from=build /app/target/release/realworld /usr/local/bin/realworld
ENV PORT=8000 DATABASE_PATH=/data/realworld.db
RUN mkdir -p /data
EXPOSE 8000
CMD ["realworld"]
