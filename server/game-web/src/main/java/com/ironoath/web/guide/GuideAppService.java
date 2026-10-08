package com.ironoath.web.guide;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.guide.GuideScript;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerGuide;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.web.battle.BattleReportStore;
import com.ironoath.web.dto.generated.GuideProgressReq;
import com.ironoath.web.dto.generated.GuideProgressResp;
import com.ironoath.web.dto.generated.GuideScriptResp;
import com.ironoath.web.quest.QuestAppService;

/**
 * 职责：新手引导的两个用例 —— 下发脚本（含续传位置与入口闸门）、推进一步（B18）。
 * 依赖：{@link GuideRulesAssembler}（表 → 脚本）、{@link QuestAppService}（完成判定的唯一数据源）、
 *       玩家仓储（进度就住在存档上）、战报存储（闸门的一半）、玩家锁与幂等、{@link TimeService}。
 *
 * <p><b>本类不判「做没做到」，它只去问</b>：一步达成没有是读任务账本得出的（{@link #judgeReached}），
 * 而推进/拒绝的规则全在 {@link GuideScript} 里。客户端上报的 {@code COMPLETE} 在这里的待遇与
 * 一次「请帮我查一下」相同 —— 这是 B00 铁律 3 在引导这一档的具体形状（验收 5）。
 *
 * <p><b>进度写存档，不写本地、也不新开一张表</b>（B18 §一.2）：{@code PlayerSave.guide} 只有两位，
 * 换设备/清缓存都不该从头再来；而写存档要走 {@link PlayerRepository#save} 的乐观锁，
 * 所以推进整段包在 {@code playerLock} 里 —— 与城建、训练改同一份存档的两个路径不互相覆盖。
 *
 * <p><b>引导不发奖</b>（B18 禁止项）：本类没有任何 {@code RewardService} 依赖，
 * 这是刻意的 —— 第二条发奖口的后果不是报错，是同一份奖励有两个来源、对不上账。
 */
@Service
public class GuideAppService {

