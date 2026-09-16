// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 领取时入账的一条奖励。与 bag 的 `RewardItemView`、quest 的 `QuestReward`、stage 的 `StageReward`、mail 的 `MailReward` 形状相同，但生成器只支持同文件 `$ref`，所以这里又有一份（第 5 份）。**五份的字段与枚举取值必须一致**，由 `ActivityEndpointTest` 的反射比对与 `StageContractParityTest` 一族一起钉住 —— 复制而不校验才是危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
public record ActivityReward(
        String type,   // 奖励类型，取值与 bag 协议的 `RewardType` 一致（CI 的枚举一致性守卫会核对同名同序）。
        String id,   // 资源/道具/碎片的标识。
        long count,   // 入账数量（不是申请量）。`RewardService.grantReward` 算完溢出之后写回来的就是它。
        String name)   // 人看得懂的名字，与 `RewardLine.name` 同源（`RewardNames`）。**旧记录不许回填**：名字只在下发那一刻取一次。
{
}
