package com.ironoath.web.service;

import com.ironoath.common.time.TimeService;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.resource.ResourceOutputCalculator;
import com.ironoath.web.dto.generated.OutputBreak;
import com.ironoath.web.dto.generated.ResourceDetail;
import com.ironoath.web.dto.generated.ResourceDetailResp;
import com.ironoath.web.dto.generated.ResourceType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 职责：资源明细面板的应用服务（B04 §2，验收 5）。
 * 依赖：{@link CityAppService}（在锁内完成结算并交出它用的那份产率）、game-common（时间）。
 *
 * <p><b>本类不做任何计算，只做映射。</b>这是刻意的：验收 5 要求「明细各项之和 = 实际每小时产出，
 * 误差 0」，而唯一能保证误差为 0 的办法是让这两个数字来自同一次计算。
 * 所以产率由 {@link CityAppService#settleAndSnapshot} 在锁内算好交出来，
 * 本类只负责把 {@code ResourceOutputCalculator.Breakdown} 翻译成协议对象。
 * 一旦这里再算一遍（哪怕公式完全相同），两次计算之间发生一次收割就会让面板与存档分叉 ——
 * 而这个面板是 B04 明说的「转化关键 UI」，它算错会直接摧毁玩家对数值的信任。
 *
 * <p>依赖方向是 resource → city 而不是反过来：产率的唯一来源是城建状态（哪些建筑、几级、
 * 是否在升级），资源域自己没有这个信息。
 */
@Service
public class ResourceAppService {

    private final CityAppService cityAppService;
    private final TimeService timeService;

    public ResourceAppService(CityAppService cityAppService, TimeService timeService) {
        this.cityAppService = cityAppService;
        this.timeService = timeService;
    }

    /**
     * 资源明细。这是个有副作用的「读」：它会顺带完成惰性结算与到点收割，
     * 因为不结算就拿不到「此刻」的存量，也就无法判断是否满仓。
     */
    public ResourceDetailResp detail(String playerId) {
        ResourceRateService.Settlement settlement = cityAppService.settleAndSnapshot(playerId);
        List<ResourceDetail> details = new ArrayList<>();
        for (Map.Entry<String, PlayerResourceState> e : settlement.states().entrySet()) {
            String id = e.getKey();
            PlayerResourceState s = e.getValue();
            ResourceOutputCalculator.Breakdown breakdown = settlement.rates().breakdowns().get(id);
            details.add(new ResourceDetail(
                    ResourceType.valueOf(id),
                    s.current(), s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle(),
                    // 满仓即停产（B04 验收 1/11）：结算已经把 current 封在 cap 上，
                    // 所以「等于 cap」就是「正在停产」，不需要另设标志位
                    s.current() >= s.cap(),
                    toBreaks(breakdown)));
        }
        return new ResourceDetailResp(details, timeService.serverNow());
    }

    /** 明细行转协议对象。顺序保持不变 —— 面板要按「底产 → 各建筑 → 三个加成」展示。 */
    private static List<OutputBreak> toBreaks(ResourceOutputCalculator.Breakdown breakdown) {
        List<OutputBreak> out = new ArrayList<>(breakdown.lines().size());
        for (ResourceOutputCalculator.Line line : breakdown.lines()) {
            out.add(new OutputBreak(line.source(), line.amount(), line.isPercent(),
                    line.isPercent() ? line.percentFixed() : null));
        }
        return out;
    }
}
