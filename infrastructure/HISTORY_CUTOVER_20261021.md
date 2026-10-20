# One-time history cutover: 21 October 2026

The owner authorized removal of all Notify data through 20 October. This follow-up removes history that arrives after the initial cleanup and before the start of valid business data. It is a single dated operation, not an ongoing retention policy.

- Business cutoff: **2026-10-21 00:00 America/Sao_Paulo**, equivalent to **03:00 UTC**.
- Scheduled execution: **00:05 São Paulo / 03:05 UTC**.
- Absolute retry deadline: **00:35 São Paulo / 03:35 UTC**. No retries after that date/window.
- Database: only `mibess_notify`, user `mibess_notify`, container `postgres-prd`.

`cutover-notify-history-20261021.sql` removes events created before the cutoff and all notifications derived from those events. It also removes independent notifications created before the cutoff. Their attempts, timelines and outbox entries belong to the same historical aggregate, even when a receipt or retry completes after midnight. Events created at or after the cutoff and their notifications remain. Standalone webhook receipts and inbound messages use `received_at`; audit entries use `created_at`. The comparison is strictly `<`, preserving the boundary instant itself.

The SQL does not change applications, rules, templates, contacts, groups, channels, credentials/API keys, consent/suppression data, images, users, sessions, schema or another database. It verifies that only the two intended Artgian applications exist. It acquires history-table locks, then aborts rather than deleting old pending/queued/processing work, accepted events, unpublished outbox rows or unprocessed webhook receipts. All deletion and postconditions commit together. New unrelated work is not considered a reason to abort.

`run-notify-history-cutover-20261021.sh` first takes a private custom-format database backup and verifies its archive directory with `pg_restore --list`. It records its checksum before invoking cleanup. A process lock prevents concurrent runs. Only known transaction rollback failures retry every 30 seconds until the absolute deadline. Other failures stop for review. The completion marker prevents another execution after success; SQL deletion is also idempotent. Neither script installs a timer or calls a production endpoint by itself.

The reviewed scripts were installed together under `/opt/mibess-notify/maintenance/20261021/` after delivery tests and the final recipient switch. The versioned `notify-history-cutover-20261021.service` and `.timer` were installed under `/etc/systemd/system/`. The timer is enabled, persistent across host restarts, and has exactly one dated `OnCalendar=2026-10-21 03:05:00 UTC` entry. `systemd-analyze verify` passed and `systemctl show` confirmed `ActiveState=active` and the expected next execution. The cleanup itself was **not yet executed** when these notes were written on 20 October.

Do not add a recurring calendar expression. The runner refuses execution outside its fixed window; if the host remains unavailable past the deadline, inspect manually. Verify completion with:

```bash
systemctl status notify-history-cutover-20261021.service
cat /opt/mibess-notify/state/history-cutover-20261021.done
journalctl -u notify-history-cutover-20261021.service
```

Evidence is written with mode restricted by `umask 077` under `/opt/mibess-notify/backups/prd/history-cutover-20261021/` and `/opt/mibess-notify/state/history-cutover-20261021.done`. Inspect the service journal and marker after the run. The backup is emergency recovery material; restoring it would also revert later valid records, so recovery must be selective and separately reviewed.

Validation during preparation: `bash -n`, `git diff --check`, and eight local runner scenarios with mocked `docker`, clock and sleep commands passed. The scenarios covered success, retry after pending work, failed backup, unreadable backup, premature execution, expired window, non-retryable SQL failure and retry deadline. A second successful invocation did not execute SQL again. No production database or timer was touched by these checks. The SQL was reviewed against migrations V1–V5; a real database execution remains the parent's separate rollout step.

The timer installation is verified; future execution must be distinguished from scheduling and checked through its dated completion marker.
