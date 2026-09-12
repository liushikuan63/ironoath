package com.ironoath.core.player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：玩家的 PVP 侧状态 —— 战力峰值时点、暴虐值、连续受害护盾（B08）。
 * 依赖：无（纯数据，随 {@link PlayerSave} 一起持久化）。
 *
 * <p><b>为什么单独一个值对象而不是往 PlayerSave 上加五个字段</b>：
 * {@code PlayerSave.restore} 已经有 11 个参数，再摊五个进去，
 * 反序列化时漏传或传错顺序就变成一件很容易发生、且编译器不报错的事。
 * 收成一个不可变对象后，这五项要么整体存在要么整体是 {@link #empty()}，
 * 改动时用 {@code with*} 返回新对象，天然对齐 PlayerSave 的乐观锁语义（整份替换）。
 *
 * <p><b>全部字段都是「惰性结算」的锚点</b>，没有一个需要定时器：
 * 峰值衰减按 {@code peakTouchedAt} 与 now 的差补算天数，暴虐衰减按 {@code tyrannyTouchedAt} 同理。
 * 这与 B03 的资源结算是同一套纪律（B00 铁律：禁止 @Scheduled 扫表结算）。
 *
 * @param peakTouchedAt     上次刷新战力峰值的时刻（毫秒）。峰值每日衰减 PEAK_POWER_DAILY_DECAY，
 *                          靠这个时间戳按天补算，不跑定时器
 * @param tyranny           暴虐值（B08 §4 的点数，不是定点小数）
 * @param tyrannyTouchedAt  上次推进暴虐值的时刻，每日衰减 BRUTALITY_DAILY_DECAY 靠它补算
 * @param victimShieldUntil 连续受害护盾的到期时刻，null 表示当前无护盾
 * @param attackerHits      统计窗口内的「攻击者 → 最近一次攻击时刻」。按<b>不同攻击者</b>计数，
 *                          不按攻击次数（match_rule.json 的裁定，见 Protection 的类注释）
 * @param closedUntil       闭城死守的「不可出兵采集」到期时刻（与 {@code peaceUntil} 同时推进）
 * @param exileAt           上次流亡迁城的时刻（B08 §5 的冷却锚点）。<b>必须是时刻而不是「按天计数」</b>：
 *                          原文是「3 天限 1 次」这一条<b>滚动</b>冷却，用日桶会变成
 *                          「每 3 个自然日重置」——第三天 23:59 与 00:01 各迁一次是合法的，
 *                          而那正是这条冷却要挡住的连续搬家
 */
public record PlayerPvp(long peakTouchedAt,
                        long tyranny,
                        long tyrannyTouchedAt,
                        Long victimShieldUntil,
                        Map<String, Long> attackerHits,
                        Long peaceUntil,
                        Long closedUntil,
                        Long exileAt) {

    /**
     * @param peaceUntil 道具授予的<b>自愿停战</b>到期时刻（免战牌，item.effectValue 秒后）。
     *
     *                   <p><b>为什么必须与 {@code victimShieldUntil} 分开存</b>：受害护盾由战斗
     *                   结算按 24h 窗口自动推进与覆盖，是「被打出来的」；免战牌是玩家花金币买的、
     *                   时长确定。共用一个字段的话，一张 24h 的牌会被随后的一次战斗悄悄改写成
     *                   4h（第一档护盾时长），而付费内容缩水比不生效更难被发现，也更接近合规事故。
     *                   两个来源与生命周期都不同的停战理由，就该各自成字段。
     */
    public PlayerPvp {
        if (peakTouchedAt < 0L) {
            throw new IllegalArgumentException("peakTouchedAt 不得为负：" + peakTouchedAt);
        }
        if (tyranny < 0L) {
            throw new IllegalArgumentException("暴虐值不得为负：" + tyranny);
        }
        if (tyrannyTouchedAt < 0L) {
            throw new IllegalArgumentException("tyrannyTouchedAt 不得为负：" + tyrannyTouchedAt);
        }
        if (tyranny > 0L && tyrannyTouchedAt == 0L) {
            // 有暴虐值却没有推进时刻，衰减就永远算不出天数，这个玩家会永久停在当前档位 ——
            // 而「公敌」变成一个摘不掉的标签正是 B08 §4 明确要避免的
            throw new IllegalArgumentException("暴虐值大于 0 时必须给出 tyrannyTouchedAt");
        }
        if (attackerHits == null) {
            attackerHits = Map.of();
        } else {
            // 先校验再拷贝：Map.copyOf 遇到 null 值抛的是 NullPointerException，
            // 排查时看不出是哪个攻击者的记录坏了
            attackerHits.forEach((id, at) -> {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("攻击者 id 不得为空");
                }
                if (at == null || at <= 0L) {
                    throw new IllegalArgumentException("攻击时刻必须为正的服务端时间戳，攻击者=" + id);
                }
            });
            attackerHits = Map.copyOf(new LinkedHashMap<>(attackerHits));
        }
    }

    /** 全新状态：没有任何峰值记录、没有暴虐值、没有护盾。 */
    public static PlayerPvp empty() {
        return new PlayerPvp(0L, 0L, 0L, null, Map.of(), null, null, null);
    }

    public PlayerPvp withPeakTouchedAt(long value) {
        return new PlayerPvp(value, tyranny, tyrannyTouchedAt, victimShieldUntil, attackerHits,
                peaceUntil, closedUntil, exileAt);
    }

    /** 替换暴虐值与它的推进时刻（两者必须同时改，见紧凑构造器的约束）。 */
    public PlayerPvp withTyranny(long value, long touchedAt) {
        return new PlayerPvp(peakTouchedAt, value, touchedAt, victimShieldUntil, attackerHits,
                peaceUntil, closedUntil, exileAt);
    }

    public PlayerPvp withVictimShieldUntil(Long value) {
        return new PlayerPvp(peakTouchedAt, tyranny, tyrannyTouchedAt, value, attackerHits,
                peaceUntil, closedUntil, exileAt);
    }

    /**
     * 设置自愿停战的到期时刻。<b>只延长、不缩短</b>（取 max，null 视为无）：
     * 玩家连用两张牌时，第二张不该把第一张的时间倒扣回去 —— 那是付了两次钱换到更短的免战。
     */
    public PlayerPvp withPeaceUntil(Long value) {
        Long merged = value == null ? peaceUntil
                : (peaceUntil == null ? value : Math.max(peaceUntil, value));
        return new PlayerPvp(peakTouchedAt, tyranny, tyrannyTouchedAt, victimShieldUntil,
                attackerHits, merged, closedUntil, exileAt);
    }

    /**
     * 闭城死守：设置「不可出兵采集」的到期时刻，<b>并同时把免战推到同一时刻</b>
     * （关门的意思是不打仗，两者不可能一个生效一个失效）。
     *
     * <p><b>为什么不能与 {@code peaceUntil} 合成一个字段</b>：24h 免战牌没有出兵限制，
     * 8h 闭城有。合一等于让先上线、更贵的那个付费道具顺带禁掉采集 ——
     * 花得更多的反而更亏。两种代价就该是两个到期时刻。
     */
    public PlayerPvp withClosedUntil(Long value) {
        return new PlayerPvp(peakTouchedAt, tyranny, tyrannyTouchedAt, victimShieldUntil,
                attackerHits,
                value == null ? peaceUntil
                        : (peaceUntil == null ? value : Math.max(peaceUntil, value)),
                value, exileAt);
    }

    /**
     * 流亡迁城：记下这次迁城的时刻（冷却锚点）。
     *
     * <p>与 {@link #withClosedUntil} 一样，落点免战由调用方<b>同时</b>用 {@link #withPeaceUntil}
     * 推进 —— 「搬完家就有 12 小时免战」是同一件事的两半，分开写会漏一半，
     * 而漏掉免战的迁城等于把人从家里连人带兵搬到仇人隔壁。
     */
    public PlayerPvp withExileAt(Long value) {
        return new PlayerPvp(peakTouchedAt, tyranny, tyrannyTouchedAt, victimShieldUntil,
                attackerHits, peaceUntil, closedUntil, value);
    }

    public PlayerPvp withAttackerHits(Map<String, Long> value) {
        return new PlayerPvp(peakTouchedAt, tyranny, tyrannyTouchedAt, victimShieldUntil, value,
                peaceUntil, closedUntil, exileAt);
    }

    /** 护盾此刻是否生效。 */
    public boolean shieldActive(long now) {
        return victimShieldUntil != null && victimShieldUntil > now;
    }
}
