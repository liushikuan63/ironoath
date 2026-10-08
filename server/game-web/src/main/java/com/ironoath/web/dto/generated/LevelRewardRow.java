// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一个等级的领取视图。行数由 level_reward 表决定（现跑 40 行，1..40 逐级都有），服务端不做分页裁剪也不做筛选 —— 客户端拿到的是全表视图，分页是面板的事。
 */
public record LevelRewardRow(
        long level,   // 等级（表里的 level 列）。领取时原样回传它，而不是行 id —— 行 id 是表内主键，改表就会漂，而「第几级」这件事的本体就是这个数。
        String name,   // 这一级的展示名（表里的 name，如「主城 31 级奖励」）。客户端不得自行拼接等级文案。
        List<LevelRewardItem> rewards,   // 这一级给什么。由服务端按表逐列展开（0 的列不出现），顺序即客户端飘字顺序：木、石、金币。
        boolean locked,   // 主城等级还没到这一级。与 claimed 分开是为了让客户端知道该提示「先去升主城」还是「已领取」。
        boolean claimable,   // 此刻能不能领：等级已达到 且 未 claimed。客户端不要自己算这个布尔 —— 判据住在服务端，抄一份就会在口径变更时漂。
        boolean claimed)   // 这一级的奖励是否已领过。是「领取」按钮置灰的唯一依据（重复领取会被服务端拒并回业务码）。
{
}
