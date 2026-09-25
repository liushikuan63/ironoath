// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 世界坐标（格）。与 world 协议的 Coord 形状相同，但生成器只支持同文件 $ref，所以这里各有一份。**两份的字段名与类型必须一致**，由 SocialContractParityTest 断言 —— 复制而不校验才是真正的危险：漂移的症状是服务端下发的字段在客户端解析成 undefined，TS 侧不会报错，UI 只会空白。
 */
public record SocialCoord(
        int x,   // 横坐标（格）
        int y)   // 纵坐标（格）
{
}
