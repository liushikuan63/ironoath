#!/usr/bin/env bash
# Duty: guard that the balance-sim f2p simulator reads warehouse.capBase from the
#       config table instead of hardcoding it. See scripts/check-balance-sim-capbase.js
#       for the full rationale and the two failure shapes it catches.
#       This wrapper stays ASCII on purpose: a non-ASCII comment made bash
#       mis-decode the file and abort check.sh with exit 127.
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-balance-sim-capbase.js
