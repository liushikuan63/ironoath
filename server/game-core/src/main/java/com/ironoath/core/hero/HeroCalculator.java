package com.ironoath.core.hero;

import com.ironoath.common.num.FixedPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：武将侧的全部数值计算 —— 养成后属性、战力、队伍乘区加成、带兵上限（B06 §2/§4/§5）。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>三条口径，写清楚是因为它们最容易被后来人「顺手统一」掉</b>：
 *
 * <ol>
 *   <li><b>养成后属性</b> = 基础三维 × 成长率 × 等级因子 × 星级因子 × 觉醒因子 + 装备固定值。
 *       <br>装备固定值刻意<b>不参与</b>前面那串乘法：若参与，同一件装备在满级武将身上的价值
 *       是 1 级的 5 倍，玩家会把所有好装备堆在主力身上，其余武将永远拿不到装备，
 *       装备投放就退化成一次性。让它不被养成放大，装备才是一个可以横向分配的独立维度。</li>
 *   <li><b>战力</b> = (基础三维之和 × 成长率) 套 curve.HERO_GROWTH 的幂律 × 星级 × 觉醒 + 装备固定值之和。
 *       <br>战力走陡峭的幂律（100 级 = 251 倍）而战斗属性走线性（100 级 = 2.98 倍），
 *       是因为两者服务不同目的：战力要让玩家「感到变强了很多」并驱动 B08 的圈层匹配，
 *       战斗属性要换算成乘区百分比，必须有界（见 HeroRules 类注释）。</li>
 *   <li><b>稀有度比值必须与养成进度无关</b>（B06 验收 7：SSR/SR ∈ [1.5,1.7]，禁止项：不得超过 2 倍）。
 *       这要求等级/星级/觉醒三个因子<b>与稀有度无关</b> —— 它们是全体武将共享的曲线。
 *       稀有度差异只来自「基础三维 × 各自的成长率」。
 *       若让等级因子含稀有度（例如按各自的 growthRate 当指数），
 *       100 级时 SSR/SR 会到 2.32 倍，直接踩穿生态红线。</li>
 * </ol>
 */
public final class HeroCalculator {

    private HeroCalculator() {
    }

    /**
     * 养成后的三维属性。
     *
     * @param base            hero 表的三维原值
     * @param growthRateFixed hero 表的 growthRate（定点），逐稀有度不同：SSR 1.20 / SR 1.10 / R 1.09 / N 1.00
     * @param equipFlat       四件装备的固定值之和（加算，不被养成因子放大）
     */
    public static HeroAttrs finalAttrs(HeroAttrs base, long growthRateFixed,
                                       int level, int star, int awaken,
                                       HeroAttrs equipFlat, HeroRules rules) {
        require(base, growthRateFixed, rules);
        long factor = growthFactor(level, star, awaken, rules);
        return HeroAttrs.of(
                grow(base.might(), growthRateFixed, factor) + equipFlat.might(),
                grow(base.command(), growthRateFixed, factor) + equipFlat.command(),
                grow(base.wisdom(), growthRateFixed, factor) + equipFlat.wisdom());
    }

    /**
     * 武将战力。
     *
     * <p>只用于展示与 B08 圈层匹配，<b>不进战斗公式</b> —— 战斗用的是
     * {@link #teamBonus} 算出的乘区加成。两个数字服务两个目的，不要互相替代。
     */
    public static long power(HeroAttrs base, long growthRateFixed,
                             int level, int star, int awaken,
                             HeroAttrs equipFlat, HeroRules rules) {
        require(base, growthRateFixed, rules);
        long scaledTotal = FixedPoint.round(
                FixedPoint.mul(FixedPoint.of(base.total()), growthRateFixed));
        long grown = FixedPoint.round(FixedPoint.powerLaw(
                FixedPoint.of(scaledTotal), level, rules.powerExponentFixed()));
        long withStarAwaken = FixedPoint.round(FixedPoint.mul(
                FixedPoint.mul(FixedPoint.of(grown), rules.starFactor(star)),
                rules.awakenFactor(awaken)));
        return withStarAwaken + equipFlat.total();
    }

