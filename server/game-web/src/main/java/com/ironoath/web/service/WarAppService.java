package com.ironoath.web.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.ironoath.common.time.TimeService;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.dto.generated.WarNationScoreView;
import com.ironoath.web.dto.generated.WarPhase;
import com.ironoath.web.dto.generated.WarStatusResp;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;

/**
 * 职责：国战状态的只读视图（B13 §一 §7、B21 §二 的 {@code WarStatusResp}）。
 * 依赖：{@link WarStore}、{@link NationStore}（取国名）、{@link WarRulesAssembler}、{@link TimeService}。
 *
 * <p><b>没有成员关系门槛，全服谁都能读</b>：国战是全服事件，不是某一国的内部事务 ——
 * 与 {@code /nation/treasury} 恰好相反，那本账是公共资产（要防贪污，成员必须看得见），
 * 这本账是公共进度（B13 §7 让不打国战的人也贡献击杀，读不到进度条那条设计就白写）。
 * 这里也不做权限位：{@code role_permission} 表里没有 VIEW_WAR 这一位，凭空加一道只会让人以为
 * 「普通玩家看不到国战进度」是设计意图。
 *
 * <p><b>本类是承载，不是玩法</b>：这一切片没有任何写入路径（击杀累计、疲劳累积、开战与结算都未接线），
 * 所以生产上 {@code findLatest()} 恒空、视图恒回 {@code hasWar=false}。
 * 验收矩阵里 B13 的疲劳值上限与国家集结门槛两条因此继续挂 ⬜，<b>不因为这个端点存在而变</b> ——
 * 把它当「国战通了」的证据是错的，这条边界在 {@code WarStatusResp} 的协议描述里也写了同一句。
 *
 * <p><b>数值一律现取，不缓存</b>：规则来自 {@link WarRulesAssembler}（每次装配一遍，热更立刻生效），
 * 剩余秒数由 {@code now} 现算（服务端禁常驻定时器，{@code check-no-scheduled.sh} 是门禁）。
 */
@Service
public class WarAppService {

    private final WarStore wars;
    private final NationStore nations;
    private final WarRulesAssembler assembler;
    private final TimeService timeService;

    public WarAppService(WarStore wars, NationStore nations,
                         WarRulesAssembler assembler, TimeService timeService) {
        this.wars = wars;
        this.nations = nations;
        this.assembler = assembler;
        this.timeService = timeService;
    }

    /**
     * 国战状态。
     *
     * @param playerId 请求者（{@code X-Player-Id}）。用于算<b>他本人</b>的疲劳与行军闸门 ——
     *                 这两项是按玩家分的，不是全服的，所以身份不是装饰：
     *                 漏掉它等于把甲的疲劳显示成乙的，而乙会以为自己还能再派一批
     */
    public WarStatusResp warStatus(String playerId) {
        long now = timeService.serverNow();
        WarScoreBoard.Rules rules = assembler.rules();
        Optional<WarScoreBoard> latest = wars.findLatest();
        if (latest.isEmpty()) {
            // 无战事：积分与击杀给 0（那是"没有任何事发生过"的真值），而 phase/startedAt/占领者给 null
            // （那三项没有真值可给，填 0 会被读成"1970 年开过一场仗"）。
            // canMarch 在这里是 true —— 疲劳闸门只在国战里生效，没有仗就没有那道闸；
            // "有没有仗"由 hasWar 单独说，一个事实只用一种表示。
            return new WarStatusResp(false, null, null, 0L, rules.gateCount(),
                    null, null, List.of(), 0L, rules.serverGoalKills(), false,
                    0L, rules.fatigueMax(), true, now);
        }
        WarScoreBoard board = latest.get();
        Map<String, WarScoreBoard.Score> scores = board.snapshot();
        List<WarNationScoreView> rows = new ArrayList<>(scores.size());
        for (Map.Entry<String, WarScoreBoard.Score> entry : scores.entrySet()) {
            String nationId = entry.getKey();
            WarScoreBoard.Score score = entry.getValue();
            rows.add(new WarNationScoreView(nationId, nationNameOrNull(nationId),
                    score.occupyScore(), score.killScore(), score.buildingScore(), score.total(),
                    board.gateCount(nationId), board.isQualified(nationId)));
        }
        String capitalHolder = board.capitalHolder();
        return new WarStatusResp(true, toContractPhase(board.phase()), board.startedAt(),
                board.remainingSeconds(now), rules.gateCount(),
                capitalHolder, nationNameOrNull(capitalHolder), List.copyOf(rows),
                board.totalKills(), rules.serverGoalKills(), board.serverGoalReached(),
                board.fatigueOf(playerId), rules.fatigueMax(), board.canMarch(playerId), now);
    }

    /**
     * 国名一律现查、查不到给 null（客户端据此显示「未知国家」，<b>不许回落到裸 id</b>）。
     *
     * <p>为什么不在这里塞一句中文回退语：那份文案属于客户端的本地化表，服务端下发中文
     * 就成了「改一次文案要改服务端」；同 {@code GachaHistory} 的 {@code 未知武将} 一条。
     * 国家可能在战争进行中被解散，那时这一行仍然要出现在积分板上（仗是打过的事实），
     * 所以"查不到"是合法状态而不是 bug。
     */
    private String nationNameOrNull(String nationId) {
        if (nationId == null) {
            return null;
        }
        return nations.findById(nationId).map(Nation::name).orElse(null);
    }

    /**
     * 内核 {@code Phase} → 协议 {@code WarPhase}。
     *
     * <p>用 {@code valueOf(.name())} 而不是 switch：两份枚举必须同名同序，
     * 而这件事由 {@code WarEndpointTest.warPhaseMatchesTheDomainEnum} 断言钉住。
     * 真漂移时那条用例先红，而这里会抛 {@code IllegalArgumentException} —— 响亮，不会静默换个阶段。
     */
    private static WarPhase toContractPhase(WarScoreBoard.Phase phase) {
        return WarPhase.valueOf(phase.name());
    }
}
