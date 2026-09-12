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
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationDiplomacyReq;
import com.ironoath.web.dto.generated.NationDiplomacyResp;
import com.ironoath.web.dto.generated.NationDisbandReq;
import com.ironoath.web.dto.generated.NationDisbandResp;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationJoinReq;
import com.ironoath.web.dto.generated.NationLeaveReq;
import com.ironoath.web.dto.generated.NationLeaveResp;
import com.ironoath.web.dto.generated.NationResp;
import com.ironoath.web.dto.generated.NationTreasuryResp;
import com.ironoath.web.dto.generated.NationTreasurySpendReq;
import com.ironoath.web.dto.generated.NationTreasurySpendResp;
import com.ironoath.web.service.NationAppService;

/**
 * 职责：国家域 HTTP 入口（B13）—— 建国、联盟入籍与退出国、解散国家、任命官职、外交、查看本国与国库流水。
 * 依赖：Spring Web、{@link NationAppService}。
 *
 * <p><b>写操作都返回操作后的完整视图</b>而不是只回 ok：客户端据此刷新面板，
 * 不需要再发一次查询 —— 少一次往返在弱网下就是少一次超时。
 * 两个例外是 {@code /leave} 与 {@code /disband}：那两次操作之后调用方已经没有国家可看了，
 * 回一份他无权查询的视图是假动作，所以各回自己那件最该被显示的事实（何时能再入籍 / 亡国的审计四件套）。
 *
 * <p><b>还缺的端点</b>：国策投票、国战状态。
 * 各自的领域前置未就位（国策需要提案表、国战需要与 B07 地图和 B10 集结接线并压测；
 * 国库支出已在 2026-09-11 接通 —— 见 /treasury/spend）。
 * B13 禁止项明写「不要在没有压测的情况下上线王城战」，所以那几个端点刻意不先开出来 ——
 * 一个能调用但结算不了的宣战接口，比没有这个接口更危险。
 */
@RestController
@RequestMapping("/nation")
public class NationController {

    private final NationAppService nations;

    public NationController(NationAppService nations) {
        this.nations = nations;
    }

    /** 建国。前置（主城 16 级 / 开服 D14 / 在联盟中）全部由服务端校验。 */
    @PostMapping("/found")
    public Result<NationResp> found(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                    @RequestBody NationFoundReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.found(playerId, req));
    }

    /**
     * 联盟入籍：盟主代表全盟加入指定的国家。
     *
     * <p>发起人身份按「你是不是这个盟的盟主」判定，<b>不查 role_permission</b> ——
     * 入籍那一刻他还不在目标国，任何国家侧官职都无从谈起，而那张表里也没有 JOIN_NATION 位。
     * 能不能加入（冷却、名额、是否已属他国）全部由领域层判，见 {@code Nation.admitBlockFor}。
     */
    @PostMapping("/join")
    public Result<NationResp> join(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                   @RequestBody NationJoinReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.join(playerId, req));
    }

    /** 联盟退出国：盟主代表全盟退出所属国家，全盟失去国籍并进入入籍冷却（B13 验收 2）。 */
    @PostMapping("/leave")
    public Result<NationLeaveResp> leave(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                         @RequestBody NationLeaveReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.leave(playerId, req));
    }

    /**
     * 解散国家。只有国王能成功（领域层判的就是这个身份），成功后所有成员联盟进入入籍冷却。
     *
     * <p>回的是审计事实（哪个国、叫什么、带着几个联盟、核销了多少钱）而不是国家视图 ——
     * 那一刻已经没有国家可看了。
     */
    @PostMapping("/disband")
    public Result<NationDisbandResp> disband(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody NationDisbandReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.disband(playerId, req));
    }

    /** 任命官职。权限走 role_permission 表，且 Bot 会被合规闸门拒绝。 */
    @PostMapping("/appoint")
    public Result<NationResp> appoint(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                      @RequestBody NationAppointReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.appoint(playerId, req));
    }

    /**
     * 变更外交关系。权限位 MANAGE_DIPLOMACY（国王与外交官档）。
     *
     * <p>关系会立刻改变 {@code mayAttackNation} 的结果，所以这不是一个装饰性接口。
     */
    @PostMapping("/diplomacy")
    public Result<NationDiplomacyResp> diplomacy(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                                 @RequestBody NationDiplomacyReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.diplomacy(playerId, req));
    }

    /** 我的国家。不在任何国家里时回「不存在」。 */
    @GetMapping
    public Result<NationResp> view(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(nations.view(playerId));
    }

    /**
     * 国库流水（B13 §3 验收 5）。本国任一成员联盟的成员都能读 ——
     * 这本账的存在理由就是让成员看得见，只给国王看的日志等于把审计权交给被审计的人。
     */
    @GetMapping("/treasury")
    public Result<NationTreasuryResp> treasury(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(nations.treasury(playerId));
    }

    /**
     * 国库支出（B13 §3）。落点分两类：发给某个玩家（俸禄，扣账后走发放器发 GOLD）
     * 或由消耗性用途核销（国家科技 / 国战增益，不入任何个人账户）—— 见协议里那两个枚举。
     *
     * <p>权限走 role_permission 表的 {@code WITHDRAW_TREASURY}（表里只给国主）。
     */
    @PostMapping("/treasury/spend")
    public Result<NationTreasurySpendResp> spendTreasury(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody NationTreasurySpendReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.spendTreasury(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
