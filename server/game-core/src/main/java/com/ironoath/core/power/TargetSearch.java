package com.ironoath.core.power;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;
import com.ironoath.core.world.Coord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 职责：B08 §8 的目标搜索 —— 候选池过滤 + 四项权重排序 + 分档下发。
 * 依赖：game-common 的 FixedPoint / Rng，game-core 的 {@link PowerBandGuard}（纯 Java，可脱离容器单测）。
 *
 * <p><b>放在 game-core 而不是 game-web</b>：验收 11 要「1000 玩家采样，2.1x 对手绝不出现、同段位占比 ≥ 60%」。
 * 这是一个统计性质的断言，必须能在纯 JUnit 里跑一千次而不启动 Spring —— 否则没人会真的跑它，
 * 而搜索排序恰恰是最容易在改权重时悄悄退化的地方。
 *
 * <p><b>两个字段刻意不精确下发</b>（验收 12 + B08 禁止项）：
 * <ul>
 *   <li>距离只给 NEAR/MID/FAR 三档：精确距离让客户端能自己算出「几秒能到」，
 *       把行军时间变成可离线推演的信息，而 B07 的设计是到达时刻由服务端权威下发。</li>
 *   <li>资源只给 RICH/NORMAL/POOR 三档：给数字等于把对方仓库状态下发出去，
 *       而那应当是侦查才能拿到的情报，否则 B07 §3 的「情报有误差」就失去意义。</li>
 * </ul>
 * 匹配战力是<b>照实下发</b>的：它是玩家判断「打不打得过」的必要信息，藏起来只会逼玩家去外部工具查战力。
 *
 * <p><b>没有任何「向下收益衰减」或「弱者补偿」的影子</b>：本类只决定「谁能出现在你的目标列表里」，
 * 不改动任何一方的收益或属性。圈层是<b>可见性</b>规则，不是<b>数值</b>规则 —— 这条界线是 B08 的头号禁止项。
 */
public final class TargetSearch {

    /** 距离档位。与协议里的 DistanceBand 一一对应（由 PowerContractParityTest 断言）。 */
    public enum DistanceBand {
        NEAR, MID, FAR
    }

    /** 资源富度档位。与协议里的 ResourceHint 一一对应。 */
    public enum ResourceHint {
        RICH, NORMAL, POOR
    }