    /** 等级 × 星级 × 觉醒三个因子的合成。三次定点乘法，只在最后落到属性时取整一次。 */
    private static long growthFactor(int level, int star, int awaken, HeroRules rules) {
        return FixedPoint.mul(
                FixedPoint.mul(rules.levelFactor(level), rules.starFactor(star)),
                rules.awakenFactor(awaken));
    }

    /** 单维成长：基础 × 成长率 × 合成因子，最后取整一次。 */
    private static long grow(long base, long growthRateFixed, long factor) {
        if (base == 0L) {
            return 0L;
        }
        return FixedPoint.round(FixedPoint.mul(
                FixedPoint.mul(FixedPoint.of(base), growthRateFixed), factor));
    }

    /**
     * 属性 → 战斗加成（定点百分比）。
     *
     * <p>换算口径见 {@link HeroRules#attrToBonusFixed}：每 attrPerPercent 点属性 = +1%。
     */
    public static long attrToBonus(long attr, HeroRules rules) {
        return rules.attrToBonusFixed(attr);
    }

    /**
     * 带兵上限 = 队伍统帅值 × TROOP_PER_COMMAND（B05 §二、B06 验收 8）。
     *
     * @param commandValue 队伍统帅值合计（主将 100% + 副将各 subBonusRatio）
     */
    public static long troopCap(long commandValue, HeroRules rules) {
        if (commandValue < 0L) {
            throw new IllegalArgumentException("统帅值不得为负：" + commandValue);
        }
        // 溢出保护：统帅值 × 每点带兵数在极端养成下可能超出 long，
        // 但那种情况本身说明配置错了，宁可截断到 Long.MAX_VALUE 也不要绕回成负数
        long cap = commandValue * rules.troopPerCommand();
        if (commandValue != 0L && cap / commandValue != rules.troopPerCommand()) {
            return Long.MAX_VALUE;
        }
        return cap;
    }

    private static void require(HeroAttrs base, long growthRateFixed, HeroRules rules) {
        if (base == null) {
            throw new IllegalArgumentException("base 不得为 null");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (growthRateFixed < FixedPoint.ONE) {
            // 成长率低于 1.0 意味着「养成让武将变弱」，那一定是配置填错了小数点
            throw new IllegalArgumentException("成长率不得低于 1.0（定点 " + FixedPoint.ONE
                    + "），实际=" + growthRateFixed + "。低于 1 说明配置里可能把 1.20 填成了 0.012");
        }
    }

    /**
     * 队伍加成明细的一行。
     *
     * @param zone 落在哪个乘区（B06 硬约束 1：配置表里要明确标注每个加成落在哪个乘区）。
     *             HERO 与 BOND 都进 B05 的乘区 A，但在明细里分行 —— 玩家必须能看出
     *             「这 8% 是缘分给的」，否则缘分的收集动力就不成立（B06 §3）
     */
    public record Break(String source, long valueFixed, Zone zone) {

        public Break {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("明细行的 source 不得为空");
            }
            if (valueFixed < 0L) {
                throw new IllegalArgumentException("加成不得为负：source=" + source
                        + ", value=" + valueFixed);
            }
            if (zone == null) {
                throw new IllegalArgumentException("明细行必须标明乘区：source=" + source);
            }
        }
    }

    /** 乘区归属。与协议里的 BonusZone 枚举一一对应（由 ContractEnumParityTest 断言）。 */
    public enum Zone {
        /** 武将本体属性（等级/星级/觉醒/装备固定值共同作用后的结果）。 */
        HERO,
        /** 缘分子乘区（B06 §3：走乘区 A 的独立子乘区）。 */
        BOND,
        /** 装备套装百分比（B05 §1.3 的独立乘区，与武将乘区隔离）。 */
        EQUIP_SET
    }

