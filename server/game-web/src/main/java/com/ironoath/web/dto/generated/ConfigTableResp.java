// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 白名单表的整表内容（热更取内容的响应）。
 */
public record ConfigTableResp(
        String name,   // 表名。**只有白名单内的表名会被取到**（白名单在服务端一处，见 global.HOT_UPDATE_TABLES）。
        int version,   // 该表的版本号（内容变更即变）。客户端拿它判断「要不要重画」：版本没变就不动界面，这是「安全刷新点」的另一半。
        String json)   // **整表 JSON 字符串**（含 table/version/rows）。为什么是字符串而不是结构化对象：表会增删，把每张表的行结构写进契约等于每加一张表就改双端代码并重新生成；而客户端本来就持有生成的 TS 类型，解析后按那个类型断言即可。**解析失败必须当失败处理**，不许静默用空表继续跑 —— 静默吞掉解析失败，症状是「公告位空了」而不是「热更坏了」。
{
}
