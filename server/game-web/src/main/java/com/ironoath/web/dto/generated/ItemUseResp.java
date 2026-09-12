// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /item/use 响应体。granted 是实际入账的部分，overflow 是装不下（资源满仓 / 背包满格）而转邮件的部分 —— 两者都要回，否则满仓时用资源箱的玩家只看到 granted 为空，从他视角这就是一次静默失败（B04 禁止项：不得静默吞掉）。
 */
public record ItemUseResp(
        long consumed,   // 实际消耗的道具数量
        List<ResourceAmount> granted,   // 使用后实际入账的资源
        List<ResourceAmount> overflow,   // 装不下而转邮件的资源（B04 验收 2）
        String mailId,   // 溢出转邮件的邮件 id，无溢出时为 null
        Long reducedSeconds,   // 加速类道具实际提前的秒数
        Long peaceUntil,   // 免战类道具使用后的停战到期时刻（服务端毫秒）。null = 这次使用与免战无关。 必须下发而不是让客户端自己按 effectValue 推算：多张牌叠加时取的是「延长到多晚」，而延长规则在服务端（只延长不缩短），客户端算出来的时刻会与真实值不一致 —— 面板显示还剩 3 小时、实际 24 小时，玩家会据此决定要不要再买一张。
        long serverNow)
{
}
