package com.ironoath.web.nation;

import com.ironoath.battle.OrgBonus;
import com.ironoath.battle.UnitType;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NationPolicyCfg;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.nation.Nation;
import com.ironoath.web.social.SocialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：把国家<b>当前生效</b>的国策折成这一方拿到的加成 —— 乘区 G 与乘区 H 的<b>唯一生产方</b>
 * （B13 §4 / B21 块③，2026-09-30）。
 * 依赖：game-config（{@code nation_policy} / {@code unit} 两张表）、{@link NationStore}、
 * {@link SocialStore}、{@link NationLeaders}。
 *
 * <p><b>形状与 {@code NationTechBonuses} 一致，理由也一致</b>：两本账记在不同的地方
 * （个人科技在玩家存档上、国家科技在国家聚合里），而「解析我是哪国人」这件事只有一处做。
 * 那个类把合并点放在<b>消费点</b>（那里一句 {@code personal + nation}），不在读取口 ——
 * 与联盟那一份同一条分工。国策也一样：这里只给「国家这一份是多少」，
 * 同类相加由消费点做（收口清单 #146 ②）。
 *
 * <p><b>本类只提供已经有消费点的四个属性</b>，与 {@code nation_policy.json} 的
 * {@code effectAttr} 四个取值一一对应：{@code POLICY_ATTACK} / {@code POLICY_DEFENSE} /
 * {@code OUTPUT} / {@code MARCH_SPEED}。多做一个没人调的方法就是「有名字零调用点」的新一格，
 * 那正是 {@code check-config-consumers} 在防的形状。
 *
 * <p><b>没有国家的玩家是「无加成」而不是抛错</b>：打野、单排采集、未建国的新号都要走同一条
 * 产率与时长算式，{@code NationTechBonuses} 早就立了这条先例（仓储异常也吞成 0 并打 WARN：
 * 国家仓储抖一下就让全服产率算式跟着炸，比少一档加成严重得多）。
 */
@Component
public class NationPolicyBonuses {

    private static final Logger LOG = LoggerFactory.getLogger(NationPolicyBonuses.class);

    private final ConfigRegistry configs;
    private final SocialStore social;
    private final NationStore nations;
    private final NationLeaders leaders;

    public NationPolicyBonuses(ConfigRegistry configs, SocialStore social,
                               NationStore nations, NationLeaders leaders) {
        this.configs = configs;
        this.social = social;
        this.nations = nations;
        this.leaders = leaders;
    }

    /**
     * 这个玩家此刻拿到的战斗侧组织加成（乘区 G 攻击 + 乘区 G 防御 + 乘区 H 城墙）。
     *
     * <p><b>城墙那一项目前恒为 0</b>：城墙等级 → 战斗加成的幅度至今没有任何出处
     * （{@code building.json} 的城墙行只有 {@code powerBase=7}，{@code global.json} 无城墙参数），
     * 按本仓「待裁决不发明」的红线不编数。乘区 H 的通路已在内核交付，等策划给数后
     * 在这里补一行即可（那一格是 {@code CITY_WALL_DEFENSE_PER_LEVEL} 之类的参数）。
     *
     * <p><b>按兵种累加而不是压成一个标量</b>：{@code np_cavalry_t1..t5} 五行各作用于轻骑兵，
     * 压成一个数会让 +15% × 5 = +75% 打给<b>所有</b>兵种。这条在 A 格改形状时踩过一次。
     */
    public OrgBonus combatBonusFor(String playerId) {
        Nation nation = nationOf(playerId);
        if (nation == null) {
            return OrgBonus.none();
        }
        List<NationPolicyCfg> active = activeRows(nation);
        if (active.isEmpty()) {
            return OrgBonus.none();
        }
        Map<UnitType, Long> attackByUnit = new EnumMap<>(UnitType.class);
        long defense = 0L;
        for (NationPolicyCfg row : active) {
            switch (row.effectAttr()) {
                case POLICY_ATTACK -> {
                    UnitType type = unitTypeOf(row.targetUnit());
                    if (type != null) {
                        attackByUnit.merge(type, row.effectValue(), Long::sum);
                    }
                }
                case POLICY_DEFENSE -> defense += row.effectValue();
                case OUTPUT, MARCH_SPEED -> {
                    // 战斗侧不消费这两项：它们分别进产量算式与行军时长算式，见下面两个方法。
                    // 刻意不写 default 分支抛错 —— 表里将来加第五个取值时，漏接的表现应该是
                    // 「那一项没生效」而不是「整个国家的国策全废」。
                }
            }
        }
        return new OrgBonus(attackByUnit, defense, 0L);
    }