    /**
     * 一个候选目标。
     *
     * @param playerId          目标玩家 id
     * @param coord             主城坐标
     * @param matchPower        匹配战力（不是展示战力 —— 圈层校验的唯一依据）
     * @param shielded          是否处于护盾（新手保护或受害护盾）
     * @param allianceId        所属联盟 id，null 表示无联盟（B10 落地前恒为 null）
     * @param lastActiveAt      最近活跃时刻（服务端毫秒时间戳）
     * @param resourceFillFixed 库存 / 仓库容量，定点 [0, 1.0]，超出会被截断
     * @param tyranny           暴虐值，用于决定下发的档位
     */
    public record Candidate(String playerId,
                            Coord coord,
                            long matchPower,
                            boolean shielded,
                            String allianceId,
                            long lastActiveAt,
                            long resourceFillFixed,
                            long tyranny) {

        public Candidate {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不得为空");
            }
            if (coord == null) {
                throw new IllegalArgumentException("coord 不得为 null");
            }
            if (matchPower < 0L) {
                throw new IllegalArgumentException("匹配战力不得为负：" + matchPower);
            }
            if (tyranny < 0L) {
                throw new IllegalArgumentException("暴虐值不得为负：" + tyranny);
            }
            if (resourceFillFixed < 0L) {
                resourceFillFixed = 0L;
            } else if (resourceFillFixed > FixedPoint.SCALE) {
                // 仓库溢出（离线产出超过容量）时比值会大于 1.0。截断而不是报错：
                // 富度只参与排序与档位提示，为一个提示字段把整次搜索打断不值得
                resourceFillFixed = FixedPoint.SCALE;
            }
        }
    }

    /**
     * 一次搜索请求。
     *
     * @param radius        请求半径，会被截断到 [1, maxRadius]
     * @param maxCount      请求数量，null 用默认值，超过上限会被截断
     * @param activeCutoff  活跃截止时刻：{@code lastActiveAt} 早于它的候选一律剔除。
     *                      由调用方用 {@code now - SEARCH_ACTIVE_WINDOW} 算好传进来 ——
     *                      本类不读时钟（铁律 5）
     */
    public record Request(int radius, Integer maxCount, long activeCutoff) {
    }

    /**
     * 搜索规则。全部来自 global 表（铁律 1：不硬编码），装配处在 game-web 的 PowerService。
     *
     * @param maxRadius          半径上限（SEARCH_MAX_RADIUS）
     * @param activeWindowMillis 活跃窗口（SEARCH_ACTIVE_WINDOW_HOURS），仅供调用方算 activeCutoff
     * @param defaultCount       maxCount 缺省时的返回数量（SEARCH_DEFAULT_COUNT）
     * @param maxCount           返回数量硬上限（SEARCH_MAX_COUNT）
     * @param weightPower        战力接近度权重（SEARCH_WEIGHT_POWER = 0.35）
     * @param weightDistance     距离近权重（SEARCH_WEIGHT_DISTANCE = 0.25）
     * @param weightResource     资源富度权重（SEARCH_WEIGHT_RESOURCE = 0.25）
     * @param weightRandom       随机权重（SEARCH_WEIGHT_RANDOM = 0.15）
     * @param nearRatio          NEAR 档的半径占比（SEARCH_NEAR_RATIO）
     * @param midRatio           MID 档的半径占比（SEARCH_MID_RATIO）
     * @param richRatio          RICH 档的库存占比（SEARCH_RICH_RATIO）
     * @param poorRatio          POOR 档的库存占比（SEARCH_POOR_RATIO）
     * @param peerRatioMin       同段位下界（SEARCH_PEER_RATIO_MIN = 0.80），验收 11 的统计口径
     * @param peerRatioMax       同段位上界（SEARCH_PEER_RATIO_MAX = 1.25）
     */
    public record Rules(int maxRadius,
                        long activeWindowMillis,
                        int defaultCount,
                        int maxCount,
                        long weightPower,
                        long weightDistance,
                        long weightResource,
                        long weightRandom,
                        long nearRatio,
                        long midRatio,
                        long richRatio,
                        long poorRatio,
                        long peerRatioMin,
                        long peerRatioMax) {

        public Rules {
            if (maxRadius < 1) {
                throw new IllegalArgumentException("搜索半径上限必须 >= 1：" + maxRadius);
            }
            if (activeWindowMillis <= 0L) {
                // 窗口为 0 会把所有候选判成「不活跃」，搜索永远返回空列表 ——
                // 而这是一个静默失败：没有异常，玩家只看到「附近没有可攻击目标」
                throw new IllegalArgumentException("活跃窗口必须为正，否则候选池恒为空：" + activeWindowMillis);
            }
            if (defaultCount < 1 || maxCount < defaultCount) {
                throw new IllegalArgumentException("返回数量必须满足 1 <= default <= max：default="
                        + defaultCount + ", max=" + maxCount);
            }
            long weightSum = weightPower + weightDistance + weightResource + weightRandom;
            if (weightSum != FixedPoint.SCALE) {
                // 四个权重必须正好加满 1.0。不加满不会立刻报错，
                // 但会让「调一个权重」的实际效果取决于其余三个的和 —— 那是一次没人能预测的调整。
                // 强制加满，任何改动都必须同时把其余几项重新配平，这是有意的摩擦
                throw new IllegalArgumentException("四项搜索权重之和必须为 1.0（定点 " + FixedPoint.SCALE
                        + "），实际=" + weightSum + "。请同时调整其余权重重新配平");
            }
            requireUnitRatio(nearRatio, "NEAR 档占比");
            requireUnitRatio(midRatio, "MID 档占比");
            if (midRatio <= nearRatio) {
                throw new IllegalArgumentException("距离档位占比必须严格递增：near=" + nearRatio
                        + ", mid=" + midRatio);
            }
            requireUnitRatio(richRatio, "RICH 档占比");
            requireUnitRatio(poorRatio, "POOR 档占比");
            if (poorRatio >= richRatio) {
                throw new IllegalArgumentException("富度档位必须满足 poor < rich：poor=" + poorRatio
                        + ", rich=" + richRatio);
            }
            if (peerRatioMin <= 0L || peerRatioMax < peerRatioMin) {
                throw new IllegalArgumentException("同段位区间必须满足 0 < min <= max：min="
                        + peerRatioMin + ", max=" + peerRatioMax);
            }
            if (peerRatioMin > FixedPoint.ONE || peerRatioMax < FixedPoint.ONE) {
                // 同段位区间必须跨过 1.0，否则「势均力敌」永远不算同段位，
                // 验收 11 的占比会恒为 0，而排序权重看起来一切正常
                throw new IllegalArgumentException("同段位区间必须包含 1.0（势均力敌）：min="
                        + peerRatioMin + ", max=" + peerRatioMax);
            }
        }

        private static void requireUnitRatio(long fixed, String name) {
            if (fixed < 0L || fixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException(name + "必须落在 [0, 1.0] 的定点区间，实际=" + fixed);
            }
        }

        /** 活跃截止时刻。core 层不读时钟，now 由调用方给。 */
        public long activeCutoff(long now) {
            if (now <= 0L) {
                throw new IllegalArgumentException("now 必须为正的服务端时间戳，实际=" + now);
            }
            return now - activeWindowMillis;
        }
    }

    /**
     * 一次搜索的结果项。
     *
     * @param score      综合得分（定点，越大越靠前）。不下发，只用于排序与调试
     * @param distance   曼哈顿距离（格）。<b>不得下发给客户端</b>（验收 12），只用于排序与分档
     * @param powerRatio 目标匹配战力 / 自己的匹配战力（定点）。这一项照实下发
     * @param peer       是否落在「同段位」区间内，验收 11 用它统计占比
     */
    public record Scored(Candidate candidate,
                         long score,
                         int distance,
                         long powerRatio,
                         DistanceBand distanceBand,
                         ResourceHint resourceHint,
                         boolean peer) {

        public Scored {
            if (candidate == null) {
                throw new IllegalArgumentException("candidate 不得为 null");
            }
            if (distance < 0) {
                throw new IllegalArgumentException("距离不得为负：" + distance);
            }
            if (distanceBand == null || resourceHint == null) {
                throw new IllegalArgumentException("档位不得为 null");
            }
        }
    }

    private TargetSearch() {
    }

    /**
     * 执行一次目标搜索。
     *
     * <p>流程（B08 §8）：半径 → 活跃度 → 护盾 → 同盟 → 战力圈层，五道过滤后按四项权重打分排序，
     * 最后截断到 maxCount。
     *
     * <p><b>圈层过滤用的是 {@link PowerBandGuard}，不是本类自己算一遍区间</b>（B08 禁止项：
     * 不要新增绕过统一中间件校验的代码路径）。搜索、发起攻击、发起集结三个入口必须得到
     * 同一个答案，否则玩家会遇到「列表里能选、点了却说打不了」。
     *
     * @param self    搜索者自己，不会出现在结果里
     * @param pool    候选池（一般是全世界已落位的玩家城）；null 视为空池
     * @param request 本次请求的半径 / 数量 / 活跃截止
     * @param rules   搜索规则
     * @param band    战力圈层规则
     * @param rng     随机源。随机项按 playerId fork 出子流，
     *                这样结果只取决于「有哪些候选」而不取决于候选池的遍历顺序
     */
    public static List<Scored> search(Candidate self, List<Candidate> pool, Request request,
                                      Rules rules, PowerBandGuard.Rules band, Rng rng) {
        if (self == null) {
            throw new IllegalArgumentException("self 不得为 null");
        }
        if (request == null) {
            throw new IllegalArgumentException("request 不得为 null");
        }
        if (rules == null || band == null) {
            throw new IllegalArgumentException("rules 与 band 都不得为 null");
        }
        if (rng == null) {
            throw new IllegalArgumentException("rng 不得为 null（随机权重必须可复现，铁律 4）");
        }
        int radius = Math.max(1, Math.min(request.radius(), rules.maxRadius()));
        int limit = request.maxCount() == null
                ? rules.defaultCount()
                : Math.max(1, Math.min(request.maxCount(), rules.maxCount()));
        long maxDeviation = maxDeviation(band);

        List<Scored> scored = new ArrayList<>();
        if (pool == null) {
            return List.of();
        }
        for (Candidate candidate : pool) {
            if (candidate == null || candidate.playerId().equals(self.playerId())) {
                continue;
            }
            // 曼哈顿距离，与行军时长用的是同一把尺子（Coord.distanceTo）。
            // 换成欧氏距离会让「列表里显示 NEAR 但走很久」，两把尺子必须一致
            int distance = self.coord().distanceTo(candidate.coord());
            if (distance > radius) {
                continue;
            }
            // 死号不进候选池：资源少、不会反击、打了没有任何互动，
            // 而「打了没人理」恰恰是玩家流失的直接原因（B08 §8 的理由原文）
            if (candidate.lastActiveAt() < request.activeCutoff()) {
                continue;
            }
            if (candidate.shielded()) {
                continue;
            }
            // 同盟不可攻击。B10 落地前 allianceId 恒为 null，这一条自然不生效
            if (candidate.allianceId() != null && candidate.allianceId().equals(self.allianceId())) {
                continue;
            }
            if (!PowerBandGuard.check(self.matchPower(), candidate.matchPower(), 1, band).allowed()) {
                continue;
            }
            // 自己的匹配战力为 0 时（刚建号且没有兵）比值无意义，PowerBandGuard 已经拒绝过这种情况，
            // 这里用 max(...,1) 只是防除零，不会真的走到
            long ratio = FixedPoint.div(FixedPoint.of(candidate.matchPower()),
                    FixedPoint.of(Math.max(self.matchPower(), 1L)));
            scored.add(new Scored(candidate,
                    scoreOf(candidate, distance, radius, ratio, rules, maxDeviation, rng),
                    distance, ratio,
                    distanceBand(distance, radius, rules),
                    resourceHint(candidate.resourceFillFixed(), rules),
                    isPeer(ratio, rules)));
        }
        // 排序必须完全确定：得分相同时按 playerId 兜底。
        // 不兜底的话，同一份输入在候选池顺序变化时会给出不同结果，
        // 玩家的表现是「刷新一下目标列表就换了一批人」，而排查时没有任何日志能复现
        scored.sort(Comparator.comparingLong(Scored::score).reversed()
                .thenComparing(s -> s.candidate().playerId()));
        return scored.size() > limit ? List.copyOf(scored.subList(0, limit)) : List.copyOf(scored);
    }

    /**
     * 综合得分 = 0.35×战力接近度 + 0.25×距离近 + 0.25×资源富度 + 0.15×随机（B08 §8）。
     *
     * <p><b>战力接近度用 {@code 1 - |r - 1/r| / maxDev}，不用 {@code 1 - |r - 1|}</b>。
     * 后者在对数轴上不对称：区间 [0.5x, 2.0x] 的两个端点，
     * 0.5x 能拿到 0.5 分而 2.0x 拿 0 分 —— 于是排序会系统性地把<b>弱目标推到前面</b>。
     * 那不是 B08 禁止的「向下收益衰减」（收益一分没少），但方向完全一致：
     * 让玩家一打开列表就看见最容易欺负的那几个人，
     * 而 C01 说的「杀死社交起点」正是从「虐菜太顺手」开始的。
     * {@code |r - 1/r|} 关于 r=1 对称（r 与 1/r 给出同一个值），两个端点都是 0 分，
     * 势均力敌才是满分 —— 这是「同段位对抗优先」唯一正确的度量方式。
     *
     * <p>随机项按 playerId fork 子流。若直接推进主随机流，结果就会依赖候选池的遍历顺序，
     * 而遍历顺序在换成 MongoDB 之后是不保证的 —— 那会让「同样的地图给出同样的目标」悄悄失效。
     */
    private static long scoreOf(Candidate candidate, int distance, int radius, long powerRatio,
                                Rules rules, long maxDeviation, Rng rng) {
        long powerCloseness = FixedPoint.ONE;
        if (powerRatio > 0L && maxDeviation > 0L) {
            powerCloseness = FixedPoint.sub(FixedPoint.ONE,
                    FixedPoint.div(logDeviation(powerRatio), maxDeviation));
        } else if (powerRatio <= 0L) {
            powerCloseness = 0L;
        }
        if (powerCloseness < 0L) {
            powerCloseness = 0L;
        }
        long distanceCloseness = FixedPoint.sub(FixedPoint.ONE,
                FixedPoint.div(FixedPoint.of(distance), FixedPoint.of(radius)));
        if (distanceCloseness < 0L) {
            distanceCloseness = 0L;
        }
        long randomTerm = rng.fork(candidate.playerId().hashCode()).nextFixed(0L, FixedPoint.SCALE);
        long total = FixedPoint.mul(rules.weightPower(), powerCloseness);
        total = FixedPoint.add(total, FixedPoint.mul(rules.weightDistance(), distanceCloseness));
        total = FixedPoint.add(total, FixedPoint.mul(rules.weightResource(), candidate.resourceFillFixed()));
        total = FixedPoint.add(total, FixedPoint.mul(rules.weightRandom(), randomTerm));
        return total;
    }

    /**
     * 倍率 r 的对数偏离量 {@code |r - 1/r|}（定点）。r=1 时为 0，r 与 1/r 时相等。
     *
     * <p>不用真的取对数：{@code |r - 1/r|} 与 {@code |ln r|} 在 [0.5, 2.0] 上单调同序，
     * 而排序只需要序、不需要绝对值。省掉一次 BigDecimal 对数，
     * 在上千候选的搜索里是实打实的差别。
     */
    private static long logDeviation(long ratioFixed) {
        if (ratioFixed <= 0L) {
            throw new IllegalArgumentException("倍率必须为正：" + ratioFixed);
        }
        long reciprocal = FixedPoint.div(FixedPoint.ONE, ratioFixed);
        return Math.abs(FixedPoint.sub(ratioFixed, reciprocal));
    }

    /**
     * 归一化用的最大偏离量：圈层上限与下限各自的对数偏离量取大者。
     *
     * <p>默认配置 lower=0.5 / upper=2.0 时两者相等（互为倒数），
     * 但若有人把区间调成不对称的（B08 开放问题 1 提到过 [0.33x, 3.0x]），
     * 取大者能保证接近度不会因为区间不对称而变成负数。
     */
    private static long maxDeviation(PowerBandGuard.Rules band) {
        return Math.max(logDeviation(band.upperRatioFixed()), logDeviation(band.lowerRatioFixed()));
    }

    /** 距离分档：只给三档，精确距离不出本类（验收 12）。 */
    public static DistanceBand distanceBand(int distance, int radius, Rules rules) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius 必须为正：" + radius);
        }
        if (distance <= FixedPoint.round(FixedPoint.mul(FixedPoint.of(radius), rules.nearRatio()))) {
            return DistanceBand.NEAR;
        }
        if (distance <= FixedPoint.round(FixedPoint.mul(FixedPoint.of(radius), rules.midRatio()))) {
            return DistanceBand.MID;
        }
        return DistanceBand.FAR;
    }

    /** 资源富度分档：只给三档，精确库存不出本类。 */
    public static ResourceHint resourceHint(long fillFixed, Rules rules) {
        if (fillFixed >= rules.richRatio()) {
            return ResourceHint.RICH;
        }
        if (fillFixed <= rules.poorRatio()) {
            return ResourceHint.POOR;
        }
        return ResourceHint.NORMAL;
    }

    /**
     * 是否「同段位」（验收 11 要求同段位占比 ≥ 60%）。
     *
     * <p>区间是 SEARCH_PEER_RATIO_MIN ~ SEARCH_PEER_RATIO_MAX（默认 0.80 ~ 1.25，互为倒数），
     * 即倍率的对数轴上关于 1.0 对称的一段：打 1.25 倍的人和被 0.80 倍的人打是同一件事。
     */
    public static boolean isPeer(long powerRatio, Rules rules) {
        return powerRatio >= rules.peerRatioMin() && powerRatio <= rules.peerRatioMax();
    }
}
