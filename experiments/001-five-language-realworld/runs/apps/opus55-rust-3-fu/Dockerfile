# --- Build stage: compile an optimized release binary -----------------------
FROM rust:1-slim-bookworm AS builder
WORKDIR /app

# Cache dependency compilation separately from application sources.
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs \
    && cargo build --release --locked \
    && rm -rf src

COPY src ./src
RUN touch src/main.rs && cargo build --release --locked

# --- Runtime stage ------------------------------------------------------------
FROM debian:bookworm-slim
RUN useradd --system --create-home --home-dir /app app \
    && mkdir -p /data && chown app /data
WORKDIR /app
COPY --from=builder /app/target/release/realworld /usr/local/bin/realworld

USER app
ENV PORT=8080 \
    DATABASE_URL=sqlite:/data/realworld.db \
    RUST_LOG=info
VOLUME ["/data"]
EXPOSE 8080

# The server creates the schema itself when the database is empty.
CMD ["realworld"]
