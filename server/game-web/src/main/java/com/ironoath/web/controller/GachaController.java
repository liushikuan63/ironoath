package com.ironoath.web.controller;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.web.dto.generated.GachaDrawReq;
import com.ironoath.web.dto.generated.GachaDrawResp;
import com.ironoath.web.dto.generated.GachaProbResp;
import com.ironoath.web.service.GachaAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责：抽卡域 HTTP 入口（B06 §1/§6）。
 * 依赖：Spring Web、GachaAppService。
 *
 * <p><b>{@code /gacha/probability} 是合规端点，不是普通查询接口</b>：
 * B06 §6 要求「客户端概率面板必须读同一份配置，不能客户端写死一份，否则一定会对不上」，
 * 禁止项里又写了一遍「不要将抽卡概率写死在代码或客户端」。
 * 这个端点就是那句话的落地 —— 面板上的每一个数字都从这里来，
 * 而这里读的与服务端抽取时用的是同一张 gacha 表。
 *
 * <p>它因此<b>不需要身份头</b>：概率公示要对未登录玩家也可见（合规要求「抽卡界面必须原文呈现」，
 * 而玩家可能在建号前就想看概率）。这不是漏掉鉴权，是刻意的。
 */
@RestController
@RequestMapping("/gacha")
public class GachaController {

    private final GachaAppService gachaAppService;

    public GachaController(GachaAppService gachaAppService) {
        this.gachaAppService = gachaAppService;
    }

    /**
     * 概率公示。返回<b>公示概率</b>（综合概率，含保底）与逐武将概率、保底规则、公示原文。
     *
     * <p>{@code disclosureText} 必须原样展示：gacha 表里明写
     * 「客户端抽卡界面必须原文呈现，不得删减、折叠或以图标替代」。
     */
    @GetMapping("/probability")
    public Result<GachaProbResp> probability(@RequestParam("poolId") String poolId) {
        return Result.ok(gachaAppService.probability(poolId));
    }

    /**
     * 抽卡。count 只能是 1 或 10（B06 §2）。
     *
     * <p>响应里回 {@code seed}：配合 count 与保底进度可以完整复现这批结果
     * （B06 验收 11）。种子由服务端生成，与任何请求字段无关 ——
     * 否则玩家可以离线枚举出哪个种子出 SSR 再拿它发请求，
     * 「服务端计算」就退化成了「玩家挑选结果」。
     */
    @PostMapping("/draw")
    public Result<GachaDrawResp> draw(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                      @RequestBody GachaDrawReq req) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
        return Result.ok(gachaAppService.draw(playerId, req));
    }

    /**
     * 最近 50 次抽取记录（B15 §三 合规要求）。
     *
     * <p>与 {@code /gacha/probability} 是一对：一个公示概率、一个提供可验证的证据。
     * 只有前者没有后者，公示就成了「请相信我」。
     */
    @GetMapping("/history")
    public Result<com.ironoath.web.dto.generated.GachaHistoryResp> history(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
        return Result.ok(gachaAppService.history(playerId));
    }
}
