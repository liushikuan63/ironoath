// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /hero/equip 请求体。equipUid 为 null 表示卸下该槽位（B06 验收 10：卸下后加成必须消失）。
 *
 * **为什么字段叫 equipUid 而不是 equipId**（B20 §五⑤）：装备现在是一件一个实例，"穿哪一件"与"穿哪一行"不是一回事。服务端仍然接受配置行 id（老客户端的写法，按"该行第一件未穿的"解析并记 ERROR 日志），但字段名按目标语义写 —— 名字留着旧语义，客户端改起来就没有方向。
 */
public record HeroEquipReq(
        String requestId,
        String heroId,
        EquipSlot slot,
        String equipUid)
{
}
