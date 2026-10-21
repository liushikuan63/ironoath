// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 白名单表的整表内容（热更取内容的响应）。
 */
public record ConfigTableResp(
        String name,   // 表名。**只有白名单内的表名会被取到**（白名单在服务端一处，见 global.HOT_UPDATE_TABLES）。
        int version,   // 人工维护的表版本，只供诊断。内容可在不升版本时变化，客户端是否刷新必须按 hash 判。
        String hash,   // 表原始行内容 SHA-256 的前 16 位十六进制，与客户端清单同源；取表时从同一份不可变快照计算。
        String json)   // 仅含白名单展示字段的 JSON 字符串（table/version/rows）；不会把内部 why 等文档字段下发。首版只有 notice，扩表需同步审查投影。解析失败必须显式报错，不能静默当空表。
{
}
