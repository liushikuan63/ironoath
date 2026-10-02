#!/usr/bin/env bash
# Duty: keep balance-sim's tech bonus read from the tech config table (delegates to the .js).
# This wrapper stays ASCII on purpose: a non-ASCII comment made bash mis-decode the file
# and abort check.sh with exit 127 (see check-balance-sim-config-sync.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-balance-sim-tech-sync.js
