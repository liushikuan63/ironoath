# B13 strategic COMBAT production and recovery

## Scope

Wire the already modeled strategic battle into a durable, replayable production flow. Keep `MarchAppService` and `SocialAppService` strategic-target entry gates closed until the full operation passes its recovery and acceptance checks. This plan does not open target contests, select provisional troop values or coordinates, implement no-resistance capture, build national income, or complete B19.

The caller must preserve the established lock order: all involved nation locks, all original attacker/defender rally locks, then the single `@strategic-target:<targetId>` lock. Re-read the original March, target anchor, operation slot, and player defender under the target lock. If a rally/owner context changes, release every lock before redispatch. Pending COMBAT must be read from its frozen plan before consulting live defender state; partial target writes may make that live state intentionally unreadable.

## Existing source boundaries

- `CapturedBattlePreparation.Scope.freezeTargetBattle` creates the immutable original `CapturedStrategicBattle` from one captured config view and one actual simulation.
- `TargetOperationStore` already stores monotonic `WITHDRAWAL`/`COMBAT` operations with CAS, frozen battle payloads, anchors, and completed barriers. `StrategicGarrisonWithdrawal` currently fails closed when it sees `COMBAT`.
- `StrategicWoundSettlement`, `StrategicBattleHospitalSettlement`, `StrategicBattleMarchOutcome`, `NeutralTargetGarrisonStore`, `TargetOccupationStore`, `WarStore.recordBattleEvent`, and `BattleReportStore.saveExact` are independently tested persistence/model primitives. None currently forms a production combat publisher.
- `StrategicTargetMarchAdvancer` establishes both-side rally locks before target lock and rereads a live player defender under the target lock. It must first learn how to derive those rally locks from a pending frozen COMBAT plan.

## Implementation sequence

1. **Lock order and call boundary — implemented #2390.** Strategic Marches now enter `StrategicTargetMarchAdvancer` before the old single-rally path. It acquires all known attacker/defender Rally locks through `SocialAppService.runRalliesDepartureLocked`, then the existing `@strategic-target:<id>` lock; it rereads the owner/defender under that lock and releases all locks before redispatch when context changes. Effective RED proved the previous caller locked only the attacker. The contest gate remains closed.
2. **Pending-operation preflight — implemented #2391.** An effective regression stores a pending real `COMBAT` plan while the live defender reader is deliberately unavailable. The caller derives Rally locks from the frozen operation payload, never reads potentially partial live target state, and stops at the current explicit unsupported-COMBAT recovery barrier. It rechecks the exact slot under the target lock; unknown/replaced operation state cannot proceed.
3. **Durable publisher.** Under the established locks, prepare the real target defender, run `SIEGE` for NPC and player garrisons, freeze the actual kernel result/hospital facts, and CAS a pending `COMBAT` slot before any injury, ownership, garrison, March, report, or War mutation. Same-operation replay must return the identical payload; changed source versions or target anchors fail closed.
4. **Ordered recovery owner.** Dispatch pending operations by `Kind`; do not pass COMBAT to withdrawal code. Derive every step from the frozen plan and independently persisted receipts. Each unknown write requires exact independent readback. Preserve order and barriers across per-member hospital receipts, NPC survivor/retired state or player ownership, attacker/defender March HOLD/RETURN/ANNIHILATED CAS/delete, due queue scheduling, reports, War event, then COMBAT completion. A failed prerequisite leaves the slot pending and prevents ordinary March advancement.
5. **Call-site integration and failure matrix.** Route strategic arrival and `processDue` recovery through the publisher/recovery owner; test interruption/restart after every persistent boundary, stale versions, unknown replies, target-context replacement, full annihilation, NPC survivors, player garrison survivors, mixed hospital capacities, and a newer operation trying to pass the completion barrier. Keep NPC/player `SIEGE` uniform.
6. **War attribution and evidence.** `B13_国家与国战.md` says `BattleImpact.kills` is the already-decided enemy deaths assigned to each member and explicitly forbids inventing rally kill distribution. Search for an existing authoritative allocation rule. If none exists, preserve that question in the open ledger and keep the final War step/gate fail-closed rather than assigning kills proportionally by assumption. Continue the storage/capture/recovery work that does not depend on this ruling.

### Arrival preservation boundary — #2393

Before the publisher is available, a persisted strategic attack must not pass through the ordinary unsupported-target catch and become an unfought return. Effective RED reproduced that behavior from both `resolveAttack` and the actual due-arrival path. The latter now saves the original `FIGHTING` March with CAS before raising an explicit unsupported-COMBAT error. A subsequent scan retains the same version, troops, and due event. The departure gates remain closed. This preserves a recovery input; it does not publish a COMBAT plan or settle any battle effects.

The current user rulings are B07 terrain mapping, actual engine member contribution for kill attribution, and B08 power-band validation for every defender including NPC. An aggregate engine result is not evidence of per-member contribution; that source must be implemented or identified before the War event step can be enabled.

## Verification and commit boundary

For each implementation cell, add the failing production-path test first, run its minimal RED, implement the smallest complete step, then run the related JUnit classes with `-am` so reactor dependencies come from this source tree. Run `check.sh` with Node 20 and the checklist append/table gates after ledger edits. The earlier Docker secrets-engine failure is superseded: Mongo is available and the strategic contract tests execute against random `TestMongo` databases that are dropped on close; Redis ownership/queue tests have passed with UUID namespaces. These are prerequisite checks, not a fresh full-suite/build result. Keep the real player database untouched. Do not run 5000 without a fresh holder/resource/UTC admission. Commit exact paths atomically, fast-forward `master`, and do not push.

Overall Goal remains open until production publication, ordered recovery, real capture/defense/hospital/March/War/report effects, national income/restoration, B19 acceptance, unresolved value/coordinate decisions, and the required isolated runtime evidence are each handled.