    /**
     * 队伍的武将侧加成，按乘区拆开。
     *
     * @param atkFixed   攻击乘区加成（定点），对应 B05 的 {@code HeroSnapshot.heroBonusFixed}
     * @param defFixed   防御乘区加成，对应 {@code HeroSnapshot.defBonusFixed}
     * @param skillFixed 技能强度加成（由智力折算）
     * @param equipSetAtkFixed 装备套装对攻击乘区的贡献。<b>单独返回</b>是因为 B05 的
     *                   {@code ArmySide.equipBonusFixed} 是与武将乘区隔离的独立乘区，
     *                   合进 atkFixed 就违反了 B06 禁止项「不要让武将加成污染其他乘区」。
     *                   接内核时直接喂给 {@code ArmySide.equipBonusFixed}
     * @param equipSetDefFixed 套装对防御方向的贡献
     * @param equipSetSkillFixed 套装对技能强度方向的贡献。
     *                   TODO(B16): B05 内核的攻击侧有独立的装备乘区槽位，
     *                   防御侧与技能侧没有（只有 per-hero 的 defBonusFixed）。
     *                   所以这两个值目前只在明细与响应里可见、可单独调参，
     *                   还没有真正进战斗公式 —— 补内核槽位时必须接上，
     *                   否则玄武套与文曲集会变成「面板上有、战斗里没用」的装饰
     * @param commandValue 队伍统帅值合计
     * @param capped     武将乘区是否触到了 HERO_ZONE_CAP 上限
     * @param breakdown  明细
     */
    public record TeamBonus(long atkFixed,
                            long defFixed,
                            long skillFixed,
                            long equipSetAtkFixed,
                            long equipSetDefFixed,
                            long equipSetSkillFixed,
                            long commandValue,
                            boolean capped,
                            List<Break> breakdown) {

        public TeamBonus {
            requireNonNegative(atkFixed, "atkFixed");
            requireNonNegative(defFixed, "defFixed");
            requireNonNegative(skillFixed, "skillFixed");
            requireNonNegative(equipSetAtkFixed, "equipSetAtkFixed");
            requireNonNegative(equipSetDefFixed, "equipSetDefFixed");
            requireNonNegative(equipSetSkillFixed, "equipSetSkillFixed");
            requireNonNegative(commandValue, "commandValue");
            breakdown = List.copyOf(breakdown);
        }

        private static void requireNonNegative(long value, String field) {
            if (value < 0L) {
                throw new IllegalArgumentException(field + " 不得为负：" + value);
            }
        }
    }

