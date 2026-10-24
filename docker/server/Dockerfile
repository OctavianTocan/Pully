# The Pully server as a Cloudflare Container (packages/worker): the Rust server in --server mode,
# with no checkout, Docker or Node. `pully-container` restores /var/lib/pully from R2 through the
# Worker before the server starts and saves it back as it changes (backend/src/data_mirror.rs).
# Built from the repository root; Dockerfile.dockerignore lets in only the backend and the files
# it embeds.
FROM rust:1.97-bookworm AS build
WORKDIR /src
COPY docker/agents/skills docker/agents/skills
COPY packages/core/src/types/merge-risk-rubric.json packages/core/src/types/merge-risk-rubric.json
COPY backend backend
# A build behind a TLS-inspecting proxy passes its CA as `--secret id=build-ca,src=<file>`;
# without it the build trusts the image's own certificates.
RUN --mount=type=secret,id=build-ca \
  if [ -s /run/secrets/build-ca ]; then export CARGO_HTTP_CAINFO=/run/secrets/build-ca; fi \
  && cargo build --release --locked --manifest-path backend/Cargo.toml --bins

FROM debian:bookworm-slim
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates \
  && rm -rf /var/lib/apt/lists/* \
  && useradd --create-home pully \
  && install -d -o pully -g pully /var/lib/pully
COPY --from=build /src/backend/target/release/pulls-review-server /src/backend/target/release/pully-container /usr/local/bin/
COPY docker/agents/skills /opt/pulls-review/skills
ENV PULLS_REVIEW_SKILLS_DIR=/opt/pulls-review/skills \
  PULLS_REVIEW_ANALYTICS_DIR=/var/lib/pully/analytics \
  PULLS_REVIEW_ANDROID_DIR=/var/lib/pully/android \
  PULLY_DATA_ROOT=/var/lib/pully
USER pully
WORKDIR /home/pully
EXPOSE 8080
ENTRYPOINT ["pully-container"]
CMD ["--server", "--data-dir", "/var/lib/pully/store", "--host", "0.0.0.0", "--port", "8080", "--no-open"]
