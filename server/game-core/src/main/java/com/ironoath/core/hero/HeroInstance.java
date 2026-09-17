package com.ironoath.core.hero;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 职责：一名已拥有武将的状态 —— B06 §2 五条养成线里的四条（等级/星级/觉醒/技能）加上装备槽。
 * 依赖：无（纯 Java，不依赖 Spring / MongoDB / game-config）。
 *
 * <p>与 {@code CityState}/{@code PlayerSave} 同一套做法：聚合根可变、仓储整体持久化、
 * 只暴露带校验的操作方法，不暴露裸 setter。裸 setter 会让「升到 6 星」这种
 * 越过上限的写入在编译期看起来完全合法。
 *
 * <p><b>经验是「等级 + 当前等级内累计经验」两段式</b>，不是「总经验」：
 * 用总经验的话，每次显示进度条都要重跑一遍曲线求和，而且曲线热更后
 * 同一个总经验会对应到不同的等级 —— 玩家会因为一次调参而掉级。
 * 两段式让「已经到达的等级」成为既成事实，调曲线只影响后续升级所需经验。
 */
public final class HeroInstance {

    private final String heroId;
    private int level;
    private long exp;
    private int star;
    private int awaken;
    private int mainSkillLevel;
    private int subSkillLevel;
    private final Map<EquipSlot, String> equips = new EnumMap<>(EquipSlot.class);

    public HeroInstance(String heroId) {
        this(heroId, 1, 0L, 1, 0, 1, 1);
    }

    public HeroInstance(String heroId, int level, long exp, int star, int awaken,
                        int mainSkillLevel, int subSkillLevel) {
        if (heroId == null || heroId.isBlank()) {
            throw new IllegalArgumentException("heroId 不得为空");
        }
        if (level < 1) {
            throw new IllegalArgumentException("level 必须 >= 1，实际=" + level);
        }
        if (exp < 0L) {
            throw new IllegalArgumentException("exp 不得为负：" + exp);
        }
        if (star < 1) {
            throw new IllegalArgumentException("star 必须 >= 1（获得时就是 1 星），实际=" + star);
        }
        if (awaken < 0) {
            throw new IllegalArgumentException("awaken 不得为负：" + awaken);
        }
        if (mainSkillLevel < 1 || subSkillLevel < 1) {
            throw new IllegalArgumentException("技能等级必须 >= 1：main=" + mainSkillLevel
                    + ", sub=" + subSkillLevel);
        }
        this.heroId = heroId;
        this.level = level;
        this.exp = exp;
        this.star = star;
        this.awaken = awaken;
        this.mainSkillLevel = mainSkillLevel;
        this.subSkillLevel = subSkillLevel;
    }

    public String heroId() {
        return heroId;
    }

    public int level() {
        return level;
    }

    public long exp() {
        return exp;
    }

    public int star() {
        return star;
    }

    public int awaken() {
        return awaken;
    }

    public int mainSkillLevel() {
        return mainSkillLevel;
    }

    public int subSkillLevel() {
        return subSkillLevel;
    }

    /**
     * 某槽位上<b>那一件</b>装备的实例 uid；null 表示空槽。
     *
     * <p>B20 §五⑤ 之前这里存的是 {@code equip.json} 的行 id。形状不同（uid 形如 {@code e7}），
     * 所以老档里的行 id 能被一眼认出来并按 +0 解析（见 {@code EquipLedger#resolve}），
     * 不会读成"这个槽位是空的"。
     */
    public String equipOf(EquipSlot slot) {
        return equips.get(slot);
    }

    /** 四个槽位的只读视图（值 = 实例 uid），按 {@link EquipSlot} 声明顺序遍历（EnumMap 保证）。 */
    public Map<EquipSlot, String> equips() {
        return Collections.unmodifiableMap(equips);
    }

    // ---------- 五条养成线 ----------

