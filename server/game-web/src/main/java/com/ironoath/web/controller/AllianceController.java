package com.ironoath.web.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.Result;
import com.ironoath.common.time.TimeService;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceDonateReq;
import com.ironoath.web.dto.generated.AllianceDonateResp;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceListResp;
import com.ironoath.web.dto.generated.AllianceMemberReq;
import com.ironoath.web.dto.generated.AllianceRoleReq;
import com.ironoath.web.dto.generated.AllianceMember;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.AllianceSelfReq;
import com.ironoath.web.dto.generated.AllianceSyncReq;
import com.ironoath.web.dto.generated.AllianceSyncResp;
import com.ironoath.web.dto.generated.AllianceTechReq;
import com.ironoath.web.dto.generated.AllianceTechResp;
import com.ironoath.web.dto.generated.SocialSummaryResp;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：联盟域 HTTP 入口（B10 §2）。
 * 依赖：Spring Web、{@link SocialAppService}、{@link SocialStore}（只为 diff 同步读版本号）。
 *
 * <p><b>{@code POST /alliance/sync} 是验收 10 的落点</b>：成员数据变更只下发 diff，不全量同步。
 * 客户端带上手里的版本号，版本相同就返回 unchanged=true 与三个空列表 ——
 * 与 B07 地图 chunk 的版本号是同一套思路（那里的验收 6 是「无变化时二次请求实体数为 0」）。
 * 150 人的成员列表是联盟数据里最大的一块，每次心跳都带上它就把 diff 同步的意义抵消掉了。
 *
 * <p><b>当前实现的 diff 粒度是「全有或全无」</b>：版本相同则什么都不给，
 * 版本不同则把所有成员都放进 changedMembers。这满足验收 10 的可观测行为
 * （无变化时零数据量），但还没做到「只给变化的那几个人」——
 * 那需要在 Alliance 里给每个成员记一个 lastChangedVersion，属 B16 的性能批次。
 * 已记入待办清单。
 */
@RestController
@RequestMapping("/alliance")
public class AllianceController {

    private final SocialAppService social;
    private final TimeService timeService;
    private final SocialStore store;

    public AllianceController(SocialAppService social, TimeService timeService, SocialStore store) {
        this.social = social;
        this.timeService = timeService;
        this.store = store;
    }

    /** 创建联盟。消耗 global.ALLIANCE_CREATE_COST_GOLD 金币，且解散保护期内不能再建（验收 7）。 */
    @PostMapping("/create")
    public Result<SocialSummaryResp> create(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                            @RequestBody AllianceCreateReq req) {
        requirePlayer(playerId);
        return Result.ok(social.allianceCreate(playerId, req));
    }

    /**
     * 可申请联盟的列表（B26 S6）。这是「申请加入」那一颗按钮的唯一数据来源 ——
     * 在它存在之前，没有联盟的玩家读不到"有哪些联盟可申"，只能自己花金币建一个。
     */
    @GetMapping("/list")
    public Result<AllianceListResp> list(@RequestHeader(CityController.PLAYER_HEADER) String playerId) {
        requirePlayer(playerId);
        return Result.ok(social.allianceList(playerId, timeService.serverNow()));
    }

    /** 申请入盟。 */
    @PostMapping("/apply")
    public Result<SocialSummaryResp> apply(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody AllianceIdReq req) {
        requirePlayer(playerId);
        requireText(req.allianceId(), "allianceId");
        return Result.ok(social.allianceApply(playerId, req));
    }

    /** 审核申请。<b>拒绝也要显式调用</b>：只是不处理会让申请者永远不知道自己被忽略了。 */
    @PostMapping("/review")
    public Result<SocialSummaryResp> review(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                            @RequestBody AllianceReviewReq req) {
        requirePlayer(playerId);
        requireText(req.applicantId(), "applicantId");
        return Result.ok(social.allianceReview(playerId, req));
    }

    /** 踢出成员。权限位 KICK_MEMBER（走 role_permission 表），不能踢盟主。 */
    @PostMapping("/kick")
    public Result<SocialSummaryResp> kick(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                          @RequestBody AllianceMemberReq req) {
        requirePlayer(playerId);
        return Result.ok(social.allianceKick(playerId, req));
    }

