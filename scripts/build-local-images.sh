#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> Building bootJars..."
./gradlew :services:api-core:bootJar \
          :services:api-public-read:bootJar \
          :services:external-ingestion:bootJar

echo "==> Building Docker images..."
docker build -t safespot/api-core:local services/api-core
docker build -t safespot/api-public-read:local services/api-public-read
docker build -t safespot/external-ingestion:local services/external-ingestion

echo "==> Done."
echo "  safespot/api-core:local"
echo "  safespot/api-public-read:local"
echo "  safespot/external-ingestion:local"
