// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一张配置表的元信息（B16 §5 配置热更）。
 */
public record TableMeta(
        String name,   // 表名，形如 global / unit / season。
        String version,   // 表版本号。**仅供人读与日志排查，不参与热更判定**：版本号是人给的，会出现「改了内容忘了升版本」，那时客户端认为自己是最新的而实际上不是。判定一律走 hash。用字符串而不是整数，是为了将来能放 git 短 sha 这类非数字版本。
        String hash)   // 表内容的指纹，由行数据算出（不含版本号）。内容变了 hash 就变 —— 内容变了而 hash 没变在数学上不可能，这正是验收 7「修改配置表后客户端拉取新版本，不改包生效」的判定依据。
{
}
