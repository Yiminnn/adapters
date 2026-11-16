# Same base and toolchain as the upstream c-verifier (docker/model/cpp/Dockerfile_bench).
FROM python:3.11-slim-bookworm

RUN apt-get update \
 && apt-get install -y --no-install-recommends cmake gcc make gcc-multilib \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
