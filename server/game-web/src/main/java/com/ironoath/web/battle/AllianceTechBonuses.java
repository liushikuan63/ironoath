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
 * <p><b>只折算 UNIT_ATTACK 与 UNIT_DEFENSE 两个效果属性</b>，不是偷懒而是这张表目前的全部可接线范围：
 * 内核的乘区 B 就是一份攻击 + 一份防御，而另外六个属性
 * （MARCH_SPEED / LOAD_CAPACITY / HOSPITAL_CAPACITY / RALLY_CAPACITY / BUILD_SPEED / HELP_SPEED）
 * 各自要接的地方都<b>已经有一个数值来源在那里</b>，乘进去之前需要先定组合口径：
 * <ul>
 *   <li>{@code RALLY_CAPACITY} 与 {@code Alliance.LevelRule.rallyCapacity}（按联盟等级给的是一个
 *       绝对人数，不是百分比）怎么合？</li>
 *   <li>{@code HELP_SPEED} 与 {@code squad_config.helpSpeedBonus}（小队等级也给一份）是否叠加？</li>
 *   <li>{@code HOSPITAL_CAPACITY} 目前来自城里的医院建筑等级，是相加还是相乘？</li>
 *   <li>{@code MARCH_SPEED} / {@code BUILD_SPEED} 是缩短时长，除法的取整口径要与
 *       {@code MarchAppService}、{@code CityAppService} 现有的取整一致，否则会出现「差一秒」的争议。</li>
 * </ul>
 * 这些都属于平衡口径，未定之前这里不擅自接（接错了在战斗里看不出来，只体现在胜率上）。
 * 已记在收口清单 #31。
 *
 * <p><b>集结（多盟参战）时的加成按发起者的联盟算</b>：{@code ArmySide} 只有一份科技加成，
 * 而一支集结队伍可能来自多个联盟。取发起人是唯一不需要新发明状态的做法（集结本就由发起人建账），
 * 但它确实意味着队友的科技在这场战斗里不生效 —— 这条口径需要裁决，也记在 #31。
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
