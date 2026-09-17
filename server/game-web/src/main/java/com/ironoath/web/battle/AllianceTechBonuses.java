package com.ironoath.web.battle;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ironoath.battle.TechBonus;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.AllianceTechCfg;
import com.ironoath.core.social.Alliance;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：把本盟已研究的联盟科技折成战斗内核的<b>乘区 B</b>（B05 §1.3「×(1 + 科技加成)」那一项）。
 * 依赖：{@link SocialStore}（联盟账本）、{@link ConfigRegistry}（alliance_tech 表）。
 *
 * <p><b>只折算 UNIT_ATTACK 与 UNIT_DEFENSE 两个属性</b>：乘区 B 在内核里就是一份攻击 + 一份防御，
 * 多出来的属性不属于它。另外四个属性的<b>消费点在别处</b>，各自接在自己的算式上，
 * 组合口径由 B20 §五④ 定了（<b>同类加成相加成总加成率、作用于基础值一次、缩短时长一律 ceil</b>）：
 * <ul>
 *   <li>{@code BUILD_SPEED} 已接：{@code CityAppService.shortenByPercent}（#155）；</li>
 *   <li>{@code HOSPITAL_CAPACITY} 已接：{@code ArmyAppService.hospitalCapacity}（#156）；</li>
 *   <li>{@code TRAIN_SPEED} / {@code MARCH_SPEED} / {@code LOAD_CAPACITY} 还没接上，
 *       缺的是消费点而不是裁决 —— 别再写"等口径"，那条已经定了（收口清单 #146 裁④）。</li>
 * </ul>
 * 个人科技与联盟科技的<b>相加</b>发生在 {@code BattleTechBonuses}（本类只管联盟那一份，
 * 语义不变，下面的既有用例也就仍在原位）。
 *
 * <p><b>{@code RALLY_CAPACITY} 与 {@code HELP_SPEED} 两行已在 2026-09-13 从表里退役</b>
 * （收口清单 §三·补 B10/B11，表 v1→v2）：它们撞的正是上面这条理由 —— 集结人数上限的唯一来源是
 * {@code Alliance.LevelRule.rallyCapacity}（绝对值），互助加速的上限已整体收归
 * {@code global.HELP_SPEEDUP_TOTAL_CAP}。枚举里两个取值保留（将来定出口可复用），
 * 但<b>表里没有行 = 玩家不可能再花钱研究一项永不生效的科技</b>。
 *
 * <p><b>集结（多盟参战）时的加成按发起者的联盟算</b>：{@code ArmySide} 只有一份科技加成，
 * 而一支集结队伍可能来自多个联盟。取发起人是唯一不需要新发明状态的做法（集结本就由发起人建账），
 * 但它确实意味着队友的科技在这场战斗里不生效 ——
 * <b>2026-09-13 裁决：保持这一口径</b>（收口清单 §三·补 A4）。逐人折算要把 {@code ArmySide} 改成
 * 单位级科技（一处结构改动，收益只落在集结战力的少数场景），留到真需要它的那一轮再做。
 */
@Component
public class AllianceTechBonuses {

    private final SocialStore social;
    private final ConfigRegistry configs;

    public AllianceTechBonuses(SocialStore social, ConfigRegistry configs) {
        this.social = social;
        this.configs = configs;
    }

    /** 某个玩家的攻击/防御科技加成。没有联盟就是「无加成」而不是抛错 —— 打野与 PVE 不该被联盟绑住。 */
    public TechBonus forPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return TechBonus.none();
        }
        return social.allianceOf(playerId).map(this::ofAlliance).orElseGet(TechBonus::none);
    }

    /** 把一个联盟的科技账本折成乘区 B。同类效果<b>相加</b>（表里攻击与防御各一行，不存在同类多行）。 */
    TechBonus ofAlliance(Alliance alliance) {
        List<AllianceTechCfg> table = configs.all(AllianceTechCfg.class);
        long attack = 0L;
        long defense = 0L;
        for (AllianceTechCfg tech : table) {
            int level = alliance.techLevel(tech.id());
            if (level <= 0) {
                continue;
            }
            // 表里的 effectValue 是「单级幅度」（定点），所以按等级线性累加，与 Alliance#researchTech
            // 回给客户端的 effectValue 口径一致 —— 两处若各算一套，面板显示与实际生效就会漂移
            long total = tech.effectValue() * level;
            if (tech.effectAttr() == AllianceTechCfg.EffectAttr.UNIT_ATTACK) {
                attack += total;
            } else if (tech.effectAttr() == AllianceTechCfg.EffectAttr.UNIT_DEFENSE) {
                defense += total;
            }
        }
        return new TechBonus(attack, defense);
    }
}
