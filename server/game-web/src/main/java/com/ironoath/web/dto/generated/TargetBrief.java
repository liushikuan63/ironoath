// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个可攻击目标的摘要。**不含距离数值、不含精确资源量、不含 isBot**（B08 验收 12、B07 禁止项、B11 合规）。
 *
 * coord 是必须给的：不知道坐标就无法出兵。所以「不下发精确距离」在这里不是保密，而是不给客户端一个可以直接渲染成倒计时数字的原料，理由见 DistanceBand。
 * resourceHint 才是真正的情报保护 —— 精确库存无法从响应里的任何其它字段推导出来，它只能靠侦查获得，给数字就等于把 B07 §3 的「情报有误差」删掉。
 *
 * tyrannyLevel 高于 COMMONER 时表示攻击他可以拿到围剿加成 —— 这是「给弱者制造反击靶子」在客户端的入口。
 */
public record TargetBrief(
        String id,
        String name,
        Coord coord,
        long matchPower,   // 目标的匹配战力（不是展示战力）。圈层校验用的是它
        long powerRatio,   // 相对自己的倍率（定点 ×10000）。下发倍率而不是只下发对方战力，是因为玩家真正要判断的是「我打得过吗」，而那个判断需要知道自己的匹配战力 —— 让他自己做除法等于把口径交给客户端
        DistanceBand distanceBand,
        ResourceHint resourceHint,
        boolean isShielded,   // 是否处于护盾。护盾目标不进候选池（B08 §8），但已下发的目标要能被标出来
        String tyrannyLevel)   // 暴虐档位；平民或无记录时为 null（不下发 COMMONER，因为那一档没有任何效果，下发只会让 UI 多一个无意义的标签）
{
}
