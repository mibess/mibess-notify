#!/usr/bin/env bash
set -euo pipefail
# Dedicated deployment key accepts only a Notify environment and immutable commit.
if [[ ${SSH_ORIGINAL_COMMAND:-} =~ ^deploy\ (hml|prd)\ ([a-f0-9]{40})$ ]]; then
  exec /opt/mibess-notify/infrastructure/deploy.sh "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi
echo 'Only Notify deploy hml|prd <40-character SHA> is permitted.' >&2
exit 2