    /**
     * 投喂经验，连续升级直到经验不足或到达上限。
     *
     * <p><b>一次投喂可能连升数级</b>（B06 §2.1 用经验书升级，而小经验书 500 经验
     * 对 1 级只需 20 经验的武将来说够升好几级），所以这里必须循环而不是升一级就返回。
     * 只升一级会让玩家看到「投了 25 本书却只升 1 级，剩下 499 经验凭空消失」。
     *
     * <p>满级时剩余经验<b>保留在 exp 里</b>而不是丢弃：满级不是永久状态
     * （赛季可能开放更高上限），丢掉玩家已经付费获得的东西是不可接受的。
     *
     * @return 实际升了多少级
     */
    public int feedExp(long amount, int maxLevel, HeroRules rules) {
        if (amount < 0L) {
            throw new IllegalArgumentException("经验不得为负：" + amount);
        }
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel 必须 >= 1，实际=" + maxLevel);
        }
        if (amount == 0L) {
            return 0;
        }
        exp += amount;
        int gained = 0;
        while (level < maxLevel) {
            long need = rules.expToNext(level, maxLevel);
            if (need <= 0L || exp < need) {
                break;
            }
            exp -= need;
            level++;
            gained++;
        }
        return gained;
    }

    /**
     * 升一星。
     *
     * @return 消耗的碎片数（由调用方按 hero_rarity.starUpFragment 决定并实际扣除）
     */
    public void starUp(HeroRules rules) {
        if (star >= rules.starMax()) {
            throw new IllegalStateException("已达星级上限 " + rules.starMax() + " 星，heroId=" + heroId);
        }
        star++;
    }

    /** 推进一阶觉醒。上限逐武将不同（hero 表 awakenMax：SSR 3 / SR 2 / R 1 / N 0）。 */
    public void awakenUp(int maxAwaken) {
        if (maxAwaken < 0) {
            throw new IllegalArgumentException("maxAwaken 不得为负：" + maxAwaken);
        }
        if (awaken >= maxAwaken) {
            throw new IllegalStateException("已达觉醒上限 " + maxAwaken + " 阶，heroId=" + heroId);
        }
        awaken++;
    }

    /**
     * 升技能。
     *
     * @param main true 升主技能，false 升副技能
     */
    public void skillUp(boolean main, HeroRules rules) {
        int current = main ? mainSkillLevel : subSkillLevel;
        if (current >= rules.skillMaxLevel()) {
            throw new IllegalStateException((main ? "主" : "副") + "技能已达上限 "
                    + rules.skillMaxLevel() + " 级，heroId=" + heroId);
        }
        if (main) {
            mainSkillLevel++;
        } else {
            subSkillLevel++;
        }
    }

    /**
     * 穿或卸装备。
     *
     * @param equipUid 要穿上的那一件的实例 uid；null 表示卸下该槽位（B06 验收 10：卸下后加成必须消失）
     * @return 被替换下来的那件的 uid；原本空槽则为 null。
     *         <b>调用方不必"把它放回背包"</b>（§五⑤ 之后实例永不出账本），
     *         只需要翻掉那一件的 worn 标志 —— 这件事在 {@code EquipWearer} 里与本次改动同批落库
     */
    public String equip(EquipSlot slot, String equipUid) {
        if (slot == null) {
            throw new IllegalArgumentException("slot 不得为 null");
        }
        if (equipUid != null && equipUid.isBlank()) {
            throw new IllegalArgumentException("装备 uid 不得为空白串，卸下请传 null");
        }
        String previous = equips.put(slot, equipUid);
        if (equipUid == null) {
            // 空槽要从 map 里真正移除，否则 equips() 会返回一个 value 为 null 的条目，
            // 而「有 4 个条目」会被误读成「穿满了 4 件」
            equips.remove(slot);
        }
        return previous;
    }

    /** 供仓储反序列化写回。业务代码不要用。 */
    public void restore(int level, long exp, int star, int awaken,
                        int mainSkillLevel, int subSkillLevel, Map<EquipSlot, String> restoredEquips) {
        this.level = level;
        this.exp = exp;
        this.star = star;
        this.awaken = awaken;
        this.mainSkillLevel = mainSkillLevel;
        this.subSkillLevel = subSkillLevel;
        this.equips.clear();
        if (restoredEquips != null) {
            restoredEquips.forEach((slot, id) -> {
                if (id != null) {
                    this.equips.put(slot, id);
                }
            });
        }
    }

    @Override
    public String toString() {
        return "HeroInstance{" + heroId + " Lv" + level + " ★" + star + " 觉醒" + awaken
                + " 技能" + mainSkillLevel + "/" + subSkillLevel + " 装备" + equips + "}";
    }
}
