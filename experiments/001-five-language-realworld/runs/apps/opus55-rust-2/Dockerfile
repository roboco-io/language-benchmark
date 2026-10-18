FROM rust:1-slim-bookworm AS build
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
# Build dependencies first so they are cached between source changes.
RUN mkdir src && echo 'fn main() {}' > src/main.rs \
    && cargo build --release --locked \
    && rm -rf src target/release/deps/realworld* target/release/realworld*
COPY src ./src
RUN cargo build --release --locked

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 app && mkdir /data && chown app /data
COPY --from=build /app/target/release/realworld /usr/local/bin/realworld
USER app
ENV PORT=8000 \
    DATABASE_URL=sqlite:///data/realworld.db
VOLUME /data
EXPOSE 8000
CMD ["realworld"]
