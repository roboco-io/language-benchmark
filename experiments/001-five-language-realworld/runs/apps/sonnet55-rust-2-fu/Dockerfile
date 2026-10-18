FROM rust:1-slim AS build
WORKDIR /app
COPY Cargo.toml Cargo.lock* ./
COPY src ./src
COPY migrations ./migrations
RUN cargo build --release

FROM debian:stable-slim
RUN useradd -r -m app && mkdir /data && chown app /data
COPY --from=build /app/target/release/realworld /usr/local/bin/realworld
USER app
ENV PORT=8000 DATABASE_URL=sqlite:///data/realworld.db
EXPOSE 8000
CMD ["realworld"]