    /** 四种资源的国家加成（定点万分比）。国策里 {@code OUTPUT} 一行管四资源，同一个幅度。 */
    public long outputPercent(String playerId) {
        Nation nation = nationOf(playerId);
        if (nation == null) {
            return 0L;
        }
        long total = 0L;
        for (NationPolicyCfg row : activeRows(nation)) {
            if (row.effectAttr() == NationPolicyCfg.EffectAttr.OUTPUT) {
                total += row.effectValue();
            }
        }
        return total;
    }

    /**
     * 行军速度（定点万分比）。与个人科技、国家科技<b>同类相加</b>后交给
     * {@code Rates.shortenSeconds} 作用一次（收口清单 #146 ②）。
     */
    public long marchSpeedPercent(String playerId) {
        Nation nation = nationOf(playerId);
        if (nation == null) {
            return 0L;
        }
        long total = 0L;
        for (NationPolicyCfg row : activeRows(nation)) {
            if (row.effectAttr() == NationPolicyCfg.EffectAttr.MARCH_SPEED) {
                total += row.effectValue();
            }
        }
        return total;
    }

    /**
     * 本国当前生效的国策表行（按到期时刻升序）。
     *
     * <p><b>表里被删掉的行跳过而不是崩</b>：配置表热更会删行，而存档里可能还留着上一轮生效的
     * 那一行。让整个战斗装配炸掉的症状是「策划删了一行国策，全服的战斗都打不了」——
     * 而那一行本来只是失效。同一条宽容读法也写在 {@code Nation.fromSnapshot} 的注释里。
     */
    private List<NationPolicyCfg> activeRows(Nation nation) {
        long now = System.currentTimeMillis();
        List<NationPolicyCfg> out = new ArrayList<>();
        for (Nation.ActivePolicy active : nation.activePolicies(now)) {
            if (!configs.rawTable("nation_policy").has(active.policyId())) {
                LOG.warn("国策 {} 仍在生效但 nation_policy 表里已经没有这一行，按无加成处理", active.policyId());
                continue;
            }
            out.add(configs.get(NationPolicyCfg.class, active.policyId()));
        }
        return out;
    }

    /** {@code targetUnit}（{@code unit_cavalry_t1} 这类 id）→ 兵种枚举。读不到返回 null。 */
    private UnitType unitTypeOf(String unitId) {
        if (unitId == null || unitId.isBlank()) {
            return null;
        }
        if (!configs.rawTable("unit").has(unitId)) {
            return null;
        }
        UnitCfg row = configs.get(UnitCfg.class, unitId);
        for (UnitType type : UnitType.values()) {
            if (type.name().equals(row.type())) {
                return type;
            }
        }
        return null;
    }

    /**
     * 玩家所属国家；读不出来一律返回 null（不是抛）。
     *
     * <p>通路是「玩家 → 联盟 → 国家」（与 {@code NationTechBonuses} 同一条路），
     * 但那里是「必须有一个国家」的语义，这里是「没有就算了」的语义。
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
            LOG.warn("国策加成读不到所属国家，按 0 计 playerId={} 原因={}", playerId, e.toString());
            return null;
        }
    }
}
