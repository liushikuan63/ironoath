package com.ironoath.core.hero;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：玩家的武将聚合根 —— 已拥有的武将、各稀有度碎片、编队预设（B06 §1/§4）。
 * 依赖：无（纯 Java，不依赖 Spring / MongoDB / game-config）。
 *
 * <p>与 {@code CityState} 同一套做法：聚合根可变、仓储整体持久化、
 * 所有规则校验都在聚合内完成，应用层只负责加锁与落库。
 *
 * <p><b>碎片不在这里</b>：碎片是道具（item 表的 {@code item_mat_hero_frag_*}），
 * 归属背包而不是武将存档。在两边各存一份等于开了第二个真相来源，
 * 而两份余额迟早会对不上 —— 玩家看到的是「背包里明明有 30 个碎片，升星却说不够」。
 * 所以本聚合只管武将本体与编队，碎片的增减一律走 {@code RewardPorts.Bag}。
 */
public final class HeroRoster {

    private final Map<String, HeroInstance> heroes = new LinkedHashMap<>();
    private final List<Lineup> lineups = new ArrayList<>();

    public HeroRoster() {
    }

    /**
     * 获得一名武将。
     *
     * @return true 表示首次获得；false 表示重复（调用方应据此把奖励转成碎片，
     *         数量取 hero_rarity.dupFragment）
     */
    public boolean obtain(String heroId) {
        requireText(heroId, "heroId");
        if (heroes.containsKey(heroId)) {
            return false;
        }
        heroes.put(heroId, new HeroInstance(heroId));
        return true;
    }

