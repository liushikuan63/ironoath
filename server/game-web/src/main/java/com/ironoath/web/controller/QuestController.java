package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.dto.generated.QuestClaimResp;
import com.ironoath.web.dto.generated.QuestListResp;
import com.ironoath.web.quest.QuestAppService;

/**
 * 职责：任务域 HTTP 入口（B12 §1）—— 任务面板与领取奖励。
 * 依赖：Spring Web、{@link QuestAppService}。
 *
 * <p><b>只有两个端点</b>：任务的"做"发生在别的端点里（升级、训练、击杀…），
 * 进度由事件总线累加（B12 禁止项：不得轮询），所以这里没有 POST /quest/progress 这类接口 ——
 * 有它就等于让客户端能自己报进度，而那条路一旦存在，任务系统就不再是证据。
 *
 * <p><b>本轮没有客户端面板</b>：端点是服务端的一半（契约见 quest.schema.json 的说明），
 * 面板是另一档客户端工作。
 */
@RestController
@RequestMapping("/quest")
public class QuestController {

    private final QuestAppService quests;

    public QuestController(QuestAppService quests) {
        this.quests = quests;
    }

    /** 任务面板。会跨天/跨周清零每日与每周任务（惰性推进，不跑定时器）。 */
    @GetMapping("/list")
    public Result<QuestListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(quests.list(playerId));
    }

    /** 领取一条已完成任务的奖励。未完成 / 已领取 / 前置未解锁各有各的业务码。 */
    @PostMapping("/claim")
    public Result<QuestClaimResp> claim(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody QuestClaimReq req) {
        requirePlayer(playerId);
        return Result.ok(quests.claim(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
