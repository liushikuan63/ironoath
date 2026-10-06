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
import com.ironoath.web.dto.generated.NationRelationsResp;
import com.ironoath.web.dto.generated.NationJoinReq;
import com.ironoath.web.dto.generated.NationLeaveReq;
import com.ironoath.web.dto.generated.NationLeaveResp;
import com.ironoath.web.dto.generated.NationResp;
import com.ironoath.web.dto.generated.NationPolicyProposeReq;
import com.ironoath.web.dto.generated.NationPolicyProposeResp;
import com.ironoath.web.dto.generated.NationPolicyRoundView;
import com.ironoath.web.dto.generated.NationPolicyVoteReq;
import com.ironoath.web.dto.generated.NationPolicyVoteResp;
import com.ironoath.web.dto.generated.NationTechListView;
import com.ironoath.web.dto.generated.NationTechResearchReq;
import com.ironoath.web.dto.generated.NationTechResearchResp;
import com.ironoath.web.dto.generated.NationTreasuryResp;
import com.ironoath.web.dto.generated.NationTreasurySpendReq;
import com.ironoath.web.dto.generated.NationTreasurySpendResp;
import com.ironoath.web.dto.generated.WarCooldownsResp;
import com.ironoath.web.dto.generated.WarDeclareReq;
import com.ironoath.web.dto.generated.WarGoalClaimReq;
import com.ironoath.web.dto.generated.WarGoalClaimResp;
import com.ironoath.web.dto.generated.WarStatusResp;
import com.ironoath.web.service.NationAppService;
import com.ironoath.web.service.WarAppService;

/**
 * 职责：国家域 HTTP 入口（B13）—— 建国、联盟入籍与退出国、解散国家、任命官职、外交、查看本国与国库流水、国策、国战状态。
 * 依赖：Spring Web、{@link NationAppService}。
 *
 * <p><b>写操作都返回操作后的完整视图</b>而不是只回 ok：客户端据此刷新面板，
 * 不需要再发一次查询 —— 少一次往返在弱网下就是少一次超时。
 * 两个例外是 {@code /leave} 与 {@code /disband}：那两次操作之后调用方已经没有国家可看了，
 * 回一份他无权查询的视图是假动作，所以各回自己那件最该被显示的事实（何时能再入籍 / 亡国的审计四件套）。
 *
 * <p><b>国战这一侧现在只有读口</b>（{@code GET /nation/war}，2026-10-06 的承载切片 1）：
 * 存储端口、内存与 Mongo 两套实现、装配点与这份视图齐了，但<b>没有任何写入路径</b> ——
 * 击杀累计、疲劳累积、开战与结算都还没接线，所以视图在生产上恒回 {@code hasWar=false}。
 * 还缺的写侧刻意不先开出来：B13 禁止项明写「不要在没有压测的情况下上线王城战」
 * （B21 验收 10 要的是一份 5000 在线 / 行军 200 QPS 的压测报告），
 * 而一个能调用但结算不了的宣战接口，比没有这个接口更危险。
 * 国策投票已在 2026-09-30 接通（{@code /policy/*} 三个端点），不再属于「还缺」。
 */
@RestController
@RequestMapping("/nation")
public class NationController {

    private final NationAppService nations;
    private final WarAppService wars;

    public NationController(NationAppService nations, WarAppService wars) {
        this.nations = nations;
        this.wars = wars;
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

    /**
     * 本国的关系表（只读）。
     *
     * <p><b>为什么要有这个读口</b>：那张表原先只在「改关系」那一次写入时回，于是别的写入改了关系之后
     * 客户端手里那份就旧了 —— 最典型的是宣战（把对目标国那一行置成 HOSTILE）。宣完战切到外交页，
     * 屏上还是旧关系，读起来就是「宣战了却没敌对」。
     */
    @GetMapping("/relations")
    public Result<NationRelationsResp> relations(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(nations.relations(playerId));
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
     * <p>权限走 role_permission 表的 {@code WITHDRAW_TREASURY}（2026-09-13 的 C16 裁决把这一格
     * 从"只给国主"放开到 OFFICER 档，代价与限额都写在那一行的 why 里）。
     */
    @PostMapping("/treasury/spend")
    public Result<NationTreasurySpendResp> spendTreasury(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody NationTreasurySpendReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.spendTreasury(playerId, req));
    }

    /**
     * 国家科技整棵树 + 当前账本 + 国库余额（B20 块③）。
     *
     * <p>本国任一成员都能读，与 {@code /nation/treasury} 同一条理由：花的是公共钱，
     * 看得见才是这本账的存在理由；只给官员看的面板等于把审计权交给被审计的人。
     */
    @GetMapping("/tech")
    public Result<NationTechListView> nationTech(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(nations.nationTech(playerId));
    }

    /** 研究一级国家科技（国库出资，走 {@code sink:NATIONAL_TECH} 核销）。谁能点由 role_permission 决定。 */
    @PostMapping("/tech/research")
    public Result<NationTechResearchResp> researchNationTech(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody NationTechResearchReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.researchNationTech(playerId, req));
    }

