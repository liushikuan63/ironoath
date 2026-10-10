// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * （Schema 未提供说明）
 */
public record ResourceLearningView(
        ResourceType type,
        String name,   // 服务端资源中文名
        String description,   // 服务端资源用途与手动教学说明
        boolean manualRequired,   // 是否要求手动采集
        boolean manuallyCollected,   // 是否已确认该种资源真实产出
        String collectableBuildingId)   // 有可确认产出的已建成资源建筑；无产出时为null
{
}
