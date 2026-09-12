// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一条任务的当前视图。**进度由事件推动累加**（B12 禁止项：任务进度不得轮询），所以这里的 current 是账本里的值，不是每次读的时候扫出来的。
 */
public record QuestView(
        String questId,   // quest 表的行 id。领取奖励时原样回传。
        String name,   // 任务名（表里的 name）。客户端不得自行翻译或拼接进度文案。
        QuestType type,
        GoalType goalType,
        String goalTarget,   // 目标细分（建筑 id / 资源 id / 兵种 id / 野怪 id / 卡池 id）。null = 不限定（任意建筑升级都算）。
        long goalValue,   // 目标值。恒 >= 1：为 0 的任务一创建就是完成态（领域层在构造期就拒绝）。
        long current,   // 当前进度。累加型只增不减；状态型是最近一次事件的快照值，可以回落（花掉粮食就退回去）。
        boolean complete,   // current >= goalValue。**它与 claimable 不同**：完成但没领、完成且领过、以及前置没做完，是三种不同状态。
        boolean claimed,   // 奖励是否已领。重复领取会被服务端拒（幂等），所以这个字段是客户端按钮置灰的唯一依据。
        boolean claimable,   // 此刻能不能领：complete 且未 claimed 且前置已完成。客户端不要自己算这个布尔（前置链一变就会漂）。
        boolean locked,   // 前置任务未完成 ⇒ 还不能做。与 claimed 分开是为了让客户端知道该提示「先完成前置」还是「已领取」。
        String preQuestId,   // 前置任务 id，null 表示无前置。下发它是因为客户端要能画出任务链（「完成 X 后解锁」）。
        List<HeroChoice> heroChoices)   // 本条任务的可选武将列表（含名字）；空数组表示这条任务的奖励里没有「挑一名」这一项。客户端据此在领奖前弹出选择界面，选择结果随 claim 的 heroChoice 提交。
{
}
