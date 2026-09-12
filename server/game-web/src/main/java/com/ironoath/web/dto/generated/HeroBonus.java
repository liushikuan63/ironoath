// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 队伍的武将侧加成，按乘区拆分返回（B06 §2）。<b>与 B06 文档草案的差异（有意偏离，理由如下）</b>：草案写的是 atkMultiplier/defMultiplier/hpMultiplier，但 B05 已交付的战斗内核只有两个武将侧乘区（HeroSnapshot.heroBonusFixed 攻击、defBonusFixed 防御），没有独立的生命乘区 —— B05 把生命折进了有效防御（HP_DEFENSE_WEIGHT）。所以这里返回 atkFixed/defFixed/skillFixed：武力→攻击乘区、统率→防御乘区、智力→技能强度。硬造一个内核消费不了的 hpMultiplier 只会让面板显示一个不影响战斗的数字。
 */
public record HeroBonus(
        long atkFixed,   // 攻击乘区加成（定点）。进 BattleSnapshot 的 heroBonusFixed
        long defFixed,   // 防御乘区加成（定点）。进 defBonusFixed
        long skillFixed,   // 技能强度加成（定点），由智力折算
        long commandValue,   // 队伍统帅值合计（主将 100% + 副将各 50%），决定带兵上限
        boolean capped,   // 是否触到了 HERO_ZONE_CAP 上限。为 true 说明继续堆养成不会再变强，UI 应提示玩家
        List<BonusBreak> breakdown)
{
}
