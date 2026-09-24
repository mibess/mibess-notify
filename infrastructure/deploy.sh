#!/usr/bin/env bash
set -Eeuo pipefail
environment=${1:?hml or prd}; revision=${2:?commit SHA required}
[[ "$environment" =~ ^(hml|prd)$ ]] || exit 2
[[ "$revision" =~ ^[a-f0-9]{40}$ ]] || exit 2
base=/opt/mibess-notify
exec 9>"$base/deploy-$environment.lock"
flock -n 9 || { echo 'Another deployment is running'; exit 1; }
export REGISTRY=497176797028.dkr.ecr.us-east-1.amazonaws.com
compose="$base/bin/docker-compose"
release="$base/releases/$revision"
mkdir -p "$release" "$base/state" "$base/backups/$environment"
if [[ ! -f "$release/docker-compose.$environment.yml" ]]; then
  curl --fail --silent --show-error --location "https://github.com/mibess/mibess-notify/archive/$revision.tar.gz" | tar -xz --strip-components=1 -C "$release"
fi
previous=$(cat "$base/state/$environment.current" 2>/dev/null || true)
port=8391; database=mibess_notify_hml; dbuser=mibess_notify_hml
if [[ "$environment" == prd ]]; then port=8390; database=mibess_notify; dbuser=mibess_notify; fi
export IMAGE_TAG=$revision
file="$release/docker-compose.$environment.yml"
"$compose" -f "$file" config --quiet
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
"$compose" -f "$file" pull
# pg_dump targets only the Notify database, never other shared databases.
if docker exec "postgres-$environment" psql -U "user-$environment" -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname='$database'" | grep -qx 1; then
  umask 077
  docker exec "postgres-$environment" pg_dump -U "$dbuser" -d "$database" -Fc > "$base/backups/$environment/$(date -u +%Y%m%dT%H%M%SZ).dump"
fi
health() {
  for attempt in $(seq 1 60); do
    if curl --fail --silent "http://127.0.0.1:$port/actuator/health" | grep -q '"status":"UP"' && curl --fail --silent "http://127.0.0.1:$port/" | grep -q 'app-root'; then return 0; fi
    sleep 3
  done
  return 1
}
rollback() {
  echo 'Deployment unhealthy; attempting image rollback.'
  if [[ "$previous" =~ ^[a-f0-9]{40}$ ]]; then
    export IMAGE_TAG=$previous
    "$compose" -f "$base/releases/$previous/docker-compose.$environment.yml" up -d --remove-orphans
    health || { echo 'Rollback health check failed'; exit 1; }
    echo "Rolled back to $previous"
  else
    echo 'No previous healthy release; inspect Notify containers. Shared services were not modified.'
  fi
  exit 1
}
trap rollback ERR
"$compose" -f "$file" up -d --remove-orphans
health
trap - ERR
if [[ -n "$previous" ]]; then printf '%s\n' "$previous" > "$base/state/$environment.previous"; fi
printf '%s\n' "$revision" > "$base/state/$environment.current"
echo "Healthy $environment release: $revision"
