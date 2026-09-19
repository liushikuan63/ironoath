package com.ironoath.web.season;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.season.SeasonTier;
import com.ironoath.core.season.SeasonTimeline;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.web.dto.generated.SeasonSettleReq;
import com.ironoath.web.dto.generated.SeasonSettleResp;
import com.ironoath.web.service.ServerCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 职责：驱动 {@link SeasonSettlement} —— 实时榜上报、按快照结算、发奖、归档（B14 §3/§4/§5）。
 * 依赖：game-config（规则装配）、game-core 领域件、{@link RewardService}、{@link SeasonLedgerStore}。
 *
 * <p><b>为什么由请求驱动而不是 {@code @Scheduled}</b>：B14 禁止项明写「不要用 @Scheduled 触发结算」，
 * 分层检查也禁止常驻定时调度。所以结算是一个<b>幂等的、可重复调用的运营入口</b>
 * （{@code POST /season/settle}）：外部调度系统按时钟打一次，打三次也只发一次。
 * 快照同样是惰性的 —— 第一次结算请求进来时才拍，因此「最后一秒刷分」的时间窗是
 * <b>调度延迟</b>而不是「随时可刷」，而快照一旦拍下去就冻结了。
 *
 * <p><b>结算对象只来自榜，不去扫玩家表</b>：{@code PlayerRepository} 刻意没有全表遍历接口
 * （有它就有人会写全表扫描）。有名次的本来就只有报过战力的那批人，
 * 而没上榜的人奖励恒为 0 —— 遍历他们不会改变任何结果，只会把一次结算变成一次全服锁表。
 *
 * <p><b>Bot 不进这张榜</b>（B11 §七 / B13 §四 / B16 上线清单 §七 2）：
 * 榜是名次的唯一来源 —— 快照、{@code myRank}、发奖全从它派生，所以合规排除落在
 * <b>上榜入口</b>（{@link #report}）与 <b>恢复榜</b>（{@link #settlement()}）两处：
 * 前者保证新写入里没有 Bot，后者保证存储里若有历史 Bot 也不会被重新装回名次。
 * 到结算那一刻再筛是错的：那时 Bot 已经占着名次（真人被挤到第 4），
 * 而"摘掉"的本意是连坑位一起让出来 —— 也让展示名次与发奖名次永远只有一个口径。
 */
@Service
public class SeasonSettlementService {

    private static final Logger LOG = LoggerFactory.getLogger(SeasonSettlementService.class);

    private final ConfigRegistry configs;
    private final TimeService timeService;
    private final SeasonRulesAssembler assembler;
    private final PlayerRepository players;
    private final RewardService rewardService;
    private final SeasonLedgerStore ledger;
    /**
     * 榜与快照的持久化。<b>它与账本一起才构成"可重放"</b>：账本管"这一季这个人付过没有"，
     * 它管"结算依据是什么"。少了后者，重启后再点结算会从一张冷榜拍快照，
     * 而快照不可重拍 + 账本记成"已付过" ⇒ 名次算错的那批人永久拿不到（见 {@code SeasonBoardStore}）。
     */
    private final SeasonBoardStore boards;
    private final IdempotencyStore idempotency;
    /**
     * Bot 合规红线（§七 2「不占前 N 名奖励坑位」）的唯一判定入口。
     * 领域层刻意不知道 Bot 的存在（{@code check-no-bot-privilege.sh} 禁止身份渗进游戏逻辑），
     * 所以排除只能长在 web 这一侧的榜入口上。
     */
    private final com.ironoath.web.bot.BotRegistry bots;
    /**
     * 战令的赛季末补发（B24 验收 2：未领的奖励不静默作废）。
     *
     * <p><b>为什么挂在结算这一步而不是战令自己的读路径</b>：补发的前提是"这个赛季结束了"，
     * 而赛季结束这件事只有结算知道（`SeasonTimeline` 是纯时间轴，它不知道有没有人来结算过）。
     * 结算一年只跑一次、且已经带幂等键，所以这里不需要第二套触发机制。
     *
     * <p><b>受众与榜单不同</b>：结算的循环走的是榜单前 N 名，而战令的受众是本赛季打过分的所有人 ——
     * 所以补发按战令存储枚举，与上面那个循环各自独立。
     */
    private final com.ironoath.web.battlepass.BattlePassService battlePass;

    /** 当前赛季的结算器。赛季 id 变了就换一个新的（B14 §4：一赛季一份独立数据）。 */
    private volatile SeasonSettlement current;

    public SeasonSettlementService(ConfigRegistry configs, TimeService timeService,
                                   SeasonRulesAssembler assembler, PlayerRepository players,
                                   RewardService rewardService, SeasonLedgerStore ledger,
                                   SeasonBoardStore boards, IdempotencyStore idempotency,
                                   com.ironoath.web.bot.BotRegistry bots,
                                   com.ironoath.web.battlepass.BattlePassService battlePass) {
        this.configs = configs;
        this.timeService = timeService;
        this.assembler = assembler;
        this.players = players;
        this.rewardService = rewardService;
        this.ledger = ledger;
        this.boards = boards;
        this.idempotency = idempotency;
        this.bots = bots;
        this.battlePass = battlePass;
    }

    /**
     * 上报实时战力到榜（由 {@code PowerRefreshService} 在每次重算后调用）。
     *
     * <p>未配置赛季锚点时静默跳过：那意味着这个服还没启用赛季，而不是「榜应该是空的」。
     *
     * <p><b>Bot 在这里被摘掉</b>（B11 §七：不得占据需真人竞争的前 N 名排行奖励坑位）：
     * 它不落库、不进实时榜，于是快照、{@code myRank} 与发奖同时不含它。
     * "落选不是错误"，所以走 {@code humanOnly} 的静默形状而不是拒绝 —— Bot 战力照常刷新，
     * 只是不参与这张榜。判定本体仍只有一个家：注册表（真人一律通过）。
     */
    public void report(String playerId, String name, long matchPower) {
        if (ServerCalendar.seasonStartOrZero(configs) == 0L) {
            return;
        }
        if (bots.humanOnly(playerId, "赛季榜奖励坑位") == null) {
            return;
        }
        SeasonSettlement settlement = settlement();
        SeasonSettlement.Board board = board();
        // 先落库再改内存：落库失败会抛（调用方在玩家锁里，写请求整体失败），
        // 而"内存里更新了、库里没有"会在重启后变成一条凭空消失的上报 ——
        // 那正是本档要消掉的形状（冷榜结算）
        boards.report(settlement.seasonId(), board,
                new SeasonSettlement.Entry(playerId, name == null ? playerId : name, matchPower));
        settlement.updateLive(board, playerId, name, matchPower);
    }

    /**
     * 玩家在<b>实时榜</b>上的名次（1-based；0 = 还没上报过战力，也就是未上榜）。
     *
     * <p>刻意不是快照榜：快照只在第一次结算时拍下，面板要显示的是「我现在第几名」，
     * 而结算只认快照（B14 禁止项）。同一个方法名读两份数据会迟早接错，所以这里不叫 rank。
     */
    public int liveRank(String playerId) {
        if (ServerCalendar.seasonStartOrZero(configs) == 0L) {
            return 0;
        }
        return settlement().liveRank(board(), playerId);
    }

    /**
     * 结算本赛季。可重复调用：幂等键是 {@code requestId} + 领域层的 {@code seasonId:playerId}。
     *
     * @throws BizException 赛季未启用、本赛季还在进行中（没到结算期）、或快照无法冻结
     */
    public SeasonSettleResp settle(SeasonSettleReq req) {
        long now = timeService.serverNow();
        String requestId = req == null ? null : req.requestId();
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "赛季结算必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
        try {
            return doSettle(req, now);
        } catch (RuntimeException e) {
            idempotency.release(requestId);
            throw e;
        }
    }

    private SeasonSettleResp doSettle(SeasonSettleReq req, long now) {
        long seasonStart = ServerCalendar.seasonStartOrZero(configs);
        if (seasonStart == 0L) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "未配置 SEASON_START_AT，赛季未启用，没有可结算的赛季");
        }
        SeasonTimeline timeline = new SeasonTimeline(assembler.timelineRules());
        SeasonTimeline.Phase phase = timeline.phaseAtTime(now, seasonStart);
        if (phase != SeasonTimeline.Phase.SETTLE && phase != SeasonTimeline.Phase.REST) {
            // 提前结算会把人按还没打完的榜发奖，而发出去的奖励收不回来；到点没结算则是漏发
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "当前是" + phase.name() + "阶段，只有结算期或休赛期能结算（赛季第 "
                            + (SeasonTimeline.dayIndexOf(now, seasonStart) + 1) + " 天 / 共 "
                            + timeline.rules().totalDays() + " 天）");
        }
        SeasonSettlement settlement = settlement();
        SeasonSettlement.Board board = board();
        if (!settlement.snapshotReady()) {
            SeasonSettlement.Snapshot taken = settlement.takeSnapshot(board, now);
            if (boards.saveSnapshotIfAbsent(settlement.seasonId(), taken)) {
                LOG.info("赛季快照已拍下并落库 seasonId={} 榜={} 条目={} 时刻={}",
                        settlement.seasonId(), board, taken.entries().size(), now);
            } else {
                // 另一个实例先拍下了。快照不可更改（它是申诉依据），所以以库里那份为准：
                // 重建工作集 → settlement() 会重新 hydrate，把库里的快照恢复进来。
                // 本进程刚拍的那张只活了几毫秒，没人见过它，丢掉不构成"重拍"
                LOG.warn("本赛季快照已被另一个实例拍下，本实例改用库里的那一份 seasonId={} 榜={}",
                        settlement.seasonId(), board);
                reset();
                settlement = settlement();
            }
        }
        SeasonSettlement.Snapshot frozen = settlement.snapshot(board);
        if (frozen == null) {
            // 走到这里只可能是"存储说已有快照、却又读不回来"这种不一致（探针 P3 抓到的形状：
            // 少一行恢复就会掉到这里，而原来是一句 NPE）。必须响亮地失败而不是继续：
            // 继续等于按实时榜结算，而错误名次会被账本永久固化（发出去收不回来）
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "赛季快照未能就绪（落库与读取不一致）：拒绝按实时榜结算，请检查赛季榜存储");
        }
        long snapshotAt = frozen.snapshotAt();

        int pageSize = pageSize(req.pageSize());
        List<SeasonSettlement.Entry> entries = settlement.liveBoard(board);
        SeasonTier tier = new SeasonTier(assembler.tierRules());
        int fresh = 0;
        long coinTotal = 0L;
        long goldTotal = 0L;
        // 分页而不是全表：一页失败只影响这一页，而幂等键保证已结算的人不会被再发一次
        for (int from = 0; from < entries.size(); from += pageSize) {
            List<SeasonSettlement.Entry> slice =
                    entries.subList(from, Math.min(entries.size(), from + pageSize));
            List<String> page = new ArrayList<>(slice.size());
            for (SeasonSettlement.Entry entry : slice) {
                page.add(entry.id());
            }
            for (SeasonSettlement.Award award : settlement.settlePage(page,
                    playerId -> tier.place(powerOf(playerId)).tier())) {
                if (!award.firstTime()) {
                    continue;   // 进程内幂等快路：不发奖、不记账，也就不计入本次人数
                }
                // 先记后发：账本才是"这一季这个人已经付过"的持久凭据。
                // 上面那张进程内的 settled 表重启即空，而运维再点一次结算用的是新 requestId
                // （requestId 幂等键只挡同一个请求的重放，挡不住换一次请求重跑）——
                // 少了这一句，"重启 + 再结算"就是给同一批人再发一遍金币，而发出去收不回来。
                // 失败方向选"记了没发出去"：那笔有补偿队列与客服入口，反过来没有（与支付域同一条取舍）
                boolean mine = ledger.recordIfAbsent(settlement.seasonId(),
                        new SeasonLedgerStore.Record(award.playerId(), award.rank(), award.tier(),
                                award.seasonCoin(), award.gold()));
                if (!mine) {
                    LOG.warn("赛季结算跳过：账本里已有这一季这个人的记录 seasonId={} playerId={}"
                                    + "（重启后重跑结算会走到这里，这正是它该被挡住的证据）",
                            settlement.seasonId(), award.playerId());
                    continue;
                }
                fresh++;
                coinTotal += award.seasonCoin();
                goldTotal += grantGold(award, settlement.seasonId());
                // 顺手刷一次荣耀缓存。刻意不在这里再算一遍"荣耀怎么来" ——
                // gloryOf 是唯一写者，读路径走的是同一个方法，两处永远同口径
                gloryOf(award.playerId());
            }
        }
        List<String> expired = settlement.archive(settlement.seasonId());
        if (!expired.isEmpty()) {
            LOG.warn("领域层判定有赛季超出归档保留数：{}（本服务的实际清理以账本里出现过的季号为准，"
                    + "理由见 purgeArchivedSeasons 的注释）", expired);
        }
        purgeArchivedSeasons(settlement.seasonId(), settlement.rules().archiveCollections());
        LOG.info("赛季结算 seasonId={} 本次新结算={}人 发金币={} 发赛季币={} 依据快照={} 榜单人数={}",
                settlement.seasonId(), fresh, goldTotal, coinTotal, snapshotAt, entries.size());
        // 战令：把"已达成但没领"的档位奖励按档发进邮箱。**补发失败不回滚结算** ——
        // 结算发的是赛季币与金币（账本是凭据），战令补发是另一本账；
        // 让一个域的失败挡住另一个域已经落库的发奖，只会让"结算了一半"变成常态
        try {
            battlePass.sweepSeasonToMail(settlement.seasonId());
        } catch (RuntimeException e) {
            LOG.error("战令赛季末补发失败 seasonId={}：结算已完成，补发可以重跑"
                    + "（补发的幂等靠战令进度里的已领标记，重跑不会重复发）", settlement.seasonId(), e);
        }
        return new SeasonSettleResp(settlement.seasonId(), fresh, coinTotal, goldTotal,
                snapshotAt, now);
    }

    /**
     * 归档保留的物理执行者：把超出保留数的旧赛季从账本与榜/快照两处删掉（B14 §五 3「保留 3 个赛季」，
     * 2026-09-13 裁决 C17）。挂在结算之后而不是开一个新端点 —— 结算是唯一的写者，
     * 多一个运维入口就是第二个真相。
     *
     * <p><b>为什么不直接用领域层 {@code SeasonSettlement.archive()} 的返回值</b>：那份
     * {@code archived} 集合是<b>进程内</b>的，而本服务每个赛季新建一个 settlement 实例，
     * 所以它每次只装得下当前这一季 ⇒ 恒返回空。照它写清理代码会是一段永远跑不到的死码，
     * 症状与"清理根本没写"一模一样。真源换成存储里确实存在过的季号（{@code ledger.seasonIds()}）。
     *
     * <p><b>季号字典序为什么能当时间序用</b>：{@code season.json} 的行 id 形如
     * {@code season_01_phase_3}，季号补零到两位，所以字典序与时间序一致。
     *
     * <p><b>两条 fail-safe</b>：当前季永不进候选；且只有<b>严格小于</b>当前季号的才会被删。
     * 万一将来季号格式不再补零，最坏结果是少删（旧档多留一阵，仍然安全），
     * 而不是删掉一个还在申诉窗口内的赛季（不可恢复）。
     */
    private void purgeArchivedSeasons(String currentSeasonId, int keep) {
        List<String> older = new ArrayList<>();
        for (String seen : ledger.seasonIds()) {
            if (seen.compareTo(currentSeasonId) < 0) {
                older.add(seen);
            }
        }
        older.sort(Comparator.reverseOrder());
        // keep 里要给当前季留一个位子：B14 那句"保留 3 个赛季"含正在跑的这一季
        for (int i = Math.max(0, keep - 1); i < older.size(); i++) {
            String stale = older.get(i);
            int records = ledger.purgeSeason(stale);
            int boardRows = boards.purgeSeason(stale);
            LOG.warn("归档超出保留期（保留 {} 个赛季），已删除 seasonId={} 结算记录={} 榜与快照条目={}："
                    + "删除不可恢复，这一季之后不再能用于申诉", keep, stale, records, boardRows);
        }
    }

    /**
     * 我的荣耀三件套 —— <b>缓存的唯一写者，也是唯一读口</b>。
     *
     * <p>口径只有一句：<b>账本是真相，主存档那份是缓存</b>。所以答案永远取自
     * {@code ledger.gloryOf} 的派生值；只有主存档里那份与它不一致时才顺手写回一次。
     * 这同时就是「重启之后缓存怎么自愈」的答案 —— 不需要迁移脚本：老号从没写过、
     * 或上次结算写回时撞了乐观锁被跳过，都会在<b>第一次被读到</b>的那一刻对上一次账。
     *
     * <p><b>写回失败只记 WARN，不影响返回</b>：缓存落后不是结算失败，
     * 不该让玩家看不到自己荣耀等级的同时还得不到一个诚实的回答。
     */
    public com.ironoath.core.player.PlayerGlory gloryOf(String playerId) {
        com.ironoath.core.player.PlayerGlory derived = ledger.gloryOf(playerId);
        com.ironoath.core.player.PlayerSave save = players.findByPlayerId(playerId).orElse(null);
        if (save == null) {
            return derived;
        }
        if (derived.equals(save.glory())) {
            return derived;
        }
        save.setGlory(derived);
        try {
            players.save(save);
        } catch (RuntimeException e) {
            LOG.warn("荣耀缓存写回失败，本次仍返回账本派生值 playerId={} 原因={}"
                    + "（缓存落后不是结算失败，下一次读会再对一次账）", playerId, e.getMessage());
        }
        return derived;
    }

    /**
     * 金币走 {@link RewardService} 而不是 wallet：容量上限、保护量、装不下时转邮件补发都在它那里
     * （B04 禁止项「奖励不得绕过发放器」）。这里刻意<b>不使用</b>返回的补偿 id 去改发奖结论 ——
     * 补偿本身就是发放器的正常出口，奖励已经算发出了。
     */
    private long grantGold(SeasonSettlement.Award award, String seasonId) {
        if (award.gold() <= 0L) {
            return 0L;
        }
        var grant = rewardService.grant(award.playerId(),
                List.of(new RewardItem(RewardType.RESOURCE, ResourceIds.GOLD, award.gold())),
                RewardContext.toMail("season", seasonId, seasonId + ":" + award.playerId()));
        if (grant.hasCompensation()) {
            LOG.error("【赛季金币入账失败已进补偿队列】playerId={} seasonId={} 数量={} compensationId={}",
                    award.playerId(), seasonId, award.gold(), grant.compensationId());
        }
        return award.gold();
    }

    /** 段位要按结算那一刻的战力现算，所以读存档；读不到按 0 战力（青铜）。 */
    private long powerOf(String playerId) {
        return players.findByPlayerId(playerId).map(PlayerSave::power)
                .map(power -> power.matchPower()).orElse(0L);
    }

    private int pageSize(Integer requested) {
        int max = (int) configs.longParam("SEASON_SETTLE_PAGE_SIZE");
        if (requested == null || requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }

    private SeasonSettlement.Board board() {
        return assembler.settlementRules().snapshotBoard();
    }

    /**
     * 拿到当前赛季的结算器；赛季换了就换一个新的对象。
     *
     * <p>双检锁而不是每次 new：实时榜是<b>有状态</b>的（上报累积在其中），每调用一次就重建
     * 会让榜永远是空的，结算出一个谁都没名次的赛季。
     *
     * <p><b>新建实例时必须先从存储恢复榜与快照</b>：它们是"结算依据"，而本类的实例是进程内的。
     * 不恢复的话，重启（或换季）之后榜是空的、快照是"没拍过"——
     * 后者会让结算重拍一张快照，而快照存在的意义就是"有一刻被冻住了"。
     */
    private SeasonSettlement settlement() {
        SeasonTimeline.Rules timeline = assembler.timelineRules();
        SeasonSettlement existing = current;
        if (existing != null && existing.seasonId().equals(timeline.seasonId())) {
            return existing;
        }
        synchronized (this) {
            if (current != null && current.seasonId().equals(timeline.seasonId())) {
                return current;
            }
            SeasonSettlement next = new SeasonSettlement(timeline.seasonId(),
                    assembler.settlementRules());
            SeasonSettlement.Board board = assembler.settlementRules().snapshotBoard();
            int skippedBots = 0;
            for (SeasonSettlement.Entry entry : boards.board(next.seasonId(), board)) {
                // 读侧兜底：写入侧（report）已经拦掉 Bot，但库里可能存着这条规则生效之前
                // 写进去的条目 —— 不在这里摘掉的话，一次重启就能把 Bot 重新装回名次。
                // 与 report 用同一个判定，不另写一套"存储里算不算数"的说法
                if (bots.humanOnly(entry.id(), "赛季榜奖励坑位") == null) {
                    skippedBots++;
                    continue;
                }
                next.updateLive(board, entry.id(), entry.name(), entry.score());
            }
            if (skippedBots > 0) {
                LOG.warn("赛季榜恢复时摘掉 {} 个 Bot 条目（B16 §七 2：Bot 不占奖励坑位）"
                        + " seasonId={} 榜={}", skippedBots, next.seasonId(), board);
            }
            SeasonSettlement.Snapshot stored = boards.snapshot(next.seasonId(), board);
            if (stored != null) {
                // 库里已经有一份冻结的快照：恢复它，本次进程不再重拍（重拍会让申诉依据有两个版本）
                next.restoreSnapshot(stored);
            }
            LOG.info("切换到新赛季结算器 seasonId={} 恢复榜上={}人 已有快照={} 归档集合={}",
                    next.seasonId(), next.liveBoard(board).size(), stored != null,
                    next.archiveCollection());
            current = next;
            return current;
        }
    }

    /** 测试用：换季或清库时丢掉当前结算器。 */
    public void reset() {
        current = null;
    }
}
