package com.ironoath.core.bot;

import java.util.List;
import java.util.Optional;

import com.ironoath.core.world.Coord;

/**
 * 职责：Bot 的打野/采集「选哪个目标」（B11 §三 第 3 条：体力与队列空闲 → 打野 或 占领资源点采集）。
 * 依赖：只有坐标（纯 Java，零框架、零配置 —— 与 {@link BotDecisionTree} 同一纪律）。
 *
 * <p><b>为什么选择规则要有唯一的家</b>：执行侧（game-web）只负责把「看得见的候选」喂进来、
 * 把选中的目标变成一次与真人相同的出征。规则若写在执行侧，它会和「看得见什么」的读法一起漂移，
 * 而漂移的表现是「Bot 的行为没法用纯 JUnit 复现」。
 *
 * <p><b>两条规则都零新数值</b>（B11 没有给选怪策略与数值 —— 这是 2026-09-12 本轮定的口径，
 * 记在收口清单 #96，要改口径就改这一处）：
 * <ul>
 *   <li><b>打野 = 打得过的最强的怪</b>。「打得过」= 野怪战力 ≤ 自己的 matchPower：
 *       两边都是已有数（mapmonster 表的 power 列、玩家自己的战力），而那张表的 designNote
 *       就是用这条对照校准新号首战的（1 级怪 50 vs 新号 100 ⇒ 第一次打野必定打得动）。
 *       先滤「打不过的」，是它「像人」的关键 —— 玩家不会拿全部家当去白送。</li>
 *   <li><b>采集 = 最近的资源点</b>。资源点只有种类之分、没有强弱之分，
 *       距离是唯一可解释的偏好（少飞一会儿、少给对手留拦截窗口）。</li>
 * </ul>
 * 全部并列裁决（同级怪取更近、同距取坐标序）都是确定性比较、不用随机 ——
 * 同一份候选必须给出同一个答案，否则用例没法断言、复盘也没法解释。
 */
public final class BotPveTargets {

    private BotPveTargets() {
    }

    /** 看得见的一只野怪。{@code power} 由调用方查 mapmonster 表填进来（core 不读配置）。 */
    public record Monster(Coord coord, String monsterId, int level, long power) {

        public Monster {
            if (coord == null) {
                throw new IllegalArgumentException("野怪候选的坐标不得为 null");
            }
            if (monsterId == null || monsterId.isBlank()) {
                throw new IllegalArgumentException("野怪候选必须有 mapmonster 行 id");
            }
            if (level < 1) {
                throw new IllegalArgumentException("野怪等级必须 >= 1，实际=" + level);
            }
            if (power < 0L) {
                throw new IllegalArgumentException("野怪战力不得为负，实际=" + power);
            }
        }
    }

    /** 看得见的一个资源点。 */
    public record Resource(Coord coord, String resourceId) {

        public Resource {
            if (coord == null) {
                throw new IllegalArgumentException("资源点候选的坐标不得为 null");
            }
            if (resourceId == null || resourceId.isBlank()) {
                throw new IllegalArgumentException("资源点候选必须有资源 id");
            }
        }
    }

    /**
     * 打得过的最强的怪。
     *
     * <p>规则：先滤掉打不过的（{@code power > myMatchPower}），再取等级最高的一只；
     * 同级取更近的（曼哈顿距离，与行军/目标搜索同一口径），再并列取坐标序。
     *
     * @param visible      看得见的野怪（调用方已按「视野内已探索」筛过）
     * @param myMatchPower 自己的匹配战力；&lt;= 0 时没有打得过的怪（返回 empty）
     * @param home         自己的城坐标（并列时按距离比较）
     * @return 选中的怪；一只都打不过时 empty（调用方安静跳过，不是错误）
     */
    public static Optional<Monster> pickMonster(List<Monster> visible, long myMatchPower, Coord home) {
        if (visible == null) {
            throw new IllegalArgumentException("visible 不得为 null（没有候选就传空列表）");
        }
        if (home == null) {
            throw new IllegalArgumentException("home 不得为 null");
        }
        Monster best = null;
        for (Monster candidate : visible) {
            if (candidate.power() > myMatchPower) {
                continue;
            }
            if (best == null || betterMonster(candidate, best, home)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 最近的资源点（并列取坐标序）。
     *
     * @param visible 看得见的资源点（调用方已按「视野内已探索」筛过）
     * @param home    自己的城坐标
     * @return 选中的资源点；一个都看不见时 empty（调用方安静跳过，不是错误）
     */
    public static Optional<Resource> pickResource(List<Resource> visible, Coord home) {
        if (visible == null) {
            throw new IllegalArgumentException("visible 不得为 null（没有候选就传空列表）");
        }
        if (home == null) {
            throw new IllegalArgumentException("home 不得为 null");
        }
        Resource best = null;
        for (Resource candidate : visible) {
            if (best == null || betterResource(candidate, best, home)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean betterMonster(Monster a, Monster b, Coord home) {
        if (a.level() != b.level()) {
            return a.level() > b.level();
        }
        int da = home.distanceTo(a.coord());
        int db = home.distanceTo(b.coord());
        if (da != db) {
            return da < db;
        }
        return coordOrder(a.coord(), b.coord()) < 0;
    }

    private static boolean betterResource(Resource a, Resource b, Coord home) {
        int da = home.distanceTo(a.coord());
        int db = home.distanceTo(b.coord());
        if (da != db) {
            return da < db;
        }
        return coordOrder(a.coord(), b.coord()) < 0;
    }

    /** 行优先的坐标序 —— 只是并列裁决的最后一道，不是玩法规则。 */
    private static int coordOrder(Coord a, Coord b) {
        if (a.y() != b.y()) {
            return Integer.compare(a.y(), b.y());
        }
        return Integer.compare(a.x(), b.x());
    }
}
