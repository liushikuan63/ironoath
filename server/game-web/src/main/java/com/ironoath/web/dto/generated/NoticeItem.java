// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条当前生效的运营公告。文案由服务端原样下发，客户端只展示。
 */
public record NoticeItem(
        String id,   // 公告 id。**定位用，不印给玩家** —— 客户端拿它去拉表内容与做去重，界面上出现的是 title 与 body。
        String title,   // 公告标题，服务端原样下发。
        String body,   // 公告正文，服务端原样下发。**不允许客户端拼装或截断**：改文案是运营的日常动作，而客户端硬编码的文案在提审期被要求修改时只能发版。
        long priority)   // 排序权重，**数字大者排前**。由服务端排序，客户端不重排（重排就会出现第二个排序真源）。
{
}
