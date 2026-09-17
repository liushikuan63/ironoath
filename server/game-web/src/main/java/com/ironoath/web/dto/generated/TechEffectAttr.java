// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 这行科技改的是哪一个数。取值同样与 `tech.json` 的 `effectAttr` 列同源 —— 它是**乘区归属的路标**（B20 §四：不许为科技新开乘区），所以下发原样而不是压成一个通用百分比：客户端要按属性分组显示，而服务端要按属性决定它进产量算式还是进乘区 B。
 */
public enum TechEffectAttr {
    WOOD_OUTPUT,
    STONE_OUTPUT,
    IRON_OUTPUT,
    GRAIN_OUTPUT,
    UNIT_ATTACK,
    UNIT_DEFENSE,
    MARCH_SPEED,
    TRAIN_SPEED,
    BUILD_SPEED,
    HOSPITAL_CAPACITY,
    LOAD_CAPACITY
}
