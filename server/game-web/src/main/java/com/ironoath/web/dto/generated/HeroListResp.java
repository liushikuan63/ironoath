// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /hero/list 响应体。
 */
public record HeroListResp(
        List<HeroView> heroes,
        List<LineupView> lineups,
        List<FragmentView> fragments,   // 各稀有度的碎片持有量。碎片是道具（item 表的 item_mat_hero_frag_*），行里连名字一起下发 —— 这一行**包含余数为 0 的档**，客户端没法从背包 join 到名字（背包不列 0 余数的行），见 FragmentView 的理由
        long troopCap,   // 当前带兵上限 = Σ上阵武将统帅值 × TROOP_PER_COMMAND + 科技加成（B05 §二）
        long troopsInUse,   // 已占用的兵力。B05 第二步落地训练系统前恒为 0
        long serverNow)
{
}
