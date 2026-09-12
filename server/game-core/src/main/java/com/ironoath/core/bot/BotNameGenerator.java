package com.ironoath.core.bot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ironoath.common.rng.Rng;

/**
 * 职责：Bot 名字生成（B11 §四：姓表 × 名表 × 称号表组合，服务器内唯一，禁止机器名；验收 11）。
 * 依赖：game-common 的 Rng（纯 Java，零框架）。
 *
 * <p><b>为什么名字值得单独一个类</b>：验收 11 是「随机 100 个 Bot 名字，无机器感命名、无敏感词、
 * 无重复」—— 三条里最难的是「无重复」。姓 30 × 名 40 × 称号 15 = 18000 个组合，
 * 而单服 Bot 上限是 5000。按生日悖论，随机抽 5000 次几乎必然撞名，
 * 所以必须边生成边查重，撞了就重抽。
 *
 * <p><b>重抽有上限，超出就退回带后缀的名字</b>：无限重抽在池子快用完时会退化成死循环
 * （5000/18000 已经用掉 28%，抽到重复的概率约 28%，期望重试次数不到 1.4 次，
 * 但如果将来把上限调到接近组合数，重试次数会爆炸）。
 * 退回策略必须存在，否则「池子不够」这个内容问题会变成「开服卡住」这个事故。
 *
 * <p><b>随机源用 Rng 而不是 Math.random</b>（铁律 4：随机可复现）。
 * 名字必须能复现的理由比战斗更硬：客服接到「我邻居昨天还叫张三今天变李四了」的投诉时，
 * 唯一能核查的手段就是用同一个种子重跑一遍生成过程。
 */
public final class BotNameGenerator {

    /** 单个名字最多重抽几次。超出就退回加数字后缀 —— 那仍然比死循环好。 */
    private static final int MAX_RETRY = 32;

    /** 称号的使用概率（定点）。来源 global.BOT_NAME_TITLE_CHANCE */
    private final long titleChanceFixed;
    private final List<String> surnames;
    private final List<String> givens;
    private final List<String> titles;

    /**
     * @param surnames        姓池（bot_name 表 namePool，行 id = global.BOT_NAME_SURNAME_POOL）
     * @param givens          名池
     * @param titles          称号池
     * @param titleChanceFixed 带称号的概率（定点 0~1.0）
     */
    public BotNameGenerator(List<String> surnames, List<String> givens, List<String> titles,
                            long titleChanceFixed) {
        if (surnames == null || surnames.isEmpty()) {
            throw new IllegalArgumentException("姓池不得为空");
        }
        if (givens == null || givens.isEmpty()) {
            throw new IllegalArgumentException("名池不得为空");
        }
        if (titles == null) {
            throw new IllegalArgumentException("称号池不得为 null（不需要称号请传空列表）");
        }
        if (titleChanceFixed < 0 || titleChanceFixed > com.ironoath.common.num.FixedPoint.SCALE) {
            throw new IllegalArgumentException("titleChance 必须落在 [0, 1.0] 的定点区间，实际=" + titleChanceFixed);
        }
        if (titleChanceFixed > 0 && titles.isEmpty()) {
            throw new IllegalArgumentException("titleChance=" + titleChanceFixed
                    + " 但称号池为空：那样每次抽中称号都只能退回，等于白配了一个概率");
        }
        this.surnames = List.copyOf(surnames);
        this.givens = List.copyOf(givens);
        this.titles = List.copyOf(titles);
        this.titleChanceFixed = titleChanceFixed;
    }

    /** 理论组合数。用于断言「池子够不够撑 BOT_MAX_PER_SERVER」。 */
    public long combinationCount() {
        long base = (long) surnames.size() * givens.size();
        if (titles.isEmpty() || titleChanceFixed == 0) {
            return base;
        }
        // 带称号与不带称号是两组互斥的名字，所以总数是 base × (1 + titles)
        return base * (1L + titles.size());
    }

    /**
     * 生成一个不与 {@code taken} 重复的名字。
     *
     * @param rng    随机源。调用方按 Bot 的 nameSeed fork 一份，保证可复现
     * @param taken  已占用的名字集合（全服唯一，含真人昵称）
     * @return 一个可用名字
     * @throws IllegalStateException 重抽 {@value #MAX_RETRY} 次仍然撞名。
     *         抛错而不是返回一个重复的名字：重名的表现是「两个张三互相收不到私信」，
     *         而这种 bug 在几千个 Bot 里只会偶发，几乎不可能定位
     */
    public String generate(Rng rng, Set<String> taken) {
        if (rng == null) {
            throw new IllegalArgumentException("rng 不得为 null");
        }
        Set<String> used = taken == null ? new HashSet<>() : taken;
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            String candidate = compose(rng);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        // 退回：强制带上称号，把组合空间再乘上 titles.size()。
        // **绝不能加数字后缀**：「北望杨怀瑶 1」正是 B11 §四 禁止的
        // 「玩家 12345 式机器名」—— 为了不重名而造出一个一眼假的名字，
        // 是拿验收 11 换一个不报错，不划算
        for (int attempt = 0; attempt < MAX_RETRY && !titles.isEmpty(); attempt++) {
            String candidate = composeWithTitle(rng);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("名字池已耗尽：重抽 " + (MAX_RETRY * 2) + " 次仍然撞名，"
                + "已占用 " + used.size() + " 个，组合总数 " + combinationCount()
                + "。请扩充 bot_name 表（B11 §十一 建议 姓100 × 名200 × 称号50）");
    }

    /** 强制带称号的组合。只在普通组合已经撞完时使用。 */
    private String composeWithTitle(Rng rng) {
        String surname = surnames.get((int) rng.range(0, surnames.size() - 1L));
        String given = givens.get((int) rng.range(0, givens.size() - 1L));
        String title = titles.get((int) rng.range(0, titles.size() - 1L));
        return title + surname + given;
    }

    /** 拼一个名字。称号按 titleChance 概率出现。 */
    private String compose(Rng rng) {
        // Rng.range 返回 long（它要能覆盖定点区间），而名字池只有几十到几百项，
        // 所以这里显式收窄成下标；池子真的大到 int 装不下的时候，名字重复问题早就先爆了
        String surname = surnames.get((int) rng.range(0, surnames.size() - 1L));
        String given = givens.get((int) rng.range(0, givens.size() - 1L));
        boolean useTitle = !titles.isEmpty()
                && rng.range(0, com.ironoath.common.num.FixedPoint.SCALE - 1L) < titleChanceFixed;
        String title = useTitle ? titles.get((int) rng.range(0, titles.size() - 1L)) : "";
        return title + surname + given;
    }
}
