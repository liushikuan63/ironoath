// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 加速结果。三位各说各的：真扣了多少秒、还剩多少、是否因此研究完。
 */
public record TechSpeedUpResp(
        String techId,   // 被加速的那一行（一次一队列，所以就是队列里那一项）。
        long reducedSeconds,   // <b>实际</b>提前的秒数：`min(count × 单张效果, 剩余)`。用完一张 8 小时令去加速只剩 10 秒的研究时，reduced 是 10 而不是 28800 —— 面板若显示 28800，玩家就看到了一次凭空的损失。道具仍按张数扣（多出的部分不退还，与城建加速同一口径：买得多是用在别的队列上，不是退款理由）。
        long remainingSeconds,   // 加速后的剩余秒数，绝不为负。
        boolean finished)   // 这一级是否因此完成。<b>为 true 时等级已经在同一把锁内结算进账本</b>（不是「等下次读取再说」）：玩家花道具买的就是「现在就完成」，把它留给下一次惰性读取会让响应与实际状态不一致。
{
}
