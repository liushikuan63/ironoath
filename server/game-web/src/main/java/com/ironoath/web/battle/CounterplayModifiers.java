package com.ironoath.web.battle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.battle.BattleModifier;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerPvp;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.power.Tyranny;
import com.ironoath.web.service.PowerService;
import com.ironoath.web.service.SocialAppService;

/**
 * 职责：把 B08 §6 的反击加成装配成内核的乘区 F —— 复仇 +15%、围剿公敌 +15%（C00 公理一 / C01 §4）。
 * 依赖：{@link PlayerRepository}（受害账本与暴虐值都在存档上）、{@link PowerService}（暴虐规则与衰减天数）、
 *       {@link SocialAppService}（小队成员，复仇的「盟友」范围）、global 表（三个比率与一个窗口）。
 *
 * <p><b>这个类存在的唯一理由是「判定必须有调用点」</b>。在此之前的形态是：
 * {@code Tyranny#triggersCrusade} 与三个 global 参数都写好了，但四处生产调用点全部传
 * {@code BattleModifier.none()}，于是复仇与围剿在运行时恒为 0，而 {@code BattleModifierTest}
 * 测的是值对象本身 —— 全绿。同一族里的两个案例：{@code BotTuning#mayHoldOffice} 已在
 * 收口清单 #89 接上（成为任命与议员席的唯一口径），{@code PopupThrottle.shouldShow}
 * 仍未接（上线清单 §七 1）。
 *
 * <p><b>PVE 不装配任何东西</b>：野怪与关卡没有暴虐值，也不会在 24h 内攻击过谁，
 * 所以 {@code MonsterBattleService} 与 {@code StageAppService} 传 {@code none()} 是正确的，
 * 不是漏接。判据是「对手是不是一个有 {@link PlayerPvp} 的玩家」，不是战斗类型。
 *
 * <p><b>哀兵（{@code BONUS_MOURNING}）不在这里，且已裁决退役</b>：三处原文（C01 §4、B00 §反击加成、
 * global 表自己的 source 字段）都把它限定成「防守集结」的加成，而<b>防守集结不存在于任何批次的交付清单</b>
 * —— B10 只有集结进攻。给它编一个触发条件（例如「凡是守方就 +10%」）等于把一条被动补偿塞进乘区 F，
 * 而那正是 C01 反直觉条款 2 禁止的东西。
 * <b>2026-09-13 裁决（收口清单 §三·补 B9）：退役这条乘区</b> —— 表里那一行标了 {@code todo}（启动日志会
 * WARN 出来，别把它当成已生效的加成），内核 {@code BattleModifier.aggrieved} 与它的正确性用例保留为
 * 「机制就绪、玩法未定」；要启用先回答「防守集结是什么玩法」。本类<b>永远不该</b>出现给它喂值的代码。
 */
@Component
public class CounterplayModifiers {

    private static final Logger LOG = LoggerFactory.getLogger(CounterplayModifiers.class);

    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private final PowerService powerService;
    private final SocialAppService socialAppService;

    public CounterplayModifiers(ConfigRegistry configs, PlayerRepository players,
                                PowerService powerService, SocialAppService socialAppService) {
        this.configs = configs;
        this.players = players;
        this.powerService = powerService;
        this.socialAppService = socialAppService;
    }

    /**
     * 反击加成的口径参数，全部来自 global 表（铁律 1：不硬编码）。
     *
     * @param revengeFixed        复仇比率（定点，BONUS_REVENGE = 1500）
     * @param crusadeFixed        围剿公敌比率（定点，BONUS_SIEGE_PUBLIC_ENEMY = 1500）
     * @param revengeWindowMillis 复仇窗口（毫秒，REVENGE_WINDOW_SECONDS = 24h）
     */
    public record Rules(long revengeFixed, long crusadeFixed, long revengeWindowMillis) {
    }

    /**
     * 装配一次用的参数。<b>不缓存</b>：与 {@code PowerService} 的各 rules 方法同理，
     * 配置热更之后下一场战斗就该用新数，缓存会把热更变成重启才生效。
     *
     * <p><b>窗口耦合是启动即失败级的检查</b>：复仇读的是 {@code PlayerPvp#attackerHits}，
     * 而那本账是由受害护盾按 {@code VICTIM_SHIELD_WINDOW_SECONDS} 修剪的。两者数值恰好都是 24h，
     * 但<b>语义上是两个不同的配置</b>：一旦有人把护盾窗口调成 12h，复仇会在毫无征兆的情况下
     * 只认 12 小时之内的仇（12~24h 那段的账本已经被删了）。这种「不报错、只是少给加成」
     * 的漂移是本项目反复在防的那一类，所以直接抛而不是打日志继续。
     */
    public Rules rules() {
        long revengeWindowMillis = configs.longParam("REVENGE_WINDOW_SECONDS") * 1000L;
        long hitLedgerWindowMillis = powerService.protectionRules().windowMillis();
        if (revengeWindowMillis > hitLedgerWindowMillis) {
            throw new IllegalStateException("复仇窗口 REVENGE_WINDOW_SECONDS="
                    + (revengeWindowMillis / 1000L) + "s 不得超过受害账本窗口 VICTIM_SHIELD_WINDOW_SECONDS="
                    + (hitLedgerWindowMillis / 1000L) + "s：复仇判定读的就是那本账，"
                    + "账本先修剪会让超出护盾窗口的那段仇无声消失。要么把两者一起调，"
                    + "要么给复仇单独建一本不受护盾窗口约束的账。");
        }
        return new Rules(configs.fixedParam("BONUS_REVENGE"),
                configs.fixedParam("BONUS_SIEGE_PUBLIC_ENEMY"), revengeWindowMillis);
    }