    // ---------- 国策（B13 §4，2026-09-30） ----------

    /**
     * 国策轮次的完整视图：当前处在哪一段、本轮有哪些提案、哪些正在生效、什么时候开下一轮。
     *
     * <p><b>一次给全而不是分三个端点</b>：面板本来就要同时显示「当前国策」与「本轮提案」，
     * 分两次查会得到两个时刻的数（提案刚被投掉、面板还挂着上一份）。
     *
     * <p><b>这个读动作会推进轮次</b>（结算过期的、开到期的窗口），所以它在服务层是带玩家锁的
     * 「读-改-写」，不是一个纯查询。
     */
    @GetMapping("/policy")
    public Result<NationPolicyRoundView> policy(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(nations.nationPolicy(playerId));
    }

    /** 提案。权限走 {@code role_permission} 的 {@code SET_NATIONAL_POLICY}（2026-09-30 放开到官员档）。 */
    @PostMapping("/policy/propose")
    public Result<NationPolicyProposeResp> proposePolicy(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody NationPolicyProposeReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.proposeNationPolicy(playerId, req));
    }

    /**
     * 投票（B13 §二 的 {@code NationVoteReq(proposalId, boolean support)}）。
     *
     * <p>每成员一票、同一提案只能投一次；**Bot 被明确拒绝**（13019）——
     * 那一枚码在服务层产生，因为领域层看不见 Bot 身份（{@code check-no-bot-privilege} 门禁）。
     */
    @PostMapping("/policy/vote")
    public Result<NationPolicyVoteResp> votePolicy(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody NationPolicyVoteReq req) {
        requirePlayer(playerId);
        return Result.ok(nations.voteNationPolicy(playerId, req));
    }

    // ---------- 国战（B13 §一 §7，2026-10-06 承载切片 1：只读） ----------

    /**
     * 国战状态（B21 §二 点名的 {@code WarStatusResp}）：阶段、三类积分、占领者、全服击杀进度、
     * 本人的疲劳与行军闸门。
     *
     * <p><b>没有成员关系门槛</b>：国战是全服事件而不是某一国的内部事务，与 {@code /nation/treasury}
     * 那条相反（那本账是公共资产，这本账是公共进度）。
     *
     * <p><b>这一格现在证明的是承载，不是玩法</b>：读口、存储端口、两套实现与装配点都接通了，
     * 但击杀累计与疲劳累积还没有写入路径，所以生产上恒为 {@code hasWar=false}、积分与击杀全 0。
     * 验收矩阵里 B13 的疲劳上限（{@code :261}）与国家集结门槛（{@code :262}）仍是 ⬜，
     * <b>不因这个端点能 200 而变</b> —— 别拿这一条响应当那两条的证据。
     */
    @GetMapping("/war")
    public Result<WarStatusResp> warStatus(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(wars.warStatus(playerId));
    }

    /**
     * 宣战（B13 §一 §7 的开局那一步）：对本国之外的某个国家开一场 3 小时限时的王城战。
     *
     * <p>权限走 {@code role_permission} 的 {@code DECLARE_WAR}（表里 v3 收窄到国主独有，
     * 与 {@code B13:46} 官职表那句「大将军发起国战」不一致 —— <b>以表为准</b>，理由写在协议的
     * {@code WarDeclareReq} 描述里）。回的是宣战后的完整视图，理由与其它写操作同一条。
     *
     * <p><b>这一格开的是账，不是王城</b>：关卡与王城还不是地图上的可占领实体，所以打完这一下
     * 板子停在 {@code PREPARATION}、占领分与建筑分恒为 0，只有击杀（切片 2b）会动。
     * B13 的禁止项「没有压测的情况下不要上线王城战」压的是<b>攻城那一步</b>，本端点没有开放攻城，
     * 但它确实是那条红线往前挪了一步 —— 台账与验收矩阵都按这句话记账，不写成"王城战已上线"。
     */
    @PostMapping("/war/declare")
    public Result<WarStatusResp> declareWar(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody WarDeclareReq req) {
        requirePlayer(playerId);
        return Result.ok(wars.declare(playerId, req));
    }

    /**
     * 我国对各目标还在冷却中的剩余秒数（#755）。
     *
     * <p>与宣战那一枪共用同一个判据（`findLatestBetween` + `warCooldownMillis`），所以面板上灰下去的
     * 那一刻正是服务端会拒的那一刻 —— 客户端因此不需要自己算冷却（那是第二真源）。
     */
    @GetMapping("/war/cooldowns")
    public Result<WarCooldownsResp> warCooldowns(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(wars.cooldowns(playerId));
    }

    @PostMapping("/war/goal/claim")
    public Result<WarGoalClaimResp> claimWarGoal(
            @RequestHeader(CityController.PLAYER_HEADER) String playerId,
            @RequestBody WarGoalClaimReq req) {
        requirePlayer(playerId);
        return Result.ok(wars.claimServerGoal(playerId, req));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
    }
}
