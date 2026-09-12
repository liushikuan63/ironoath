package com.ironoath.web.service;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityState;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerPvp;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.power.PowerCalculator;
import com.ironoath.web.dto.generated.PowerBreakdown;
import com.ironoath.web.dto.generated.PowerDetailResp;
import com.ironoath.web.dto.generated.PowerSnapshot;
import com.ironoath.web.power.PowerChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 职责：重算并写回玩家战力，变更时发布 {@link PowerChangedEvent}（B08 §1）。
 * 依赖：{@link CityAppService}（加锁 + 惰性结算）、军队/武将仓储、{@link PowerService}（口径装配）。
 *
 * <p><b>为什么需要这么一个专门的入口</b>：战力是<b>派生值</b>，由城建、部队、武将三份存档算出来。
 * 派生值有两种维护方式 —— 每个改动它的写路径都顺手更新一次，或者在一个统一的地方重算。
 * 前者在本项目里有二十多个改动点（建造、升级、加速、取消、训练、治疗、升级武将、升星、觉醒、
 * 技能、装备、编队、抽卡……），漏掉任何一个，玩家就会看到「我练了兵，战力没变」，
 * 而这种 bug 不会让任何测试变红。所以选后者：一个入口，谁改完状态谁调它。
 *
 * <p><b>调用点是 HTTP 边界上的拦截器</b>（{@code PowerRefreshInterceptor}），
 * 而不是散落在各个 service 里。理由同上：新增一个会改变战力的端点时，
 * 拦截器自动覆盖它，不需要写代码的人记得「还要更新战力」。
 *
 * <p><b>走 {@link CityAppService#withSettledCity}，因此天然带玩家锁与惰性结算</b>。
 * 这一条很关键：刚完成的建筑升级只在结算时才会把等级落到 CityState 上，
 * 不结算就读原始存档，算出来的战力会漏掉那次升级 —— 玩家升级完立刻看战力，
 * 看到的是一个没有变化的数字，而这恰恰是他最想看到变化的时刻。
 */
@Service
public class PowerRefreshService {

    private static final Logger LOG = LoggerFactory.getLogger(PowerRefreshService.class);

    private final CityAppService cityAppService;
    private final ArmyAppService armyAppService;
    private final HeroRepository heroes;
    private final PowerService powerService;
    private final ApplicationEventPublisher events;
    /**
     * 赛季实时榜上报。
     *
     * <p>依赖方向是安全的：赛季服务不回依赖战力（结算时的段位是按存档里的 matchPower 现算的），
     * 所以这里注入它不会成环。
     */
    private final com.ironoath.web.season.SeasonSettlementService seasons;

    public PowerRefreshService(CityAppService cityAppService, ArmyAppService armyAppService,
                               HeroRepository heroes, PowerService powerService,
                               ApplicationEventPublisher events,
                               com.ironoath.web.season.SeasonSettlementService seasons) {
        this.cityAppService = cityAppService;
        this.armyAppService = armyAppService;
        this.heroes = heroes;
        this.powerService = powerService;
        this.events = events;
        this.seasons = seasons;
    }

    /**
     * 重算并写回一名玩家的战力。
     *
     * @return 重算结果（无论战力是否发生变化都返回）
     */
    public Refreshed refresh(String playerId) {
        return cityAppService.withSettledCity(playerId, snap -> {
            PlayerSave save = snap.player();
            CityState city = snap.city();
            long now = snap.now();
            // 军队必须先收割：到点的训练/治疗是惰性状态，直接读仓储会漏掉刚训完的兵，
            // 于是玩家训完兵立刻看战力会看到一个没变化的数字（见 ArmyAppService.settledArmy）
            ArmyState army = armyAppService.settledArmy(playerId, now);
            // 武将没有惰性队列，读不到就用空对象而不是建一条：
            // 为一个只读的重算去写一份空存档，会在玩家还没碰过武将系统时就留下垃圾数据
            HeroRoster roster = heroes.findByPlayerId(playerId).orElseGet(HeroRoster::new);

            PlayerPvp pvp = save.pvp();
            int days = powerService.daysSince(pvp.peakTouchedAt(), now);
            PowerCalculator.Result result = powerService.powerOf(
                    city, army, roster, save.power().peakPower(), days);
            PlayerPower next = new PlayerPower(
                    result.displayPower(), result.matchPower(), result.peakPower());
            long currentMatch = powerService.matchPowerOf(army, roster);
            if (next.equals(save.power())) {
                return new Refreshed(next, result, currentMatch, now);
            }

            save.setPower(next);
            // 衰减锚点：峰值来自当前战力就重置为 now（峰值是刚刚创下的）；
            // 来自衰减后的旧峰值则只推进整天数，把不足一天的余数留到下次 ——
            // 直接设成 now 的话，一天上线两次的玩家永远算不满一天，峰值永不衰减
            long anchor = result.peakFromCurrent() || pvp.peakTouchedAt() <= 0L
                    ? now
                    : powerService.advanceWholeDays(pvp.peakTouchedAt(), days);
            save.setPvp(pvp.withPeakTouchedAt(anchor));

            // 事件在锁内发布、在 save 落库之前：MatchPool 可能短暂持有一个最终没写成功的值。
            // 这是可接受的 —— 池只是缓存，搜索读不到就回落到存档，下一次 refresh 会纠正它。
            // 换成「落库后再发」需要把发布挪出 withSettledCity，那会失去「变更与快照同源」的保证
            events.publishEvent(new PowerChangedEvent(playerId, next.displayPower(),
                    next.matchPower(), next.peakPower(), result.breakdown(),
                    result.peakRaised(), now));
            // 赛季实时榜跟着战力走：不上榜的话，结算时榜上还是玩家上次变更前的数字，
            // 而那场结算恰恰是按这份榜发奖的 —— 少一次上报就是少一个人有名次。
            // 直接调用而不是挂事件：昵称此刻就在手里（snap.player()），
            // 走监听器要多读一次存档，而这条路径每个写请求都会走
            seasons.report(playerId, save.nickName(), next.matchPower());
            LOG.info("战力已更新 playerId={} 展示={} 匹配={} 峰值={} 明细[建筑={} 部队={} 武将={}] 峰值抬高={}",
                    playerId, next.displayPower(), next.matchPower(), next.peakPower(),
                    result.breakdown().building(), result.breakdown().troops(),
                    result.breakdown().heroes(), result.peakRaised());
            return new Refreshed(next, result, currentMatch, now);
        });
    }

    /**
     * 战力明细面板的数据（B08 §1：UI 必须能点开看明细）。
     *
     * <p><b>把 currentMatchPower 与 peakMemoryFloor 一起下发</b>，
     * 是为了让 matchPower 能被玩家自己复算出来：matchPower = max(两者)。
     * 只给结果的话，一个刚打完大仗、兵力折损的玩家会看到一个比部队实际战力更高的数字，
     * 而他没有任何线索知道那是峰值记忆在起作用 —— 那正是「这游戏在骗我」的观感来源。
     * 把两个输入摊开，规则就变成可自查的，而不是需要相信的。
     */
    public PowerDetailResp detail(String playerId) {
        Refreshed refreshed = refresh(playerId);
        PowerCalculator.Result result = refreshed.result();
        long floor = FixedPoint.round(FixedPoint.mul(
                FixedPoint.of(result.peakPower()), powerService.powerRules().memoryRatioFixed()));
        return new PowerDetailResp(
                new PowerSnapshot(result.displayPower(), result.matchPower(), result.peakPower()),
                refreshed.currentMatchPower(),
                floor,
                new PowerBreakdown(result.breakdown().building(), result.breakdown().troops(),
                        result.breakdown().heroes(), result.breakdown().tech(),
                        result.breakdown().equipment()),
                refreshed.now());
    }

    /**
     * 一次重算的完整结果。
     *
     * @param power             写回存档的战力三元组
     * @param result            计算明细（含 breakdown 与峰值标记）
     * @param currentMatchPower 当前部队 + 上阵主将的实际战力，不含峰值记忆
     * @param now               本次使用的服务端时刻
     */
    public record Refreshed(PlayerPower power,
                            PowerCalculator.Result result,
                            long currentMatchPower,
                            long now) {

        public Refreshed {
            if (power == null || result == null) {
                throw new IllegalArgumentException("power 与 result 都不得为 null");
            }
        }

        /** 便捷读取匹配战力 —— 圈层判定的唯一依据。 */
        public long matchPower() {
            return power.matchPower();
        }
    }
}
