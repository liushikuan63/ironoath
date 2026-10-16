# Bot observation verified player scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline; one fresh-context whole-change review at the end. Continue the authorized queue without design approval checkpoints.

**Goal:** Reduce repeated remote lock writes during one Bot observation while preserving the original business read/settlement order, authoritative lock ownership, independent Quest lock and ordinary `runLocked` reentrancy.

**Architecture:** Add optional `PlayerLock.runWithSharedScope` whose default delegates to ordinary locking. Redisson marks only an explicitly acquired lexical scope and reuses the same thread/player outer hold only after an actual server owner check; unknown ownership propagates, lost ownership uses original acquisition, other keys/threads and ordinary calls keep original behavior. `BotWorldAdapter.observe` wraps its unchanged existing body in this scope; no facts are skipped or reordered.

**Tech Stack:** Project Java17, Spring Boot, Redisson3.27.2, existing Mockito/JUnit, actual owned LinuxJRE17/Mongo/RedisAOFalways fixtures.

**Spec:** Four existing records #1404–#1406 in the four project docs; full LinuxJFR2 and original production-lock cost experiments. `RedissonPlayerLockOwnershipTest` ordinary nested remote count2 is binding. Explicit-scope prototype3 is a cost proxy only: mean6.366→3.584ms, P995.536ms still above5; no business equivalence or overall budget success inferred.

## Global Constraints

- Keep original195 Java fixture, fbe runner,606 resource helper and final2e1 Linux runner unchanged. Fixed5000/30000ms/P995ms/180s/businessNow1791979200000/workers4/Netty2/46cfg remain.
- No change to locking keys, watchdog/lease settings, AOFalways, storage consistency, ordinary successful remote reentrant count2/release behavior, or independent Quest progress lock. Already interrupted calls fail before any Redis RPC with `LockTimeoutException` and the flag retained, closing the observed lost-acquisition-reply path.
- ThreadLocal is a lexical hint only, not owner authority. No identity/roster/time cache, permanent lock registry, timer or async context propagation.
- Preserve observation initialization, City/Army lazy settlement, Quest state refresh, RNG/order and original action error handling; this is scope coalescing, not lazy skipping of facts.
- Original9490Java21756/v35/45cfg/UID P67/ironoath_native_f28_r18/normal1x/WeChat25724 stay untouched. Only owned fixture resources; no original business HTTP/Mongo or new foreground.
- Local atomic commit only after evidence and final review. No push, production deployment, payment, dependency upgrade or unrelated edits. Windows failure and all failed Linux budget evidence stay.

## Review Focus

1. Scope hints cannot authorize a different client/thread/key; server owner read failure must not execute the nested action, lost owner must preserve a foreign holder and follow normal acquisition.
2. Ordinary `runLocked` still has remote nested count2 and unchanged validation/interruption/unlock behavior; explicit API is opt-in and default-compatible with test doubles and InJvm implementations.
3. Scope cleanup is complete after acquisition failure, nested/action/owner-read/release errors, interrupt, nested scopes and thread reuse. Existing watchdog and owner-checked unlock/CAS stay.
4. Bot observation's first owner read and all existing subsequent calls occur in the outer scope, with no skipped/reordered initialization/settlement/Quest/RNG. No new cross-player lock inversion from code outside the existing observation body.
5. Correctness evidence and benchmark source identities are distinct: final new JAR is compared to fresh4d7 Linux baseline under unchanged finite inputs; proxy and JFR timings do not substitute for full5000 budgets. Original live process/artifact ownership stays.

### Task1: Explicit verified scope in real Bot observation

**Production files:** `server/game-core/src/main/java/com/ironoath/core/lock/PlayerLock.java`, `server/game-web/src/main/java/com/ironoath/web/lock/RedissonPlayerLock.java`, `server/game-web/src/main/java/com/ironoath/web/bot/BotWorldAdapter.java`.

**Tests:** new `BotObservationPlayerScopeTest` exercising real initialized Bot/service/repository with a tracking spy of the existing lock; new `RedissonPlayerSharedScopeTest` for explicit opt-in/owner/error/thread/depth paths; extend existing `RedissonPlayerLockOwnershipTest` for real explicit-scope controls without weakening ordinary count2.

- [x] First add and actually run a meaningful Bot observation regression: owner read occurs before any player lock in the current source. Record assertion failure, not import/compile/zero-test error.
- [x] Add compatible default scope API, then explicit Redisson scope unit regressions and actually observe functional failures before adding reuse logic. Preserve all existing ordinary-lock tests.
- [x] Implement minimal owner-verified explicit scope and wrap the unchanged observation body. Run focused green, actual Redis two-client/lost-owner/interrupt controls and effective mutants for owner check/scope cleanup/key/thread boundaries.
- [x] Freeze source, full build/check with fresh nonzero JUnit counts and no fail/error/skip; client1344 and tools/static counts are rerun. Create new frozen candidate JAR/config with deep class/resource diff; production call sites confirm the new API is actually used.
- [x] Final-source Linux memory three phases and fresh4d7 vs new persistent three phases, same195/2e1/5000/time/rules/stores/Netty2/workers4/AOFalways. Audit all raw samples, calibration/upgrades/cities, owner cleanup, contexts/threads. Preserve failed budgets and avoid stable-speedup claim from a single pair.
- [x] Reuse unchanged client artifact only with explicit historical identity, run new owned backend startup and exact related17 runtime paths. No fresh Cocos/WeChat/native or remote CI claim without actual execution.
- [ ] Append actual four-doc record and plan/progress, one fresh most-capable whole-change review and bounded same-seat fixpass if needed; explicit paths local atomic commit and blob readback. Continue original unfinished5000 queue.

Ruling: The first generic prototype would have changed ordinary raw reentrancy. Use an explicit new scope API instead, leaving the existing count2 regression untouched. This costs one internal default method but avoids a global compatibility change.

Ruling: The prototype1 exception-type precondition was initially treated as a harness assumption. Actual independent-key Redis regression later proved that a pre-interrupted nested acquire can send a reentry but lose its acknowledgement, leaving the peer blocked after outer release (real-redis1:11run/1assertfail/0errskip). The strengthened real-redis2 regression also fails on the old RedisException instead of the required pre-RPC failure. Reject already interrupted calls before any Redis operation, retain the interrupt flag, and keep normal successful count2/release semantics; this fixes the observed boundary rather than preserving a leaked hold. Mid-flight interruption/transport acknowledgement recovery is not redesigned here.

Final implementation checkpoint: actual validation summary above; review/approval/commit receipts decide final status. Historical unchecked/pending text is not an overall goal completion claim.
