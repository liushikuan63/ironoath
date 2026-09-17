// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /equip/forge 请求体（§二 草样原样）。一次只强化一件一级：没有「一键 +5」，
 * 因为逐级递增的价格意味着批量要按五档分别计价，而那正是最容易算错、也最难向玩家解释的地方。
 */
public record EquipForgeReq(
        String requestId,   // 幂等键。与城建/训练/研究同一套机制：双击与客户端重试都会重复投递， 而没有去重的强化会一次扣两级铁 —— 这是这条线上唯一一处「重试比不重试更糟」的地方。
        String equipUid)   // 要强化的**那一件**的实例号。传配置行 id 会失败（`PARAM_INVALID`），错误信息里写明「要 uid 不是行 id」： 这个混淆在实例化之后必然发生，报错要说清去哪拿 uid。
{
}
