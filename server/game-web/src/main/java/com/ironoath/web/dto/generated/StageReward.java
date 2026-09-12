// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 奖励条目。与 bag 协议的 RewardItemView 形状相同，但生成器只支持同文件 $ref，所以这里各有一份。**两份的字段与枚举取值必须一致**，由 StageContractParityTest 断言 —— 复制而不校验才是真正的危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
public record StageReward(
        String type,   // 奖励类型，取值与 bag 协议的 RewardType 一致（CI 校验）
        String id,   // 资源 id / 道具 id / 武将 id，含义由 type 决定
        long count,
        String name)   // 中文显示名，服务端下发。客户端不得自行翻译：飘字与战报里的称呼必须一致
{
}
