# Quest state refresh eligible consumers

> Execute inline with superpowers:executing-plans, TDD and one fresh whole-change reviewer. Continue the existing authorized queue without another approval checkpoint.

Goal: Avoid state-source queries and no-op progress saves only when the emitted state event has no unlocked matching consumer. Keep original quest event matching, current-state updates (including claimed entries), rollover, receipt recovery and independent progress locks.

Evidence: #1409 full Linux JFR3 after fab77 scope: observe selected Mongo wait71359ms and independent Quest lock19615ms. Source refresh loops read locked goals and mark dirty even if onEvent ignores them. This is a testable local inefficiency, not a claim it alone solves5000/30s/P995ms.

Ruling: Checking only the source entry's unlocked bit is unsafe. A locked specific-target row can emit an event accepted by an unlocked wildcard or same-target row. Use existing GameEvent.matchesTarget and QuestProgress.unlocked to ask whether any matching entry can consume that source event; preserve original iteration/event order and claimed-entry refresh. No gameplay rule change.

Scope: QuestAppService and focused tests; no new endpoint/config/dependency, no broad observation skipping or owner snapshot cache. Keep owner read/gold recovery, original domain onEvent and outer/progress lock order unchanged. Fixed195/fbe/606/Linux2e1 and5000/30000ms/P995ms/180s/workers4/Netty2/46cfg/AOFalways stay. Original9490Java21756/WeChat25724/P67 originalDB1x untouched. Local commit only; no push/deploy/payment/foreground.

- [x] Add a focused production-service/domain regression and observe real assertion failures for locked social queries and no-op saves; preserve raw XML and exact pre-fix source.
- [x] Cover target matching through wildcard and different-target controls, unlocked and claimed state, next-read unlocking, daily rollover, active-source failure and exact independent lock order. Implement the smallest eligibility guard, then green and effective mutation controls. Weekly reset is unchanged and covered by the full existing suite, not a new focused case.
- [x] Freeze source and rerun full build/check:301fresh XML/2912JUnit/0failerrskip,1344client33Node28Python47gates. New candidate2 f5667542/46cfg onlyQuestAppService3classes, two nested javap raw outputs identical, resources0. Canonical Linux memory/freshfab77/newpersistent9phases45000raw IDs/P99/budget audited, all correct/cleanup true; new persistent106803.739/85192.586/61877.750ms P9934.313922/33.394240/29.005444, all budgets false. Exact17FIRST0; reused337client, ordinary1x headless guide314assertions0fail and realclaim→guide3 with three actual screenshots viewed. Not314business flows, seven-step completion, new Cocos/WeChat/Windows pressure or CI evidence.
- [ ] Append actual four-doc record with limitations, obtain one fresh most-capable whole-change review, bounded red-green fix if needed; explicit-path local atomic commit and independent blob readback. Continue overall budget queue if still false.

Final gate status is governed by the bound quest-refresh approval/commit receipts, which supersede this pre-review checklist once actually produced. Current5000 persistent budgets remain false; zero new shutdown-error lines does not repair historical#1407. Original195/fbe/606/Linux2e1, prior6source/tests and original9490/Java21756/WeChat25724/v35jar45cfg identities verified unchanged; no original business HTTP/Mongo access.
