// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 当前生效的公告列表。空列表是合法结果（没有公告时客户端不画面板，而不是画一个空的）。
 */
public record NoticeListResp(
        int version,   // notice 表的版本号（内容变更即变）。**客户端拿它判断「要不要重画」** —— 版本没变就不动界面，这是「安全刷新点」的另一半：拉一次是真的，但没变就别让玩家看见界面闪一下。
        List<NoticeItem> notices)   // 按 priority 倒序（数字大者在前）排好的生效公告。
{
}
