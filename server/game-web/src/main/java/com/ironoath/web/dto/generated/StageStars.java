// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 三星的三个条件分别是否达成。**逐条下发而不是只给一个总数**：玩家看到 2/3 时需要知道差的是哪一条，否则他只能反复试；而「差哪一条」正是驱动他去练兵、换阵型、升武将的信息。
 */
public record StageStars(
        boolean cleared,   // 第一星：通关
        boolean noLoss,   // 第二星：无损 —— 己方**阵亡**为 0（伤兵允许，伤兵可以治疗）。刻意不取「阵亡与伤兵都为 0」：这个内核里攻方损失约等于「敌方总兵力 / LANCHESTER_K」，与自己带多少兵无关（减员系数 = 1/(1+K×兵力比)，乘以己方兵力后收敛到敌方兵力/K），所以「一个都没少」在任何关卡都不可达 —— 一颗永远拿不到的星比一颗定义稍宽的星更糟，玩家会理解成数值造假。按 B05 的 PVE 死亡比例 0.20，第 1 关（敌方 10 兵）的期望损失约 1.4 个，其中阵亡 0.28 个 ⇒ 取整为 0，无损可达；而敌方兵力越多，无损就越难，门槛依然真实存在。
        boolean withinRounds,   // 第三星：在 stage 表的 roundLimit 回合内通关
        int total)   // 三条件之和。冗余下发是因为客户端要显示星级图标，让它自己数三个布尔值等于把口径交给客户端
{
}