    /**
     * 攻方侧的乘区 F。两项都以「对手是一个玩家」为前提，PVE 不走这里。
     *
     * @param attackerId 攻方。复仇看的是<b>攻方自己</b>的受害账本
     * @param defenderId 守方。围剿看的是<b>守方此刻的暴虐档位</b>
     */
    public BattleModifier attackerSide(String attackerId, String defenderId, long now) {
        Rules rules = rules();
        long revenge = isRevenge(attackerId, defenderId, now, rules) ? rules.revengeFixed() : 0L;
        long crusade = isCrusade(defenderId, now, rules) ? rules.crusadeFixed() : 0L;
        if (revenge == 0L && crusade == 0L) {
            return BattleModifier.none();
        }
        return new BattleModifier(revenge, 0L, crusade, 0L);
    }

    /**
     * 复仇：目标在窗口内攻击过自己或小队的任何成员（C01 §4「24h 内反击曾攻击过自己/盟友的目标」）。
     *
     * <p><b>盟友只算小队，不算联盟</b>：C01 写的是「自己/盟友」而没有界定范围，而这里的选择有代价。
     * 账本在每个人自己的存档上，所以查一个盟友就是一次存档读取；联盟上限 150 人，
     * 那意味着每场 PVP 结算多出 150 次读档 —— 而这条路径在王城战时会按行军数放大。
     * 小队（上限 5）才是 C00 公理七说的那层「我和兄弟们」，也是 B08 求援链路的推送范围，
     * 所以按小队实现。要扩到联盟需要先给受害账本加索引，那是一条记录在案的后续。
     *
     * <p><b>用 attackerHits 而不是扫战报</b>：{@code BattleReportStore#reportsOf} 能给出同样的事实，
     * 但它是「一个人的全部战报」，为了一个 bool 去遍历整条列表、还要按 battleType 过滤，
     * 而 attackerHits 本来就是「谁在窗口内打过我」这本按攻击者去重的账。
     * 顺带得到一个好处：复仇与受害护盾共用一本账 ⇒ 两条规则对「打过我」的定义不会漂移。
     */
    private boolean isRevenge(String attackerId, String defenderId, long now, Rules rules) {
        long since = now - rules.revengeWindowMillis();
        if (wasAttackedBy(attackerId, defenderId, since)) {
            return true;
        }
        for (String mateId : socialAppService.squadMateIds(attackerId)) {
            if (wasAttackedBy(mateId, defenderId, since)) {
                return true;
            }
        }
        return false;
    }

    /** 玩家的受害账本里是否记着「{@code aggressorId} 在 {@code since} 之后打过我」。 */
    private boolean wasAttackedBy(String playerId, String aggressorId, long since) {
        PlayerSave save = players.findByPlayerId(playerId).orElse(null);
        if (save == null) {
            // 小队成员刚退盟或被删号：查不到就当他没被攻击过。
            // 这里抛异常会让一场合法的复仇被一个无关成员的存档问题毁掉
            return false;
        }
        Long lastHitAt = save.pvp().attackerHits().get(aggressorId);
        return lastHitAt != null && lastHitAt > since;
    }

    /**
     * 围剿：守方的暴虐档位是否触发全服围剿令（{@link Tyranny#triggersCrusade}）。
     *
     * <p><b>必须先补衰减再定档</b>：暴虐值每日衰减 20% 是惰性结算，锚点在 {@code tyrannyTouchedAt}。
     * 直接拿存档里的裸值定档，表现是「三个月前当过公敌的人现在打他还有 +15%」——
     * 而 C01 §3 的全部设计意图是「大佬打得越狠，全服打他的理由越充分」，
     * 一个摘不掉的永久靶子恰好相反：它奖励的是历史而不是现状。
     */
    private boolean isCrusade(String defenderId, long now, Rules rules) {
        PlayerSave save = players.findByPlayerId(defenderId).orElse(null);
        if (save == null) {
            throw new IllegalStateException("围剿判定读不到守方存档 defender=" + defenderId
                    + "：攻击一个不存在的玩家本身已经是链路问题，静默按 0 加成会把「档位判定失效」伪装成「他不该打」");
        }
        PlayerPvp pvp = save.pvp();
        long current = Tyranny.decay(pvp.tyranny(),
                powerService.daysSince(pvp.tyrannyTouchedAt(), now), powerService.tyrannyRules());
        Tyranny.Level level = Tyranny.levelOf(current, powerService.tyrannyRules());
        if (!Tyranny.triggersCrusade(level)) {
            return false;
        }
        LOG.info("围剿令生效 attacker 侧 +{}（定点）target={} 暴虐={} 档位={}",
                rules.crusadeFixed(), defenderId, current, level);
        return true;
    }
}
