// 由 tools/config-gen 依据 contract/config/avatar_frame.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 avatar_frame 的一行。
 * 头像框（B24 块③ 外观）。外观只卖表现、不许碰数值（B15 §一 第 7 条 + 公理一），所以这张表里**只有展示字段**：名字、稀有度、占位色。占位色是给运行时 Graphics 画的占位框用的 —— 素材库今天没有头像框这一族（find client/assets -iname "*frame*" 零命中），所以 2026-09-19 裁决走「占位拼一版，先把链打通」：先做真的链（拥有/穿戴/卸下/零数值影响）、假的图，美术到位后把这一列换成贴图键即可，判定与协议都不动。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/avatar_frame.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record AvatarFrameCfg(
        String id,   // 主键
        String name,
        Rarity rarity,   // 枚举，取值见 AvatarFrameRarity
        String placeholderColor)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Rarity {
        N,
        R,
        SR,
        SSR
    }

}
