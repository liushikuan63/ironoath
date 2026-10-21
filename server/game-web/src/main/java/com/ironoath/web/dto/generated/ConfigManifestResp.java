// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 客户端展示表白名单清单（V26）；运维全量变更审计仍使用服务端内部全表清单。
 */
public record ConfigManifestResp(
        String version,   // 白名单表名与内容 hash 的指纹；人工表版本没升但内容变更时也会变化。客户端是否下载仍以各表 hash 为准。
        List<TableMeta> tables,   // 客户端可下载的展示表，按 global.HOT_UPDATE_TABLES 与服务端安全分类双重过滤；不列玩法或经济表。
        List<String> outdated)   // 客户端需要下载的表名，按 tables 的顺序。**由服务端算**：比 hash 不比版本号这条规则只能存在一份。
{
}
