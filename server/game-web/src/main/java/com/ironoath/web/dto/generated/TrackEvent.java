// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.Map;

/**
 * 一个埋点事件（B16 §3 事件字典的一行）。事件名不在契约里枚举：字典是运营与分析侧的资产，会随版本增删，写进契约就等于每次加一个按钮都要改双端代码并重新生成 —— 而验收 3 要求的覆盖率是拿脚本比对 UI 清单，不是比对枚举。
 */
public record TrackEvent(
        String name,   // 事件名，形如 startup / login / guide_step / building_upgrade_start / speedup_used / pay_click / pay_success / battle_start / battle_lost / churn。没有名字的事件在分析侧无法归类，却已经占了上报配额，所以服务端与客户端都拒绝空名。
        long ts,   // 事件发生的客户端毫秒时间戳。**只用它排序，不用它算时长**：B00 硬约束「时间以服务端时间戳为准」，客户端时钟可以被玩家改，所以任何留存/时长口径都以服务端落库时间为准，这个字段的价值是保留同一批事件内部的先后顺序 —— 而那个顺序在服务端落库时会因为批量到达而丢失。
        Map<String, String> params)   // 事件参数。值统一为字符串：埋点参数会进日志与看板，类型化的代价是每加一种参数类型就要改契约，而收益只有「少写一次 String.valueOf」。空 map 表示无参数，不允许 null —— 下游遍历 null 会 NPE，而 NPE 发生在上报线程里会让整批事件消失。
{
}
