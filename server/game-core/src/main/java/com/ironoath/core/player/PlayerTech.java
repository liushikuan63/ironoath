package com.ironoath.core.player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：个人科技在玩家存档上的那一位 —— <b>已研究的等级</b> 加 <b>当前那一个研究槽</b>（B20 块①）。
 * 依赖：纯 Java，零框架（铁律 2）；不读配置表，等级上限与前置都由调用方传进来。
 *
 * <p><b>为什么住存档而不是新开一张表</b>：整棵树一共 11 行、每行一个 int，加一个研究槽四个数。
 * 新开一个存储端口要动四个登记点（{@code MongoStorageGuard}、{@code BeanAssemblyTest}、
 * {@code MongoStoreConfig}、{@code MongoIndexes}）并多一次往返，收益是零 ——
 * 与 {@link PlayerGuide}、{@link PlayerPaid} 同一条判断，还白拿存档的乐观锁与玩家锁。
 *
 * <p><b>两条刻意的形状</b>：
 * <ul>
 *   <li><b>账本里不存 0 占位</b>：没研究过的行根本不在 {@code levels} 里，读取一律走
 *       {@link #levelOf(String)}（缺失即 0）。11 行全存 0 是 B20 §一 明确不要的做法，
 *       而联盟那边已经立过这条先例（{@code Alliance#techLevel}：换成持久化存储时占位是要占空间的）。</li>
 *   <li><b>「没有在研究」用 null 而不是 0</b>：{@code finishAt=0} 会被读成「1970 年就完成了」，
 *       于是每次读取都结算一次、每次结算都把 0 级写进账本 —— 那是会长出来的错，构造期就挡住它。</li>
 * </ul>
 *
 * <p><b>这里没有任何时长公式</b>：秒数由 web 层从 {@code curve.TECH_TIME} 算好后传进来（基数 13 秒
 * 是 {@code tools/calibrate-tech-time.mjs} 量出来的，见 #152）。core 不读配置表是铁律 1 的一部分，
 * 也是这条链路能在不起容器的情况下被测出来的原因。
 */
public record PlayerTech(Map<String, Integer> levels, String researchingId, Long finishAt,
                         long startedAt, long totalSeconds) {

    public PlayerTech {
        if (levels == null) {
            throw new IllegalArgumentException("科技账本不得为 null（什么都没研究过请传空 Map）");
        }
        Map<String, Integer> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            String id = e.getKey();
            Integer level = e.getValue();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("科技 id 不得为空白");
            }
            if (level == null || level <= 0) {
                throw new IllegalArgumentException("科技 " + id + " 存了等级 " + level
                        + "：没研究过的行不该进账本（0 占位请去掉，读取走 levelOf）");
            }
            copy.putIfAbsent(id, level);
        }
        levels = Collections.unmodifiableMap(copy);
        if (researchingId == null) {
            if (finishAt != null || startedAt != 0L || totalSeconds != 0L) {
                throw new IllegalArgumentException("空闲态必须三位全空：finishAt=" + finishAt
                        + " startedAt=" + startedAt + " totalSeconds=" + totalSeconds);
            }
        } else {
            if (researchingId.isBlank()) {
                throw new IllegalArgumentException("researchingId 不得为空白字符串（空闲请传 null）");
            }
            if (finishAt == null || finishAt <= 0L || startedAt <= 0L || totalSeconds <= 0L) {
                throw new IllegalArgumentException("研究中态三位都必须为正：tech=" + researchingId
                        + " finishAt=" + finishAt + " startedAt=" + startedAt + " totalSeconds=" + totalSeconds);
            }
            if (finishAt <= startedAt) {
                throw new IllegalArgumentException("完成时刻必须晚于开始时刻：" + finishAt + " <= " + startedAt);
            }
        }
    }

    /** 从没研究过、队列空着 —— 老存档读成这个，不是 null。 */
    public static PlayerTech empty() {
        return new PlayerTech(Map.of(), null, null, 0L, 0L);
    }

    /** 当前等级。<b>缺失即 0</b>：这条读法是唯一入口，账本里不给 0 占位留位置。 */
    public int levelOf(String techId) {
        Integer level = levels.get(techId);
        return level == null ? 0 : level;
    }

    public boolean isResearching() {
        return researchingId != null;
    }

    /** 剩余秒数，<b>绝不为负</b>（到点但还没结算的那段时间显示 0，而不是 -3）。 */
    public long remainingSeconds(long now) {
        if (!isResearching()) {
            return 0L;
        }
        long left = finishAt - now;
        if (left <= 0L) {
            return 0L;
        }
        return (left + 999L) / 1000L;   // 向上取整：不足 1 秒不能显示成「已完成」
    }

    /**
     * 开始一项研究。
     *
     * @param finishAt   服务端算好的完成时刻（毫秒）
     * @param totalSeconds 本次研究总时长，必须 ≥ 1（0 秒完成等于没有队列，§五④ 的 ceil 就是为这条）
     * @throws IllegalStateException 队列已被占用 —— 那是调用方漏了校验，不是玩家的错
     */
    public PlayerTech started(String techId, long finishAt, long startedAt, long totalSeconds) {
        if (isResearching()) {
            throw new IllegalStateException("队列已有 " + researchingId + " 在研究，不能再开 " + techId);
        }
        return new PlayerTech(levels, techId, finishAt, startedAt, totalSeconds);
    }

    /** 取消当前研究：只清队列，<b>已研究的等级一位都不动</b>（返还由调用方按城建比例算）。 */
    public PlayerTech cancelled() {
        return isResearching() ? new PlayerTech(levels, null, null, 0L, 0L) : this;
    }

    /**
     * 用加速道具推进当前研究（B20 验收 8）。
     *
     * <p><b>减到点就地结算，不留一个「finishAt 已被压到 now」的中间态</b>：那种状态会撞上一条
     * 构造期不变量（{@code finishAt > startedAt}）—— 而"研究刚开局就甩一张大令"恰恰是最常见的
     * 一次加速。更实的理由是语义：玩家花道具买的就是「现在就完成」，把这一级留给下一次惰性读取，
     * 响应里的 {@code finished} 与账本里的等级就对不上了。
     *
     * <p><b>超出剩余的部分不退还</b>，与城建加速同一口径（一张 8 小时令加速只剩 10 秒的研究，
     * 提前 10 秒、道具照扣）：按秒找零要发明一种表里没有的道具单位。
     * {@code reducedSeconds} 报的是<b>实际</b>提前量，所以界面不会显示成玩家亏了 28790 秒。
     *
     * @param reduceSeconds 本次投入的总秒数（调用方按 张数 × 单张 {@code effectValue} 算好）
     * @throws IllegalStateException    队列空着（那是调用方漏了校验）
     * @throws IllegalArgumentException 秒数不为正（0 张令不该走到这里）
     */
    public SpeedUp speedUp(long reduceSeconds, long now) {
        if (!isResearching()) {
            throw new IllegalStateException("队列空着，没有可加速的研究");
        }
        if (reduceSeconds <= 0L) {
            throw new IllegalArgumentException("加速秒数必须为正，实际=" + reduceSeconds);
        }
        long remaining = remainingSeconds(now);
        long applied = Math.min(reduceSeconds, remaining);
        if (applied >= remaining) {
            Completion done = completed();
            return new SpeedUp(done.tech(), applied, true);
        }
        // 这一支里 applied < remaining <= totalSeconds，所以 totalSeconds 与 finishAt 都还在
        // 合法区间内（不会归零、也不会退到 startedAt 之前）—— 上面那条不变量由这个不等式守住
        return new SpeedUp(new PlayerTech(levels, researchingId, finishAt - applied * 1000L,
                startedAt, totalSeconds - applied), applied, false);
    }

    /**
     * @param tech           加速之后的这一位（完成时已是结算后的账本，队列腾空）
     * @param reducedSeconds 实际提前的秒数（被剩余时间截断，绝不多报）
     * @param finished       这一级是否因此完成
     */
    public record SpeedUp(PlayerTech tech, long reducedSeconds, boolean finished) {
    }

    /**
     * 到点就结算：把 {@code researchingId} 记成 +1 级并腾空队列。
     *
     * <p>幂等 —— 没到期或本来就空闲时返回 {@code null}，因此读路径上每一次 {@code /tech/list}
     * 都可以先调它，不需要外部记得「结算过没有」。这是全项目无 {@code @Scheduled} 的那条老路：
     * 完成时刻一到，谁先读到谁就顺手结掉。
     *
     * @return 结算了哪一行、结算后的等级；没有可结算的东西时为 null
     */
    public Completion settled(long now) {
        if (!isResearching() || finishAt > now) {
            return null;
        }
        return completed();
    }

    /** 把当前这一行记成 +1 级并腾空队列。两条到点路径（读时结算、加速到点）共用这一个动作。 */
    private Completion completed() {
        int nextLevel = levelOf(researchingId) + 1;
        Map<String, Integer> after = new LinkedHashMap<>(levels);
        after.put(researchingId, nextLevel);
        return new Completion(new PlayerTech(after, null, null, 0L, 0L), researchingId, nextLevel);
    }

    /**
     * @param tech   结算之后的这一位（不可变，直接替换存档上的引用）
     * @param techId 刚研究完哪一行
     * @param level  它现在的等级（任务/活动要的就是这个数：状态型目标记的是「到几级」，不是「升了几次」）
     */
    public record Completion(PlayerTech tech, String techId, int level) {
    }
}
