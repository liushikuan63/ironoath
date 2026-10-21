// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 当前生效的公告列表。空列表是合法结果（没有公告时客户端不画面板，而不是画一个空的）。
 */
public record NoticeListResp(
        String version,   // 当前可见公告列表的内容 hash（SHA-256 前 16 位十六进制），不是人工表版本。文案变更、排序变更或服务端时刻跨过生效窗口都会变化；内容相同则无需重绘。
        List<NoticeItem> notices)   // 按 priority 倒序（数字大者在前）排好的生效公告。
{
}
