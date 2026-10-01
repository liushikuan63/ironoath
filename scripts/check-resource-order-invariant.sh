#!/usr/bin/env bash
# Duty: keep the player resource bar in config-table order. Watches PlayerSave.resources()
#       plus the two neighbouring hops (PlayerInitService.createNewPlayer and
#       PlayerDocumentMapper.toDocument/toDomain) for any order-breaking map copy
#       (Map.copyOf & friends). See checklist rows #616~#618 for the full rationale.
#       The gate self-tests its own detector on synthetic fixtures before scanning,
#       so "cannot locate the method" can never be reported as a pass.
# This wrapper stays ASCII on purpose: a non-ASCII comment made bash
# mis-decode the file and abort check.sh with exit 127 (see check-balance-sim-config-sync.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/check-resource-order-invariant.js
