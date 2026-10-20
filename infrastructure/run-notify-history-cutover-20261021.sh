#!/usr/bin/env bash
# ONE-TIME 2026-10-21 cutover only. No cron entry, self-installation or recurrence.
# The parent task installs/schedules this reviewed file explicitly after testing.
set -Eeuo pipefail
umask 077

base=/opt/mibess-notify
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
sql="$script_dir/cutover-notify-history-20261021.sql"
cutover_name=history-cutover-20261021
state="$base/state/$cutover_name.done"
logdir="$base/backups/prd/$cutover_name"
start_epoch=$(date -u -d '2026-10-21 03:05:00 UTC' +%s)
end_epoch=$(date -u -d '2026-10-21 03:35:00 UTC' +%s)

[[ -r "$sql" ]] || { echo 'Cutover SQL is missing'; exit 78; }
mkdir -p "$base/state" "$logdir"
exec 9>"$base/$cutover_name.lock"
flock -n 9 || { echo 'The one-time cutover is already running'; exit 75; }
if [[ -f "$state" ]]; then
  echo 'This one-time cutover already completed; no changes made'
  exit 0
fi
now_epoch=$(date -u +%s)
if (( now_epoch < start_epoch || now_epoch >= end_epoch )); then
  echo 'Refusing execution outside 2026-10-21 03:05-03:35 UTC'
  exit 78
fi

# Scope is hard-coded: the Notify database in the shared PostgreSQL container.
# No shared service is stopped and no other database is dumped or modified.
database=$(docker exec postgres-prd psql -X -U mibess_notify -d mibess_notify -Atc 'SELECT current_database()')
[[ "$database" == mibess_notify ]] || { echo 'Unexpected database'; exit 78; }
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$logdir/before-$stamp.dump"
timeout 180s docker exec postgres-prd pg_dump -U mibess_notify -d mibess_notify -Fc > "$backup.partial"
[[ -s "$backup.partial" ]] || { echo 'Backup is empty; no cleanup attempted'; exit 1; }
docker exec -i postgres-prd pg_restore --list < "$backup.partial" > "$backup.list"
[[ -s "$backup.list" ]] || { echo 'Backup archive is unreadable; no cleanup attempted'; exit 1; }
mv -- "$backup.partial" "$backup"
sha256sum "$backup" > "$backup.sha256"
echo "Verified pre-cutover Notify backup: $backup"

attempt=0
while (( $(date -u +%s) < end_epoch )); do
  attempt=$((attempt + 1))
  attempt_log="$logdir/attempt-$stamp-$attempt.log"
  echo "Cutover attempt $attempt at $(date -u +%FT%TZ)"
  if docker exec -i postgres-prd psql -X -U mibess_notify -d mibess_notify < "$sql" > "$attempt_log" 2>&1; then
    if ! grep -q 'CUTOVER_COMMITTED_20261021' "$attempt_log"; then
      echo "No commit evidence; inspect $attempt_log. Not retrying automatically."
      exit 1
    fi
    {
      echo "completed_at=$(date -u +%FT%TZ)"
      echo 'cutoff=2026-10-21T03:00:00Z'
      echo "backup=$backup"
      echo "log=$attempt_log"
      echo "attempts=$attempt"
      sha256sum "$sql" "$backup"
    } > "$state.partial"
    mv -- "$state.partial" "$state"
    cat "$attempt_log"
    echo "One-time cutover complete. Evidence: $state"
    exit 0
  fi
  # Only transaction rollback conditions can retry: pending/locked rows,
  # deadlock, serialization conflict or PostgreSQL statement timeout.
  if ! grep -Eq 'ERROR:  (55P03|40P01|40001|57014):' "$attempt_log"; then
    cat "$attempt_log"
    echo "Non-retryable failure; no automatic replay. Inspect $attempt_log"
    exit 1
  fi
  remaining=$((end_epoch - $(date -u +%s)))
  (( remaining > 0 )) || break
  delay=30
  (( remaining >= delay )) || delay=$remaining
  echo "Historical work or locks still active; retrying in $delay seconds"
  sleep "$delay"
done
echo 'The finite cutover window expired. No further attempts will run; manual review is required.'
exit 75