    /**
     * 计算一支队伍的加成。
     *
     * @param main        主将的养成后属性；null 表示主将位空着
     * @param subs        副将的养成后属性，按位置顺序；元素可为 null 表示该位空着
     * @param equipSetAtk 装备套装给攻击乘区的百分比合计（定点）
     * @param equipSetDef 装备套装给防御乘区的百分比合计（定点）
     * @param bonds       已激活的缘分条数（成对同队才算，由 {@link HeroRoster} 判定）
     */
    public static TeamBonus teamBonus(HeroAttrs main, List<HeroAttrs> subs,
                                      long equipSetAtk, long equipSetDef, long equipSetSkill,
                                      int bonds, HeroRules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (subs == null) {
            throw new IllegalArgumentException("subs 不得为 null（没有副将请传空列表）");
        }
        if (bonds < 0) {
            throw new IllegalArgumentException("激活的缘分条数不得为负：" + bonds);
        }
        requireNonNegativeFixed(equipSetAtk, "equipSetAtk");
        requireNonNegativeFixed(equipSetDef, "equipSetDef");
        requireNonNegativeFixed(equipSetSkill, "equipSetSkill");

        List<Break> breakdown = new ArrayList<>();
        long atk = 0L;
        long def = 0L;
        long skill = 0L;
        long command = 0L;

        if (main != null) {
            atk += rules.attrToBonusFixed(main.might());
            def += rules.attrToBonusFixed(main.command());
            skill += rules.attrToBonusFixed(main.wisdom());
            command += main.command();
            breakdown.add(new Break("主将 武力 " + main.might(), rules.attrToBonusFixed(main.might()), Zone.HERO));
            breakdown.add(new Break("主将 统率 " + main.command(), rules.attrToBonusFixed(main.command()), Zone.HERO));
            breakdown.add(new Break("主将 智力 " + main.wisdom(), rules.attrToBonusFixed(main.wisdom()), Zone.HERO));
        }
        for (int i = 0; i < subs.size(); i++) {
            HeroAttrs sub = subs.get(i);
            if (sub == null) {
                continue;
            }
            // 副将只按 subBonusRatio 计入（B06 §4：主将位本身更强，属性维度也要体现出来，
            // 否则「谁当主将」就只由技能决定，编队失去权衡）
            long subAtk = scaled(rules.attrToBonusFixed(sub.might()), rules.subBonusRatioFixed());
            long subDef = scaled(rules.attrToBonusFixed(sub.command()), rules.subBonusRatioFixed());
            long subSkill = scaled(rules.attrToBonusFixed(sub.wisdom()), rules.subBonusRatioFixed());
            long subCommand = scaled(sub.command(), rules.subBonusRatioFixed());
            atk += subAtk;
            def += subDef;
            skill += subSkill;
            command += subCommand;
            breakdown.add(new Break("副将" + (i + 1) + " 武力 " + sub.might(), subAtk, Zone.HERO));
            breakdown.add(new Break("副将" + (i + 1) + " 统率 " + sub.command(), subDef, Zone.HERO));
            breakdown.add(new Break("副将" + (i + 1) + " 智力 " + sub.wisdom(), subSkill, Zone.HERO));
        }
        if (bonds > 0) {
            long bondBonus = FixedPoint.mul(FixedPoint.of(bonds), rules.bondBonusFixed());
            atk += bondBonus;
            def += bondBonus;
            breakdown.add(new Break("缘分 ×" + bonds, bondBonus, Zone.BOND));
        }
        if (equipSetAtk > 0L || equipSetDef > 0L || equipSetSkill > 0L) {
            breakdown.add(new Break("装备套装（攻击）", equipSetAtk, Zone.EQUIP_SET));
            breakdown.add(new Break("装备套装（防御）", equipSetDef, Zone.EQUIP_SET));
            breakdown.add(new Break("装备套装（技能）", equipSetSkill, Zone.EQUIP_SET));
        }

        // 武将乘区（HERO + BOND）受 zoneCap 约束；装备乘区独立，不占这个额度。
        // 这是 B05 §1.3「乘区隔离」的直接后果：合并成一个总加成就再也无法单独调某一条线了。
        boolean capped = false;
        long heroOnlyAtk = atk;
        long heroOnlyDef = def;
        if (atk > rules.zoneCapFixed()) {
            atk = rules.zoneCapFixed();
            capped = true;
        }
        if (def > rules.zoneCapFixed()) {
            def = rules.zoneCapFixed();
            capped = true;
        }
        if (capped) {
            breakdown.add(new Break("触发乘区上限（原始攻击加成 " + heroOnlyAtk
                    + "、防御加成 " + heroOnlyDef + "）", rules.zoneCapFixed(), Zone.HERO));
        }
        return new TeamBonus(atk, def, skill, equipSetAtk, equipSetDef, equipSetSkill,
                command, capped, breakdown);
    }

    /**
     * 按定点比例缩放一个<b>已经是定点数</b>的值。
     *
     * <p>这里绝不能再套 {@code FixedPoint.round}：{@code FixedPoint.mul} 的返回值本身
     * 就是定点数（800 = 8%），再 round 一次等于把「8% 的一半 = 4%」当成「0.04 个单位」
     * 取整成 0 —— 副将的全部贡献会被静默清零，而数字看起来仍然是「合法的 0」。
     */
    private static long scaled(long valueFixed, long ratioFixed) {
        return FixedPoint.mul(valueFixed, ratioFixed);
    }

    private static void requireNonNegativeFixed(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负：" + value);
        }
    }
}
