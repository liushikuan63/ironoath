#!/usr/bin/env bash
# Duty: keep BalanceCli's usage text from silently dropping switches (delegates to the .js).
# This wrapper stays ASCII on purpose: a non-ASCII comment made bash mis-decode the file
# and abort check.sh with exit 127 (see check-balance-sim-config-sync.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-balance-sim-usage.js
