// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 头像框（B24 块③ 外观）的一行。**只有展示字段**：外观不参与任何数值判定（B15 §一 第 7 条 + 公理一），所以这里既没有战力也没有属性，客户端按 placeholderColor 画占位框。
 */
public record AvatarFrameView(
        String frameId,   // avatar_frame 表的行 id
        String name,   // 显示名，服务端下发（改名字不该发一次版）
        String rarity,   // 稀有度，取值与 avatar_frame 表的 rarity 列一致（N/R/SR/SSR）
        String placeholderColor,   // 占位框的颜色（#RRGGBB）。素材到位后这一列换成贴图键，客户端的画法跟着换，判定与协议不动
        boolean owned,   // 是否已拥有。**拥有是永久事实**：卸下之后它仍然是 true
        boolean worn)   // 是否正戴着。**佩戴是当下选择**：与 owned 是两位，合成一位会让「卸下 = 失去」
{
}
