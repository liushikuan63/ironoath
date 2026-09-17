package com.ironoath.web.hero;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.EquipCfg;
import com.ironoath.core.bag.Inventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 职责：把「一个 uid 现在是哪件装备、强化到几级、于是给多少属性」一次解析好，供武将属性算式读。
 * 依赖：game-core 的 {@link Inventory}（实例账本在这里）、game-config（查装备行）。
 *
 * <p><b>为什么要有这么一份，而不是让 {@code HeroStatsService} 自己去读背包</b>：
 * 一次属性计算会穿过四个武将 × 四个槽位，读背包这件事如果散在算式里，
 * 同一次请求就会付十几次存储读的代价，而且「读不到怎么办」的答案会在每一处各写一遍。
 * 收成一份快照之后：读一次、判一次、传给需要它的算式，调用处再也不会自己查存储。
 *
 * <p><b>强化等级在这里生效，且只在这里生效</b>：
 * 每级 = 该行三维各 +{@code global.EQUIP_FORGE_ATTR_GAIN}（5%），乘算全程走定点，**不在这里落地成整数** —— 取整只发生一次，位置见 HeroStatsService.equipFlat。
 * 取整放在最后而不是每维各取一次，是为了让「+3 的三围装」不会因为三次独立舍入而少给一截；
 * 向下（而不是四舍五入）是因为属性会进战力，向上取整等于凭空多给。
 *
 * <p><b>三条解析路径，行为各不相同，这是本类唯一一处必须小心的地方</b>：
 * <ol>
 *   <li>uid 在账本里 ⇒ 正常解析（含强化等级）；</li>
 *   <li>值<b>不是</b> uid 形状 ⇒ 它是一条<b>老存档</b>里直接写装备行 id 的槽位值 ——
 *       按 +0 解析，<b>绝不返回 null</b>。§五⑤ 那句「绝不能读成没穿装备」说的就是这条路径：
 *       一次登录前的读取如果返回 null，玩家的武将就会在战力榜上掉一截，
 *       而这份档只要被任何一次落库覆盖，那件装备就真的没了；</li>
 *   <li>值是 uid 形状但账本里没有 ⇒ 悬空引用，返回 null 并打 ERROR。
 *       这是本系统自己写不出来的状态，出现即说明有 bug 或有脏数据，静默当成空槽会把那次 bug 埋掉。</li>
 * </ol>
 *
 * <p>不是线程安全的：一份快照只服务一次请求内的那几次计算。
 */
public final class EquipLedger {

    private static final Logger LOG = LoggerFactory.getLogger(EquipLedger.class);

    /**
     * 一件装备此刻的属性，<b>定点万分比</b>（12 点 = 120000）。
     *
     * <p>刻意不落成整数：一件 12 点武力的铁剑 +1 是 12.6，在这里取整就变成 12 ——
     * 玩家花 840 铁买到的答案是"一点没动"，而四件各舍一次还会凭空少给近 2 点。
     *
     * @param equipId    配置行 id（套装件数统计要按它查 {@code setId}）
     * @param forgeLevel 强化等级，老存档路径恒为 0
     */
    public record Resolved(String equipId, int forgeLevel,
                           long mightFixed, long commandFixed, long wisdomFixed) {
    }

    private final Map<String, Resolved> byUid;
    private final ConfigRegistry configs;
    private final long gainPerLevelFixed;

    private EquipLedger(Map<String, Resolved> byUid, ConfigRegistry configs, long gainPerLevelFixed) {
        this.byUid = byUid;
        this.configs = configs;
        this.gainPerLevelFixed = gainPerLevelFixed;
    }

    /** 空账本（新号还没有实例）。老存档里的行 id 仍然能解析，所以这不是"什么都读不到"。 */
    public static EquipLedger empty(ConfigRegistry configs) {
        return of(null, configs);
    }

    /**
     * 从背包解析一份快照。
     *
     * @param bag 玩家的背包；null 视为没有实例
     */
    public static EquipLedger of(Inventory bag, ConfigRegistry configs) {
        long gain = configs.fixedParam("EQUIP_FORGE_ATTR_GAIN");
        Map<String, Resolved> byUid = new HashMap<>();
        if (bag != null) {
            for (Inventory.EquipInstance instance : bag.equipInstances()) {
                Resolved resolved = resolveRow(instance.equipId(), instance.forgeLevel(), configs, gain);
                if (resolved == null) {
                    // 账本里有一件表里查不到的装备：表被删过或档被改过。跳过而不是抛 ——
                    // 抛在这里等于让这个玩家登录不了，而他能做的只有等修表
                    LOG.error("【装备实例指向一个不存在的行】uid={} equipId={} 按没有这一件处理",
                            instance.uid(), instance.equipId());
                    continue;
                }
                byUid.put(instance.uid(), resolved);
            }
        }
        return new EquipLedger(byUid, configs, gain);
    }

    /**
     * 解析槽位里的值（uid，或老存档的行 id）。返回 null 只有一种含义：这是一个悬空的 uid，
     * 见类注释的三条路径。
     */
    public Resolved resolve(String slotValue) {
        if (slotValue == null || slotValue.isBlank()) {
            return null;
        }
        Resolved hit = byUid.get(slotValue);
        if (hit != null) {
            return hit;
        }
        if (Inventory.isInstanceUid(slotValue)) {
            LOG.error("【武将槽位指向一个不存在的装备实例】uid={} 该槽位按空槽计入属性，"
                    + "但这是脏数据：玩家会看到装备图标却不涨属性，需要查是哪条链写坏了 uid", slotValue);
            return null;
        }
        // 老存档：槽位里直接写着装备行 id。按 +0 解析，绝不读成"没穿装备"（§五⑤）
        Resolved legacy = resolveRow(slotValue, 0, configs, gainPerLevelFixed);
        if (legacy == null) {
            LOG.error("【武将槽位里的既不是 uid 也不是装备行 id】value={} 该槽位按空槽处理", slotValue);
        }
        return legacy;
    }

    /** 已解析出的实例数（= 账本条数，不含老存档路径）。给"这玩家有几件装备"这类展示用。 */
    public int size() {
        return byUid.size();
    }

    /** 行 id + 等级 → 此刻属性。行不存在返回 null，由调用方决定怎么响亮地失败。 */
    private static Resolved resolveRow(String equipId, int forgeLevel, ConfigRegistry configs,
                                       long gainPerLevelFixed) {
        EquipCfg row;
        try {
            row = configs.get(EquipCfg.class, equipId);
        } catch (ConfigException e) {
            return null;
        }
        if (forgeLevel == 0) {
            return new Resolved(equipId, 0, FixedPoint.of(row.might()),
                    FixedPoint.of(row.command()), FixedPoint.of(row.wisdom()));
        }
        // 1 + 5% × 等级：先加一，再乘等级数，全程定点
        long factor = FixedPoint.ONE + FixedPoint.mul(
                FixedPoint.of((long) forgeLevel), gainPerLevelFixed);
        return new Resolved(equipId, forgeLevel,
                FixedPoint.mul(FixedPoint.of(row.might()), factor),
                FixedPoint.mul(FixedPoint.of(row.command()), factor),
                FixedPoint.mul(FixedPoint.of(row.wisdom()), factor));
    }
}
