package com.ironoath.web.tech;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.TechCfg;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.resource.ResourceIds;
import org.springframework.stereotype.Component;

/**
 * 职责：把「科技账本（哪一行几级）」折成「某个效果属性现在合计多少百分点」——科技加成的<b>唯一</b>读取口（B20 块①）。
 * 依赖：game-config（tech 表）+ game-core（{@link PlayerTech} 与资源 id 常量）。
 *
 * <p><b>为什么只有这一个入口</b>：一行的效果是「每级幅度 × 等级」，而"哪一行归哪个属性"这件事
 * 表里已经写了（{@code effectAttr} 列）。如果在每个消费点各写一遍 sum 循环，改一次表结构就要改四处，
 * 而漏掉的那一处不会报错 —— 只会<b>少算一种加成</b>。这与 {@code AllianceTechBonuses} 是同一条判断。
 *
 * <p><b>本类只提供已经有消费点的那几个属性</b>：产量、建造速度、攻防（乘区 B）、医院容量。
 * 训练速度、行军速度与负载还没有接入点（B20 验收 3 剩下的两格 + 行军那一条），
 * 先把它们做成公开方法就是「有名字零调用点」的新一格 —— 那正是本仓库反复在防的形状
 * （见 {@code check-config-consumers} 防的"表做了机制没做"，同一族）。消费点出现时在这里加一个方法即可。
 *
 * <p><b>返回的都是定点万分比（2000 = +20%）</b>：调用方拿到的是<b>增率</b>，不是倍率。
 * §五④ 裁定"同类加成相加成总加成率，再作用于基础值一次"，所以这里<b>不乘、不加 1.0、不取幂</b>，
 * 也不在这里合并来源（科技与联盟谁先谁后由消费点决定，因为"同类相加"这件事每个消费点只有一份算式）。
 */
@Component
public class TechEffects {

    private final ConfigRegistry configs;

    public TechEffects(ConfigRegistry configs) {
        this.configs = configs;
    }

    /**
     * 某种资源的基础产量加成（定点万分比）。来源是 {@code *_OUTPUT} 那一族属性。
     *
     * <p>体力与金币没有对应的 {@code *_OUTPUT} 行，所以它们天然是 0 —— 这里不写特判，
     * 因为特判等于给"以后加一行体力产量科技"这件事预先判死刑，而表允许它长出来。
     */
    public long outputPercent(String resourceId, PlayerTech tech) {
        TechCfg.EffectAttr attr = switch (resourceId) {
            case ResourceIds.WOOD -> TechCfg.EffectAttr.WOOD_OUTPUT;
            case ResourceIds.STONE -> TechCfg.EffectAttr.STONE_OUTPUT;
            case ResourceIds.IRON -> TechCfg.EffectAttr.IRON_OUTPUT;
            case ResourceIds.GRAIN -> TechCfg.EffectAttr.GRAIN_OUTPUT;
            default -> null;
        };
        return attr == null ? 0L : totalPercent(attr, tech);
    }

    /** 建造速度加成（定点万分比）。作用于城建时长的那一条算式在 {@code CityAppService}。 */
    public long buildSpeedPercent(PlayerTech tech) {
        return totalPercent(TechCfg.EffectAttr.BUILD_SPEED, tech);
    }

    /**
     * 训练速度加成（定点万分比）。由 {@code ArmyAppService} 传进 {@code ArmyState.train}，
     * 作用在<b>批次总时长</b>上（作用在单兵秒数上会让快兵种完全吃不到加成）。
     */
    public long trainSpeedPercent(PlayerTech tech) {
        return totalPercent(TechCfg.EffectAttr.TRAIN_SPEED, tech);
    }

    /**
     * 攻击加成（定点万分比）。与联盟科技的同名加成<b>相加</b>后进乘区 B，
     * 合并点在 {@code BattleTechBonuses}（§五④：同类相加成总加成率，作用一次）。
     */
    public long attackPercent(PlayerTech tech) {
        return totalPercent(TechCfg.EffectAttr.UNIT_ATTACK, tech);
    }

    /** 防御加成（定点万分比），与 {@link #attackPercent} 同一条口径。 */
    public long defensePercent(PlayerTech tech) {
        return totalPercent(TechCfg.EffectAttr.UNIT_DEFENSE, tech);
    }

    /** 医院容量加成（定点万分比）：放大的是医院建筑的<b>收容量</b>，由 {@code ArmyAppService} 使用。 */
    public long hospitalPercent(PlayerTech tech) {
        return totalPercent(TechCfg.EffectAttr.HOSPITAL_CAPACITY, tech);
    }

    /** Σ（该属性的每一行：每级幅度 × 当前等级）。没研究过的行按 0 级计（缺失即 0 由账本保证）。 */
    private long totalPercent(TechCfg.EffectAttr attr, PlayerTech tech) {
        if (tech == null) {
            return 0L;
        }
        long total = 0L;
        for (TechCfg row : configs.all(TechCfg.class)) {
            if (row.effectAttr() != attr) {
                continue;
            }
            int level = tech.levelOf(row.id());
            if (level > 0) {
                total += row.effectValue() * level;
            }
        }
        return total;
    }
}
