package com.ironoath.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.RankListResp;
import com.ironoath.web.dto.generated.RankSnapshotResp;
import com.ironoath.web.dto.generated.RankType;
import com.ironoath.web.rank.RankBoardService;

/**
 * 职责：排行榜 HTTP 入口（B23 §一 1）。
 * 依赖：Spring Web、{@link RankBoardService}。
 *
 * <p><b>两个端点都是 GET</b>：榜是纯读，不改变任何状态（"换日了补一份快照"那件事在 S2，
 * 且它按 B14 的惰性先例挂在读路径上，不额外开写端点）。
 *
 * <p><b>认不出的榜类型回参数错误</b>（而不是 500 或空榜）：一个拼错的 type 给一张空榜，
 * 客户端会把它画成"这个榜还没有人"——而实际是名字写错了。
 */
@RestController
@RequestMapping("/rank")
public class RankController {

    private final RankBoardService ranks;

    public RankController(RankBoardService ranks) {
        this.ranks = ranks;
    }

    /** 一页榜。page 从 1 起，超出范围会被夹到最后一页（而不是回空页）。 */
    @GetMapping("/list")
    public Result<RankListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                     @RequestParam("type") String type,
                                     @RequestParam(name = "page", defaultValue = "1") int page) {
        requirePlayer(playerId);
        return Result.ok(ranks.list(playerId, parseType(type), page));
    }

    /** 我的名次（未上榜时 myRank 为 null）。回的那一页是**我所在的那一页**。 */
    @GetMapping("/me")
    public Result<RankListResp> me(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestParam("type") String type) {
        requirePlayer(playerId);
        return Result.ok(ranks.me(playerId, parseType(type)));
    }

    /**
     * 某一天的每日快照里**我的那一行**（B23 §一 2：申诉读取）。
     *
     * <p><b>只回自己</b>（裁决③）：全服历史名次是情报，运营要全量走 {@code /ops/rank/snapshot}。
     * 所以这个响应里没有 entries 字段 —— 不是"隐藏了"，而是这个端点从不持有别人的行。
     */
    @GetMapping("/snapshot")
    public Result<RankSnapshotResp> snapshot(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestParam("type") String type,
            @RequestParam("dayKey") String dayKey) {
        requirePlayer(playerId);
        return Result.ok(ranks.snapshot(playerId, parseType(type), dayKey));
    }

    private static RankType parseType(String raw) {
        return RankBoardService.parseType(raw);
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
