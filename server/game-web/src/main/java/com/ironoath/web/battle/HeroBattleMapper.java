package com.ironoath.web.battle;

import com.ironoath.battle.HeroSnapshot;
import com.ironoath.battle.SkillEffect;
import com.ironoath.battle.SkillPhase;
import com.ironoath.battle.SkillSnapshot;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.SkillCfg;
import com.ironoath.core.hero.HeroAttrs;
import com.ironoath.core.hero.HeroCalculator;
import com.ironoath.core.hero.HeroInstance;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.web.service.HeroStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：把 B06 的武将状态映射成战斗内核要的 {@link HeroSnapshot}（B09 战斗接线的武将侧）。
 * 依赖：game-config（hero / skill 表）、game-core 的武将域、game-battle 的值对象。
 *
 * <p><b>与 {@code BattleArmyFactory} 分开是刻意的</b>：那边是「混合阶级 → 每兵种一份属性」的折算，
 * 正确性靠一条数学性质（内核对「数量×属性」线性）就能证死；这边是三个系统的接缝
 * （武将属性 → 乘区、技能表 → 技能快照、技能等级 → 数值缩放），
 * 混在一个类里会让「折算是否正确」这个本该单独验证的问题与武将口径纠缠在一起。
 *
 * <p><b>本类修掉的一个真实缺陷</b>：B06 交付了 skillUp（消耗道具、有等级上限、面板显示等级），
 * 但技能等级<b>不进任何公式</b> —— 玩家花道具升级，收益精确为 0。
 * 这类 bug 不报错、不产生负数，只会在玩家社区里变成一句「技能升级是骗钱的」，
 * 而武将养成是深度付费线（B06 原话），信任丢了很难回来。
 * 现在技能数值按 SKILL_LEVEL_VALUE_STEP 随等级成长。
 *
 * <p><b>已知仍未接进内核的一项：智力 → 技能强度</b>。
 * {@code HeroCalculator.TeamBonus.skillFixed} 已经算出来了、面板也显示，
 * 但 {@code ArmySide} 没有对应的槽位（只有 per-hero 的攻击/防御乘区与独立的装备乘区）。
 * 这与 B06 已记录的 {@code equipSetDefFixed / equipSetSkillFixed} 是同一类缺口，
 * 补内核槽位时必须一起接上。<b>刻意不在这里用「把智力乘进技能数值」绕过</b>：
 * 那会让智力在两个地方生效（面板的 skillFixed 与战斗里的技能数值），
 * 而两处口径不可能一直一致 —— 缺槽位就如实缺着，比偷偷补一个错的强。
 */
@Component
public class HeroBattleMapper {

    private static final Logger LOG = LoggerFactory.getLogger(HeroBattleMapper.class);

    private final ConfigRegistry configs;
    private final HeroStatsService heroStats;

    public HeroBattleMapper(ConfigRegistry configs, HeroStatsService heroStats) {
        this.configs = configs;
        this.heroStats = heroStats;
    }

    /**
     * 映射一支队伍的上阵武将。
     *
     * @param heroIds 上阵武将 id，<b>顺序即站位</b>（0 号是主将）。空位用 null 或空串表示，会被跳过
     * @param roster  武将存档，用于取等级/星级/觉醒/技能等级
     * @return 与入参顺序一致的快照列表（跳过空位后 slot 仍保留原始下标）
     */
    public List<HeroSnapshot> snapshots(List<String> heroIds, HeroRoster roster) {
        if (roster == null) {
            throw new IllegalArgumentException("roster 不得为 null");
        }
        List<HeroSnapshot> out = new ArrayList<>();
        if (heroIds == null) {
            return out;
        }
        for (int slot = 0; slot < heroIds.size(); slot++) {
            String heroId = heroIds.get(slot);
            if (heroId == null || heroId.isBlank()) {
                continue;   // 副将位可以为空（B06 的 Lineup 允许 null 占位）
            }
            HeroInstance instance = roster.hero(heroId);
            HeroAttrs attrs = heroStats.finalAttrs(instance);
            HeroCfg cfg = heroStats.heroCfg(heroId);
            List<SkillSnapshot> skills = new ArrayList<>(2);
            addSkill(skills, cfg.mainSkill(), instance.mainSkillLevel(), heroId);
            addSkill(skills, cfg.subSkill(), instance.subSkillLevel(), heroId);
            out.add(new HeroSnapshot(heroId, slot,
                    HeroCalculator.attrToBonus(attrs.might(), heroStats.rules()),
                    HeroCalculator.attrToBonus(attrs.command(), heroStats.rules()),
                    skills));
        }
        return out;
    }

    /**
     * 一个技能的战斗快照。数值按等级缩放：{@code value × (1 + STEP × (level - 1))}。
     *
     * <p><b>缩放数值而不是触发概率</b>：缩放概率会让高等级技能的体验从「经常不触发」变成
     * 「几乎必触发」，战斗节奏随等级剧烈变化，而 B05 的回合数、持续回合、平局判定
     * 全是按固定节奏校准的。缩放数值是一条平滑的强度曲线，节奏不变，只是每次触发更疼。
     *
     * <p><b>也不缩放 durationRounds</b>：持续回合一旦超过 BATTLE_MAX_ROUNDS(8) 的一半，
     * 技能就覆盖整场战斗，等于把「概率触发」变成「常驻光环」，
     * 而 B05 的乘区设计假设了 buff 是临时的。
     */
    private void addSkill(List<SkillSnapshot> out, String skillId, int level, String heroId) {
        if (skillId == null || skillId.isBlank()) {
            return;
        }
        if (level < 1) {
            throw new IllegalStateException("武将 " + heroId + " 的技能 " + skillId
                    + " 等级必须 >= 1，实际=" + level);
        }
        SkillCfg cfg;
        try {
            cfg = configs.get(SkillCfg.class, skillId);
        } catch (ConfigException e) {
            // hero 表的 mainSkill/subSkill 是指向 skill 表的外键，走到这里说明两张表不同步。
            // 静默跳过会让这个武将「没有技能」，而玩家看得到技能图标 —— 必须当场炸
            throw new IllegalStateException("武将 " + heroId + " 的技能 " + skillId
                    + " 在 skill 表里不存在（hero 表与 skill 表不同步）", e);
        }
        long step = configs.fixedParam("SKILL_LEVEL_VALUE_STEP");
        long levelFactor = FixedPoint.add(FixedPoint.ONE,
                FixedPoint.mul(step, FixedPoint.of(level - 1L)));
        long value = FixedPoint.mul(cfg.value(), levelFactor);
        out.add(new SkillSnapshot(skillId,
                SkillPhase.valueOf(cfg.trigger().name()),
                cfg.chance(),
                SkillEffect.valueOf(cfg.effect().name()),
                value,
                (int) cfg.durationRounds()));
        if (LOG.isDebugEnabled()) {
            LOG.debug("武将技能映射 heroId={} skill={} 等级={} 数值 {} → {}",
                    heroId, skillId, level, cfg.value(), value);
        }
    }
}
