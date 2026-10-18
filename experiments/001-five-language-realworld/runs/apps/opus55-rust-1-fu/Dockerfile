# Build stage: compile an optimized release binary.
FROM rust:1-slim-bookworm AS build
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
# Cache dependency compilation in its own layer.
RUN mkdir src && echo "fn main() {}" > src/main.rs \
    && cargo build --release --locked \
    && rm -rf src
COPY src ./src
RUN touch src/main.rs && cargo build --release --locked

# Runtime stage: a slim image with only the binary.
FROM debian:bookworm-slim
RUN useradd --system --create-home app && mkdir -p /data && chown app /data
COPY --from=build /app/target/release/realworld-axum /usr/local/bin/realworld-axum
USER app
ENV PORT=8000 \
    DATABASE_URL=sqlite:///data/realworld.db
VOLUME /data
EXPOSE 8000
CMD ["realworld-axum"]
