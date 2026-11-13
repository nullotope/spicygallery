#!/usr/bin/env bash
# Build and run SpicyGallery dev-style: Postgres 17 in Docker (localhost-only),
# the fat jar on the host serving SPA + API on http://127.0.0.1:8080.
#
#   MEDIA_DIR=/path/to/library ./run.sh
#
# Build shortcuts (environment variables):
#   SKIP_TESTS=1      mvn -DskipTests
#   SKIP_FRONTEND=1   backend-only jar (skip the npm/SPA build)
set -euo pipefail
cd "$(dirname "$0")"

# Dev database: the compose `db` service, published on 127.0.0.1 only (the prod
# app talks to it over the compose network; the host jar via localhost).
# DB_HOST_PORT: override when 5432 is taken on the host.
DB_HOST_PORT="${DB_HOST_PORT:-5432}"
docker compose up -d db
for _ in $(seq 1 30); do
  if docker compose exec -T db pg_isready -U spicygallery -d spicygallery > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

if [[ -z "${SPRING_DATASOURCE_URL:-}" ]]; then
  export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${DB_HOST_PORT}/spicygallery"
fi

# Build: the full reactor — the webclient SPA is built and baked into the fat
# jar (frontend-maven-plugin + the server's copy-spa step).
MVN_ARGS=(install)
if [[ -n "${SKIP_TESTS:-}" ]]; then
  MVN_ARGS+=(-DskipTests)
fi
if [[ -n "${SKIP_FRONTEND:-}" ]]; then
  MVN_ARGS+=(-DskipFrontendBuild)
fi
mvn -q "${MVN_ARGS[@]}"

JAR=(spicygallery-server/target/spicygallery-server-*.jar)
if [[ ! -e "${JAR[0]}" ]]; then
  echo "run.sh: no jar at ${JAR[0]} — did the build fail?" >&2
  exit 1
fi

export SPICYGALLERY_LIBRARIES_0_NAME=library
export SPICYGALLERY_LIBRARIES_0_ROOT="${MEDIA_DIR:-$(pwd)/media}"
# Boot relaxed binding: env name for `spicygallery.app-data` (dashes removed).
export SPICYGALLERY_APPDATA="${APP_DATA:-$(pwd)/data}"

echo "SpicyGallery: http://127.0.0.1:8080  (library: $SPICYGALLERY_LIBRARIES_0_ROOT)"
exec java -jar "${JAR[0]}" "$@"
