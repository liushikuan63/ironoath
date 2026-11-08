package com.ironoath.core.nation;

import java.util.List;

/**
 * 一场国战<b>已经算好、但可能还没投递完</b>的赛季分清单（B13 #2459 / #2460 / #2461）。
 *
 * <p><b>为什么要把这份清单冻进战事存档，而不是每次重算</b>：发奖是<b>另一次存储写入</b>
 * （{@code SeasonBoardStore} 那张赛季榜），它与"战事已结算"不在一个事务里。中间断掉以后，
 * 重试必须发出<b>与第一次完全相同</b>的载荷 —— 幂等回执那一侧是按
 * {@code (playerId, sourceId)} 比对整份载荷的（见 {@code SeasonBoardStore#isScopedReplay}）：
 * 同代次但载荷不同会直接抛「同代次原事件载荷冲突」。
 *
 * <p>重算的话，载荷至少有两处在两次尝试之间会变：
 * <ul>
 *   <li><b>昵称</b>——玩家改了名字，回执就对不上了；</li>
 *   <li><b>三条 bonus 的档位</b>——它们来自配置表、可热更，改一次表就会让还没发完的那一场永远发不出去。</li>
 * </ul>
 * 所以「发多少、发给谁、叫什么名字」在第一次尝试时定格，之后只重放这份定格。
 *
 * <p><b>{@code seasonId} 为 null 表示这一场不发</b>（{@code active()==false}），而不是一份空清单：
 * 「没有赛季」和「有赛季但没人够门槛」在下游要走不同的路 —— 前者要在日志里留下痕迹（B13 #2460
 * 要求恢复不出原赛季时 fail closed），后者是正常结果，不该报警。
 *
 * <p>成员<b>按 {@code playerId} 严格升序</b>，与 {@code StrategicBattleKillPlan} 同一条纪律：
 * 落盘与重放都要一个与哈希顺序无关的确定序列，否则两次读出来的同一份计划会因为遍历顺序不同而"载荷冲突"。
 */
public record WarAwardPlan(int formatVersion, long warStartedAt, String seasonId, List<Member> members) {

    /** 一名受益人：谁、叫什么、这一场给他多少分。 */
    public record Member(String playerId, String displayName, long score) {

        public Member {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("国战奖分成员缺少玩家 id：没有 id 就不知道分该进谁的榜");
            }
            if (score <= 0L) {
                throw new IllegalArgumentException("国战奖分成员的分必须为正，实际=" + score
                        + "。0 分不建行是这一格的一贯形状，混进清单会让回执与榜单各说各话");
            }
        }
    }

    public WarAwardPlan {
        if (formatVersion != 1) {
            throw new IllegalArgumentException("国战奖分计划版本无效：formatVersion=" + formatVersion
                    + "。这个字段是给以后改形状留的，读到不认识的版本应当拒绝而不是猜");
        }
        // 以领域内不可变的开战时刻绑定计划；存储层的 war_<startedAt> 键由 game-web 推导，
        // game-core 不依赖 game-web 的 WarStore。
        if (seasonId != null && seasonId.isBlank()) {
            throw new IllegalArgumentException("国战奖分计划的赛季 id 不得为空白：没有赛季请传 null，"
                    + "空白会被下游当成一个真赛季");
        }
        members = List.copyOf(members == null ? List.of() : members);
        if (seasonId == null && !members.isEmpty()) {
            throw new IllegalArgumentException("没有赛季时不能带受益成员：分无处可进，"
                    + "留着它只会让下一次重试以为还有事要做");
        }
        String previous = null;
        for (Member member : members) {
            if (previous != null && previous.compareTo(member.playerId()) >= 0) {
                throw new IllegalArgumentException("国战奖分成员必须按 playerId 严格升序（重复也不行）："
                        + previous + " -> " + member.playerId());
            }
            previous = member.playerId();
        }
    }

    /** 这一场到底要不要发。false 的那一份只用来记「已经判过：不发」，不产生任何榜单写入。 */
    public boolean active() {
        return seasonId != null;
    }

    /** 这份计划覆盖的人数（0 表示没人够门槛）。 */
    public int size() {
        return members.size();
    }
}
