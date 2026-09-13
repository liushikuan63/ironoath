// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 当前尚未放置、可进入首次建造流程的建筑配置。这里不预先判断具体地块能否放置；地块在玩家点选坐标后由服务端统一校验，客户端不得复制网格规则。
 */
public record BuildOptionView(
        String configId,   // building.json 的行 id
        String name,   // 建筑中文名，来自配置表
        String type,   // 建筑类型，来自 building 表的 type
        int requireMainLevel,   // 主城等级门槛，仅用于展示；实际校验在服务端
        String requireBuilding)   // 前置建筑 id；null 表示无前置
{
}