    /** 转让盟主。只有盟主能发起；转给自己会被拒。 */
    @PostMapping("/transfer")
    public Result<SocialSummaryResp> transfer(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                              @RequestBody AllianceMemberReq req) {
        requirePlayer(playerId);
        return Result.ok(social.allianceTransfer(playerId, req));
    }

    /** 任命职位。不能任命不低于自己的职位，降级盟主必须走转让。 */
    @PostMapping("/setRole")
    public Result<SocialSummaryResp> setRole(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody AllianceRoleReq req) {
        requirePlayer(playerId);
        return Result.ok(social.allianceSetRole(playerId, req));
    }

    /** 退出联盟。盟主必须先转让或解散，否则联盟会剩下没有责任人的成员。 */
    @PostMapping("/leave")
    public Result<SocialSummaryResp> leave(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                           @RequestBody AllianceSelfReq req) {
        requirePlayer(playerId);
        return Result.ok(social.allianceLeave(playerId, req));
    }

    /** 解散联盟。解散者进入保护期（验收 7），保护期长短来自 global.ALLIANCE_DISBAND_PROTECT_SECONDS。 */
    @PostMapping("/disband")
    public Result<SocialSummaryResp> disband(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody AllianceSelfReq req) {
        requirePlayer(playerId);
        return Result.ok(social.allianceDisband(playerId, req));
    }

    /** 捐献（验收 8：联盟资金与个人贡献值同步增加，响应里两个总额都下发）。 */
    /**
     * 扩容人数上限（B10 验收 3）。扣联盟资金，档位与价格来自 {@code alliance_config} 表；
     * 领域方法 {@code Alliance#expand} 早就写完了，缺的只是这个入口 ——
     * 客户端绑的 {@code /alliance/expand} 在此之前一律 404。
     */
    @PostMapping("/expand")
    public Result<SocialSummaryResp> expand(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                            @RequestBody AllianceSelfReq req) {
        return Result.ok(social.allianceExpand(requirePlayer(playerId), req));
    }

    @PostMapping("/donate")
    public Result<AllianceDonateResp> donate(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                             @RequestBody AllianceDonateReq req) {
        requirePlayer(playerId);
        if (req.tier() < 0 || req.tier() > 2) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "捐献档位只支持 0（免费）/ 1（资源）/ 2（金币），实际=" + req.tier());
        }
        return Result.ok(social.allianceDonate(playerId, req));
    }

    /**
     * 研究联盟科技（B10 §2：花联盟资金、全盟生效、上限随联盟等级）。
     *
     * <p>这是<b>能花公共资产</b>的写入口，所以权限判定在服务层走 {@code role_permission} 的
     * {@code RESEARCH_TECH} 行 —— 本类只做身份与路由，不把「谁能研究」这件事在两层各判一遍。
     */
    @PostMapping("/tech")
    public Result<AllianceTechResp> tech(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                        @RequestBody AllianceTechReq req) {
        requirePlayer(playerId);
        return Result.ok(social.allianceTech(playerId, req));
    }

    /**
     * 增量同步联盟数据（验收 10）。
     *
     * @param req 客户端手里的版本号；首次同步传 0
     */
    @PostMapping("/sync")
    public Result<AllianceSyncResp> sync(@RequestHeader(CityController.PLAYER_HEADER) String playerId,
                                         @RequestBody AllianceSyncReq req) {
        String me = requirePlayer(playerId);
        long now = timeService.serverNow();
        var alliance = store.allianceOf(me)
                .orElseThrow(() -> new BizException(ErrorCode.ALLIANCE_NOT_FOUND, "playerId=" + me));

        boolean unchanged = alliance.version() == req.version();
        List<AllianceMember> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        if (!unchanged && req.wantMembers()) {
            // 版本不同就把当前成员全量作为 changed 下发（粒度说明见类注释）
            changed.addAll(social.allianceMembers(me, now));
        }
        return Result.ok(new AllianceSyncResp(alliance.version(), unchanged, changed, removed,
                alliance.fund(), alliance.level(), alliance.memberCount(), "", now));
    }

    private static String requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
        return playerId;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + " 不得为空");
        }
    }
}