    /** 直接放入一个已存在的武将状态（仓储反序列化用）。 */
    public void restoreHero(HeroInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance 不得为 null");
        }
        heroes.put(instance.heroId(), instance);
    }

    public boolean owns(String heroId) {
        return heroes.containsKey(heroId);
    }

    public HeroInstance hero(String heroId) {
        HeroInstance instance = heroes.get(heroId);
        if (instance == null) {
            throw new IllegalStateException("尚未拥有武将 " + heroId);
        }
        return instance;
    }

    /** 已拥有的武将，按获得顺序（LinkedHashMap 保证）。 */
    public Collection<HeroInstance> heroes() {
        return Collections.unmodifiableCollection(heroes.values());
    }

    public int heroCount() {
        return heroes.size();
    }

    // ---------- 编队 ----------

    /** 取某套预设；不存在时按空队补建，保证预设数量恒等于 rules.presetCount。 */
    public Lineup lineup(int presetIndex, HeroRules rules) {
        ensureLineups(rules);
        if (presetIndex < 0 || presetIndex >= lineups.size()) {
            throw new IllegalArgumentException("presetIndex 必须落在 [0, " + (lineups.size() - 1)
                    + "]，实际=" + presetIndex);
        }
        return lineups.get(presetIndex);
    }

    public List<Lineup> lineups(HeroRules rules) {
        ensureLineups(rules);
        return Collections.unmodifiableList(lineups);
    }

    /**
     * 设置一套编队。
     *
     * <p>校验三件事，缺一不可：
     * <ol>
     *   <li>每名武将都必须已拥有 —— 否则客户端伪造一个 heroId 就能白拿加成</li>
     *   <li>同一名武将不能同时出现在两个位置（含跨预设？不含 —— 预设之间互斥使用，
     *       同一时刻只有一套生效，所以只校验本套内部）</li>
     *   <li>副将数量不得超过 lineupSize - 1</li>
     * </ol>
     *
     * <p><b>B06 验收 5「下阵无残留」由本方法的语义保证</b>：编队是整体替换而不是增量修改，
     * 传 null 就是空位。增量修改（「把 X 加进队伍」）会让「移除」变成一条独立路径，
     * 而独立路径漏掉一次清理就是永久残留的加成。
     */
    public void setLineup(int presetIndex, String main, List<String> subs, HeroRules rules) {
        ensureLineups(rules);
        if (presetIndex < 0 || presetIndex >= lineups.size()) {
            throw new IllegalArgumentException("presetIndex 必须落在 [0, " + (lineups.size() - 1)
                    + "]，实际=" + presetIndex);
        }
        if (subs != null && subs.size() > rules.lineupSize() - 1) {
            throw new IllegalArgumentException("副将数量不得超过 " + (rules.lineupSize() - 1)
                    + "（每队共 " + rules.lineupSize() + " 名），实际=" + subs.size());
        }
        Set<String> seen = new LinkedHashSet<>();
        if (main != null) {
            requireOwned(main);
            seen.add(main);
        }
        if (subs != null) {
            for (String sub : subs) {
                if (sub == null) {
                    continue;
                }
                requireOwned(sub);
                if (!seen.add(sub)) {
                    throw new IllegalArgumentException("武将 " + sub + " 在同一支队伍里出现了两次");
                }
            }
        }
        lineups.set(presetIndex, new Lineup(presetIndex, main, subs == null ? List.of() : subs));
    }

    /**
     * 已激活的缘分条数（B06 §3：特定武将同队激活加成）。
     *
     * <p>缘分是<b>成对</b>的（hero 表的 bondWith 是双向的：裴惊澜↔燕孤鸿），
     * 所以一队里两个人都在才算一条，且只算一条而不是两条。
     * 用「A 的 bondWith 是 B，且 B 也在队里，且 A 的 id 字典序小于 B」去重，
     * 避免把同一条缘分按两个方向各数一次。
     *
     * @param bondOf heroId → 它的 bondWith（null 表示没有缘分）。由调用方从 hero 表解析后传入
     */
    public int activeBonds(Lineup lineup, Map<String, String> bondOf) {
        if (lineup == null) {
            throw new IllegalArgumentException("lineup 不得为 null");
        }
        if (bondOf == null) {
            throw new IllegalArgumentException("bondOf 不得为 null（没有缘分请传空 Map）");
        }
        List<String> members = lineup.members();
        Set<String> inTeam = new LinkedHashSet<>(members);
        int count = 0;
        for (String heroId : members) {
            String partner = bondOf.get(heroId);
            if (partner == null || !inTeam.contains(partner)) {
                continue;
            }
            // 只在一个方向上计数，否则一条缘分会被数成两条（加成翻倍）
            if (heroId.compareTo(partner) < 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 供仓储反序列化写回编队。业务代码请用 {@link #setLineup}。
     *
     * <p>先拷一份再 clear：{@link #lineups(HeroRules)} 返回的是内部 List 的不可变视图，
     * 调用方把它传回来的话，{@code clear()} 会把入参一起清空，编队会全部凭空消失。
     */
    public void restoreLineups(List<Lineup> restored) {
        List<Lineup> copy = restored == null ? List.of() : new ArrayList<>(restored);
        lineups.clear();
        lineups.addAll(copy);
    }

    /**
     * 一份完整的武将存档。字段与 {@link HeroInstance#restore} 一一对应。
     *
     * <p>提出来是为了消灭"两份什么算完整"：原来这份枚举写在 {@link #copy()} 里，
     * 补第二种存储（Mongo）时它会变成第二份，少抄一个字段的表现不是报错，
     * 而是"换存储后某个养成字段静默不持久化"。现在深拷贝与 Mongo 文档都只从这里出。
     */
    public record HeroSnapshot(String heroId, int level, long exp, int star, int awaken,
                               int mainSkillLevel, int subSkillLevel, Map<EquipSlot, String> equips) {
    }

    /** 整个武将存档的快照（含编队预设）。 */
    public record Snapshot(List<HeroSnapshot> heroes, List<Lineup> lineups) {
    }

    /** 取出完整快照（不可变）。 */
    public Snapshot snapshot() {
        List<HeroSnapshot> copies = new ArrayList<>(heroes.size());
        for (HeroInstance source : heroes.values()) {
            copies.add(new HeroSnapshot(source.heroId(), source.level(), source.exp(), source.star(),
                    source.awaken(), source.mainSkillLevel(), source.subSkillLevel(), source.equips()));
        }
        return new Snapshot(List.copyOf(copies), List.copyOf(lineups));
    }

    /** 由快照重建。顺序（武将拥有关系先于编队）不影响正确性：编队校验只在应用层做。 */
    public static HeroRoster fromSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("快照不得为 null：没有快照就没有重建");
        }
        HeroRoster clone = new HeroRoster();
        for (HeroSnapshot source : snapshot.heroes()) {
            HeroInstance target = new HeroInstance(source.heroId());
            target.restore(source.level(), source.exp(), source.star(), source.awaken(),
                    source.mainSkillLevel(), source.subSkillLevel(), source.equips());
            clone.restoreHero(target);
        }
        clone.restoreLineups(snapshot.lineups());
        return clone;
    }

    /**
     * 深拷贝。仓储返回给调用方的必须是副本，否则调用方改到的就是库里那一份，
     * 乐观锁版本号形同虚设（改完再 save 会「成功」，但中途的中间状态对所有读者可见）。
     *
     * <p>连 {@link HeroInstance} 一起复制：它也是可变的。实现只做一件事 ——
     * 走 {@link #snapshot()} / {@link #fromSnapshot}，这样"完整"只有一份定义。
     */
    public HeroRoster copy() {
        return fromSnapshot(snapshot());
    }

    private void ensureLineups(HeroRules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        while (lineups.size() < rules.presetCount()) {
            lineups.add(Lineup.empty(lineups.size()));
        }
    }

    private void requireOwned(String heroId) {
        if (!heroes.containsKey(heroId)) {
            throw new IllegalArgumentException("尚未拥有武将 " + heroId + "，不能上阵");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空");
        }
    }

    @Override
    public String toString() {
        return "HeroRoster{武将" + heroes.size() + "名, 编队" + lineups + "}";
    }
}
