// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条已发放的任务奖励。与 bag 协议的 RewardItemView、stage 协议的 StageReward 形状相同，但生成器只支持同文件 $ref，所以这里各有一份。**三份的字段与枚举取值必须一致**，由 StageContractParityTest 一族的断言钉住 —— 复制而不校验才是真正的危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
public record QuestReward(
        String type,   // 奖励类型，取值与 bag 协议的 RewardType 一致（CI 校验）。任务表目前产出 RESOURCE / HERO_FRAGMENT / HERO 三种（HERO 是整卡武将，首日主线赠送用）。
        String id,   // 资源 id（WOOD/IRON/GRAIN/GOLD）、道具 id、或武将 id（HERO 直接给这名武将；HERO_FRAGMENT 由发放器换算成按稀有度的碎片道具）。
        long count,
        String name)   // 服务端从配置表解析后的展示名，客户端不得自行翻译。
{
}
