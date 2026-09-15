// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一封邮件的一条附件。与 bag 协议的 `RewardItemView`、quest 协议的 `QuestReward`、stage 协议的 `StageReward` 形状相同，但生成器只支持同文件 `$ref`，所以这里又有一份。**四份的字段与枚举取值必须一致**，由 `StageContractParityTest` 一族的反射比对钉住 —— 复制而不校验才是危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
public record MailReward(
        String type,   // 奖励类型，取值与 bag 协议的 `RewardType` 一致（CI 的枚举一致性守卫会核对两边同名同序）。
        String id,   // 资源/道具/碎片的标识。
        long count,   // 数量。这里记的是**当初溢出或补发的量**，不是「还能领多少」—— 领取失败时这一格不许被改成 0，否则玩家看不到自己少了什么。
        String name)   // 展示名，由服务端解析好下发（客户端不查配置表，铁律 2）。
{
}
