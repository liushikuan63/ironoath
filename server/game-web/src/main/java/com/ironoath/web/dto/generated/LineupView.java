// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一套编队预设（B06 §4：每队 3 名 = 主将 + 2 副将，可编 3 套）。
 */
public record LineupView(
        int presetIndex,
        String main,   // 主将 heroId；null 表示该位置空着
        String sub1,
        String sub2,
        HeroBonus bonus,
        List<String> activeBonds)   // 已激活的缘分（成对同队才算）。给客户端做高亮与提示「再抽到 X 就能激活缘分」
{
}
