// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 配置清单响应（验收 7）。同时给全量清单与「哪些表要更新」：清单用于展示与排查，outdated 用于决定下载什么。
 */
public record ConfigManifestResp(
        String version,   // 清单版本，取服务端全部表版本的指纹。客户端把它记在本地，下次请求时若相同则可以整份跳过 —— 那是一次纯粹的省流量优化，正确性仍然由每张表的 hash 保证。
        List<TableMeta> tables,   // 全部可热更的表（范围见 global.HOT_UPDATE_SCOPE）。
        List<String> outdated)   // 客户端需要下载的表名，按 tables 的顺序。**由服务端算**：比 hash 不比版本号这条规则只能存在一份。
{
}
