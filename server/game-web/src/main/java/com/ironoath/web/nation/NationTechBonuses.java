package com.ironoath.web.nation;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NationTechCfg;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.web.social.SocialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 职责：把国家科技账本折成「某个属性现在合计多少万分比」——国家这一份的<b>唯一</b>读取口（B20 块③）。
 * 依赖：game-config（nation_tech 表）、{@link SocialStore} 与 {@link NationStore}（玩家 → 国家这一条通路）。
 *
 * <p><b>为什么与 {@code TechEffects} 是两个类而不是一个</b>：两本账记在不同的地方（个人的在玩家存档上、
 * 国家的在国家聚合里），而<b>解析"我是哪国人"这件事只有这一处做</b>。让 {@code TechEffects} 兼任就得
 * 让它依赖社交与国家的仓储 —— 而它现在是个纯折叠器，能在不起容器的情况下被单测逼到精确数
 * （{@code TechEffectsTest} 就靠这一点断 2000 万分比）。形状与 {@code AllianceTechBonuses} 一致。
 *
 * <p><b>只提供已经有消费点的那几个属性</b>（粮产 / 建造 / 训练 / 行军，正好是表里的四行）：
 * §五④ 定的是"同类加成相加成总率、作用于基础一次"，所以<b>合并点在消费点</b>（那里一句
 * {@code personal + nation}），不在这里 —— 与联盟那一份同一条分工。
 * 多做一个没人调的方法就是「有名字零调用点」的新一格，那正是 {@code check-config-consumers} 在防的形状。
 *
 * <p><b>没有国家的玩家是「无加成」而不是抛错</b>：打野、单排采集、未建国的新号都要走同一条产率与时长算式。
 * {@code AllianceTechBonuses.forPlayer} 早就立了这条先例，两处一致才不会一半场景炸、一半场景空。
 */
@Component
public class NationTechBonuses {

    private static final Logger LOG = LoggerFactory.getLogger(NationTechBonuses.class);

    private final ConfigRegistry configs;
    private final SocialStore social;
    private final NationStore nations;
    private final NationLeaders leaders;

    public NationTechBonuses(ConfigRegistry configs, SocialStore social,
                             NationStore nations, NationLeaders leaders) {
        this.configs = configs;
        this.social = social;
        this.nations = nations;
        this.leaders = leaders;
    }

    /**
     * 某种资源产量的国家加成（定点万分比）。
     *
     * <p>表里今天只有 {@code GRAIN_OUTPUT} 一行属于产量族，所以木/石/铁算出来是 0 —— 这里<b>不写特判</b>，
     * 将来给它们各配一行，本方法不用改（与 {@code TechEffects.outputPercent} 同一条取舍）。
     */
    public long outputPercent(String resourceId, String playerId) {
        return ResourceIds.GRAIN.equals(resourceId)
                ? percentFor(NationTechCfg.EffectAttr.GRAIN_OUTPUT, playerId) : 0L;
    }

    /** 建造速度（万分比）。与个人科技那一份<b>相加后</b>交给 {@code Rates.shortenSeconds} 作用一次。 */
    public long buildSpeedPercent(String playerId) {
        return percentFor(NationTechCfg.EffectAttr.BUILD_SPEED, playerId);
    }

    /** 训练速度（万分比）。作用点在<b>批次总时长</b>上（#157 那条：塞进单兵秒数会让快兵种一秒不减）。 */
    public long trainSpeedPercent(String playerId) {
        return percentFor(NationTechCfg.EffectAttr.TRAIN_SPEED, playerId);
    }

    /** 行军速度（万分比）。走「时长 ÷ (1 + 合计加速率)」，不改兵种自身速度（#158 那条）。 */
    public long marchSpeedPercent(String playerId) {
        return percentFor(NationTechCfg.EffectAttr.MARCH_SPEED, playerId);
    }

    /** Σ（该属性的每一行：每级幅度 × 国家账本里的等级）。没有国家就是 0。 */
    private long percentFor(NationTechCfg.EffectAttr attr, String playerId) {
        Nation nation = nationOf(playerId);
        if (nation == null || nation.techLevels().isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (NationTechCfg row : configs.all(NationTechCfg.class)) {
            if (row.effectAttr() != attr) {
                continue;
            }
            int level = nation.techLevel(row.id());
            if (level > 0) {
                // 表里的 effectValue 是「单级幅度」（定点），按等级线性累加 —— 与联盟/个人两份读法同一条，
                // 三处若各算一套，面板显示与实际生效就会漂移
                total += row.effectValue() * level;
            }
        }
        return total;
    }

    /**
     * 玩家所属国家；读不出来一律返回 null（不是抛）。
     *
     * <p>通路是「玩家 → 联盟 → 国家」（与 {@code NationAppService.requireNationOf} 同一条路），
     * 但那里是"必须有一个国家"的语义（抛 {@code NATION_NOT_FOUND}），这里是"没有就算了"的语义。
     * 两处共用一个方法的话，就会有一侧被迫 catch 一个它本来该回答给玩家的错误。
     *
     * <p>仓储异常也吞成 0：<b>响亮但不断路</b>。让它抛的后果是"国家仓储抖一下，
     * 全服的产率与城建时长算式跟着炸"，那比少一档加成严重得多；现场留在 WARN 日志里。
     */
    private Nation nationOf(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        try {
            return social.allianceOf(playerId)
                    .flatMap(alliance -> nations.findByAlliance(alliance.id()))
                    .map(leaders::bind)
                    .orElse(null);
        } catch (RuntimeException e) {
            LOG.warn("国家科技加成读不到所属国家，按 0 计 playerId={} 原因={}", playerId, e.toString());
            return null;
        }
    }
}