    private static final Logger LOG = LoggerFactory.getLogger(GuideAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final ConfigRegistry configs;
    private final GuideRulesAssembler assembler;
    private final QuestAppService quests;
    private final PlayerRepository players;
    private final BattleReportStore reports;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;

    public GuideAppService(ConfigRegistry configs,
                          GuideRulesAssembler assembler,
                          QuestAppService quests,
                          PlayerRepository players,
                          BattleReportStore reports,
                          PlayerLock playerLock,
                          IdempotencyStore idempotency,
                          TimeService timeService) {
        this.configs = configs;
        this.assembler = assembler;
        this.quests = quests;
        this.players = players;
        this.reports = reports;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
    }

    /**
     * 下发脚本：全部步骤 + 版本 + 这个玩家该从哪一步接着做 + 这个账号还该不该看引导。
     *
     * <p><b>只读，不写存档</b>：读一次就把「从未开始」写成「第 1 步」看似无害，实际是让登录路径
     * 多一个存档写入者，而那个写入会在老号上凭空长出一位「走过引导」的记录。
     */
    public GuideScriptResp script(String playerId) {
        PlayerSave save = requireSave(playerId);
        GuideScript script = assembler.script();
        PlayerGuide guide = save.guide();
        boolean applies = applies(save, guide);
        return new GuideScriptResp(assembler.views(), assembler.version(),
                nextStepIndexOf(guide, script, applies), applies, timeService.serverNow());
    }

    /**
     * 续传位置。四种情况各说各的：
     * 已结束 → null（客户端收起引导层）；已开始 → 存档那一号；从未开始且该看 → 第 1 步；
     * 从未开始且不该看（老号）→ null。
     *
     * <p><b>「已开始」优先于闸门</b>：一个正在走引导的玩家升到 4 级之后被闸门挡住，
     * 表现是引导在半路凭空消失、而第 5~7 步永远没人教他 —— 闸门管的是「要不要开始」，不是「要不要放弃」。
     */
    private static Long nextStepIndexOf(PlayerGuide guide, GuideScript script, boolean applies) {
        if (guide.finished()) {
            return null;
        }
        if (guide.started()) {
            return (long) guide.stepIndex();
        }
        return applies ? 1L : null;
    }

    /**
     * 上报一步的结果并推进。
     *
     * <p>幂等键与任务/活动领奖同一条要求：弱网重投不该把两步并作一步（也绝不该把一次推进变成两次）。
     */
    public GuideProgressResp progress(String playerId, GuideProgressReq req) {
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> advance(playerId, req));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private GuideProgressResp advance(String playerId, GuideProgressReq req) {
        PlayerSave save = requireSave(playerId);
        GuideScript script = assembler.script();
        GuideScript.Outcome outcome = script.advance(save.guide(), req.stepId(),
                actionOf(req), judgeReached(playerId, script, req.stepId()));

        switch (outcome.status()) {
            case STEP_NOT_FOUND -> throw new BizException(ErrorCode.GUIDE_STEP_NOT_FOUND,
                    "脚本里没有步骤 " + req.stepId() + "（当前脚本版本 " + assembler.version()
                            + "）：客户端手里的是旧脚本，重新拉一次即可");
            case NOT_SKIPPABLE -> {
                // 步骤 id 只进日志、不进文案（红线「屏上不出现内部 id」，台账 #821）：
                // 玩家侧说清"是哪一步"靠序号，客服侧靠下面这行日志里的 stepId。
                String notSkippable = stepById(script, req.stepId())
                        .map(s -> "第 " + s.stepIndex() + " 步是必须做的，不能跳过")
                        .orElse("这一步不能跳过");
                LOG.info("引导强制步被请求跳过 玩家={} 步骤={}", playerId, req.stepId());
                throw new BizException(ErrorCode.GUIDE_STEP_NOT_SKIPPABLE, notSkippable);
            }
            case OUT_OF_ORDER -> throw new BizException(ErrorCode.GUIDE_STEP_OUT_OF_ORDER,
                    "当前该做第 " + outcome.stepIndex() + " 步，上报的却是 " + req.stepId()
                            + "：引导必须按序推进");
            case HELD -> {
                // 判据没达成是正常路径：留在这一步等玩家，不报错（错误码里刻意没有这一位）
                return new GuideProgressResp(false, false, (long) outcome.stepIndex());
            }
            case ALREADY_FINISHED -> {
                return new GuideProgressResp(false, true, null);
            }
            case ADVANCED -> {
                save.setGuide(new PlayerGuide(outcome.stepIndex(), null));
                players.save(save);
                return new GuideProgressResp(true, false, (long) outcome.stepIndex());
            }
            case FINISHED -> {
                long end = timeService.serverNow();
                save.setGuide(new PlayerGuide(outcome.stepIndex(), end));
                players.save(save);
                LOG.info("引导走完 playerId={} 结束于第 {} 步@{}", playerId, outcome.stepIndex(), end);
                return new GuideProgressResp(true, true, null);
            }
            default -> throw new IllegalStateException("未映射的引导推进结果：" + outcome.status());
        }
    }

    /**
     * 完成判定：<b>读任务账本</b>，不读客户端说了什么。
     *
     * <p>上报的 stepId 查不到（旧脚本）时返回 false：让 {@code GuideScript} 去报
     * {@code STEP_NOT_FOUND}，而不是在这里判一个不存在的目标 —— 那会先抛出一个「任务不存在」。
     */
    private boolean judgeReached(String playerId, GuideScript script, String stepId) {
        GuideScript.Step step = stepById(script, stepId).orElse(null);
        if (step == null) {
            return false;
        }
        Optional<QuestProgress.Entry> entry = quests.entryOf(playerId, step.judgeTarget());
        if (entry.isEmpty()) {
            // 外键已由启动期校验过，走到这里说明表改了而进程没重载：按"未达成"处理，
            // 表现是引导停在这一步（可恢复），而不是把一个提示功能变成一次 500
            LOG.warn("引导步骤 {} 的判据任务 {} 不在账本里，按未达成处理", step.id(), step.judgeTarget());
            return false;
        }
        return switch (step.judge()) {
            case QUEST_DONE -> entry.get().complete();
            case QUEST_CLAIMED -> entry.get().claimed();
        };
    }

    /** 按 id 查一步（找不到就是脚本里没有这一步）。 */
    private Optional<GuideScript.Step> stepById(GuideScript script, String stepId) {
        return script.byId(stepId);
    }

    /**
     * 入口闸门（B18 §五③：只对新号）。
     *
     * <p>三段判定，顺序有意义：走完了 → 不再打扰；<b>开始过 → 一律续得下去</b>（等级与战报都会变，
     * 拿它们回头否决一个正在走引导的人，等于让引导在半路消失）；从没开始 → 才看那两条新号特征。
     *
     * <p><b>「无出征记录」用的是近似</b>：裁决原文那句里的这个字段在现状并不存在 —— 行军完成即从
     * {@code MarchRepository} 删除，存档上也没有出兵计数。这里取「该号一条战报都没有」：
     * 打过仗必然有战报，所以它在新号首日与「没出过征」同向；漏网的是
     * 「只派兵采集、从没打仗、又停在等级上界之下」的老号。偏差已记进
     * {@code guide.json} 的 designNote 与收口清单，等一次裁决（要不要给存档加一位真「首次出兵时刻」）。
     */
    private boolean applies(PlayerSave save, PlayerGuide guide) {
        if (guide.finished()) {
            return false;
        }
        if (guide.started()) {
            return true;
        }
        long levelCap = configs.longParam("GUIDE_APPLIES_CITY_LEVEL_MAX");
        return save.cityLevel() <= levelCap && reports.reportsOf(save.playerId()).isEmpty();
    }

    /** 协议枚举 → core 枚举。穷尽 switch：加一个取值就要在这里表态。 */
    private static GuideScript.Action actionOf(GuideProgressReq req) {
        return switch (req.action()) {
            case COMPLETE -> GuideScript.Action.COMPLETE;
            case SKIP -> GuideScript.Action.SKIP;
        };
    }

    private PlayerSave requireSave(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow(
                () -> new BizException(ErrorCode.PLAYER_NOT_FOUND, "玩家不存在：" + playerId));
    }

    /** 幂等键：缺键回 1003、重复回 1002，TTL 读配置表（与任务/活动同一条）。 */
    private void acquire(String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "上报引导进度必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "requestId=" + requestId + " 已经用过了：同一次上报重投不会把两步并作一步");
        }
    }
}
