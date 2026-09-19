// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个可用碎片合成的武将条目（合成后即获得该武将，B06 §1）。
 */
public record ComposeCandidate(
        String heroId,   // POST /hero/compose 要带的 heroId。
        String name)   // 武将中文名，服务端查 hero 表的 name 列（#255 建筑名、#268 资源名、#278 技能名、#281 碎片名同一路）。 **这一列是这一行存在的理由**：客户端只有 heroId 的话，候选行只能印 `hero_ssr_02` —— 服务端本来就握着名字，且「未拥有的武将」在别处一个字段都读不到。
{
}
