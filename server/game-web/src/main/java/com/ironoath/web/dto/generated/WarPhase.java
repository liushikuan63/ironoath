// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 国战处在哪一段（B13 §一 §7、B21 §一）。
 *
 * 取值必须与 game-core 的 `WarScoreBoard.Phase` **逐一对应且同序**，由 `WarEndpointTest.warPhaseMatchesTheDomainEnum` 断言钉住 —— 与 `NationOffice` 那条同一条教训：复制而不校验才是真正的危险，漂移的症状是服务端认得的阶段客户端显示成未知，而 UI 只会空白。
 *
 * 三段各自能做什么：
 * - `PREPARATION` —— 筹备：联盟争夺王城周边 `WAR_GATE_COUNT` 座关卡，占到**任意一座**即取得进攻资格。不要求全占，因为全占会让弱势国家永远打不进王城，而国战的观赏性恰恰在翻盘可能。
 * - `SIEGE` —— 王城战进行中，时长 `WAR_DURATION_HOURS`。占领分按分钟累积，这是「防最后一秒偷家」的执行机构。
 * - `SETTLED` —— 已结束，积分定格，只读。
 *
 * **没有任何一段由常驻定时器推进**：阶段、剩余秒数与占领分都由读取动作现算（`check-no-scheduled.sh` 是门禁，服务端不许跑定时任务）。
 */
public enum WarPhase {
    PREPARATION,
    SIEGE,
    SETTLED
}
