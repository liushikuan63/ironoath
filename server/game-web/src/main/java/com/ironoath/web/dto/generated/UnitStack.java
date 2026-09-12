// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个兵种堆叠。<b>客户端渲染的就是这个，不是单个士兵</b>：B05 §三 要求「每排最多渲染 12 个单位，超出用 ×N 图标聚合」，而服务端下发的本来就是按兵种聚合的数量，所以 10 万兵力也只有 4 个堆叠。这是低端机保 60 帧的关键，客户端绝不要试图把它展开成单个单位。
 */
public record UnitStack(
        UnitType unitType,
        long count)
{
}
