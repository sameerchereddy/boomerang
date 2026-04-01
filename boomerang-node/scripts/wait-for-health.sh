#!/usr/bin/env bash
set -euo pipefail

BOOMERANG_URL="${BOOMERANG_URL:-http://localhost:8080}"
MAX_ATTEMPTS=30
SLEEP_SECONDS=2

echo "Waiting for Boomerang at $BOOMERANG_URL..."

for i in $(seq 1 $MAX_ATTEMPTS); do
  if curl -sf "$BOOMERANG_URL/actuator/health" > /dev/null 2>&1; then
    echo "Boomerang is healthy."
    exit 0
  fi
  echo "  Attempt $i/$MAX_ATTEMPTS — not ready yet, waiting ${SLEEP_SECONDS}s..."
  sleep $SLEEP_SECONDS
done

echo "Boomerang did not become healthy in time." >&2
exit 1
