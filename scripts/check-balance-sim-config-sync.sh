#!/usr/bin/env bash
# Duty: keep balance-sim reading warehouse.capBase / resource initCap / producer rows
#       from the config tables instead of hardcoding them. See the .js sibling for the
#       full rationale (see checklist rows #592~#597).
#       This wrapper stays ASCII on purpose: a non-ASCII comment made bash
#       mis-decode the file and abort check.sh with exit 127.
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-balance-sim-config-sync.js
