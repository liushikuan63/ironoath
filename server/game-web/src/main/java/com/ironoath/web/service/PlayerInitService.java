package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ResourceCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.PlayerInitResp;
import com.ironoath.web.security.AuthSessionService;
import com.ironoath.web.security.WeChatCodeExchanger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 职责：玩家初始化/登录的应用服务 —— B01 全链路的服务端终点。
 * 依赖：game-config（读初始数值）、game-core（领域模型与仓储端口）、game-common（时间/错误码）。
 *
 * <p>本类是<b>应用层</b>而非玩法层：它负责「取配置 → 组装领域对象 → 落库 → 转 DTO」，
 * 不含任何数值规则。真正的玩法规则（产出结算、建造门槛）在 B03/B04 落进 game-core，
 * 那里只接受已解析好的参数，不读配置 —— 这是 game-core 能脱离容器单测的前提。
 *
 * <p>幂等与并发（B01 验收 11）：
 * <ol>
 *   <li>先用 {@code requestId} 占位，拦住客户端断网重放造成的重复提交</li>
 *   <li>再按 {@code deviceId} 查存档，同设备永远返回同一份存档（重复 init 等于登录）</li>
 *   <li>新建时用存储层的唯一索引做原子插入，插入失败说明有并发请求已建号，改读现有存档</li>
 * </ol>
 * 三道防线缺一不可：只有 requestId 挡不住换 requestId 的重复建号，
 * 只有 deviceId 挡不住并发窗口内的双写。
 */
@Service
public class PlayerInitService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerInitService.class);

    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ResourceRateService resourceRates;
    /** 登录响应要把资源结算到当前时刻，产率与容量都取决于城建状态。 */
    private final com.ironoath.core.city.CityRepository cities;
    /** 微信登录：把 wx.login 的 code 换成 openid（B15 §三）。 */
    private final WeChatCodeExchanger weChat;
    /** 登录成功后签发会话票据；客户端之后每个请求都要带它。 */
    private final AuthSessionService sessions;
    /** 内容安全：昵称是玩家可自由填写的内容，建档前就要送检（上线检查清单 §二 7）。 */
    private final com.ironoath.web.security.ContentSecurityGuard contentSecurity;

    public PlayerInitService(ConfigRegistry configs, PlayerRepository players,
                             IdempotencyStore idempotency, TimeService timeService,
                             ResourceRateService resourceRates,
                             com.ironoath.core.city.CityRepository cities,
                             WeChatCodeExchanger weChat,
                             AuthSessionService sessions,
                             com.ironoath.web.security.ContentSecurityGuard contentSecurity) {
        this.configs = configs;
        this.players = players;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.resourceRates = resourceRates;
        this.cities = cities;
        this.weChat = weChat;
        this.sessions = sessions;
        this.contentSecurity = contentSecurity;
    }

    /**
     * 初始化或登录玩家。
     *
     * @param req 请求体，字段约束见 contract/proto/player.schema.json
     * @return 完整初始存档快照
     * @throws BizException 参数非法或重复请求
     */
    public PlayerInitResp init(PlayerInitReq req) {
        validate(req);
        long now = timeService.serverNow();
        // 微信登录：code → openid → 账号键。走这条时 deviceId 不参与建档，
        // 于是"换手机但同一个微信"能拿回同一份存档；反过来清缓存换设备也只影响无微信的环境。
        String accountKey = weChatAccountKey(req);
        // 昵称在建档之前送检：那一刻存档还不存在，账号键是唯一带着 openid 的东西。
        // 走非微信账号（本地设备号）时没有 openid，进不了送检 —— 由守卫按既定取舍处理。
        contentSecurity.requireCleanForAccount(accountKey,
                com.ironoath.web.security.ContentSecurityClient.Scene.PROFILE,
                req.nickName(), ErrorCode.PLAYER_NICKNAME_INVALID, "昵称");
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;

        boolean firstAttempt = idempotency.tryAcquire(req.requestId(), now, ttlMs);

        // 第二道防线：同设备重复 init 就是登录，返回现有存档
        Optional<PlayerSave> existing = players.findByDeviceId(accountKey);
        if (existing.isPresent()) {
            PlayerSave save = existing.get();
            // 定向更新登录时间戳，不走乐观锁：lastLoginAt 是单调可交换字段，
            // 用「读-改-写 + 版本校验」会让并发登录互相冲突，玩家在开局第一秒就看到登录失败
            players.touchLogin(save.playerId(), now);
            if (!save.touchLogin(now)) {
                // 本地副本没能前进 ⇒ 服务端时钟被回拨，或多实例时钟不同步。
                // 这是需要告警的运维事件，但不该让玩家的登录请求失败
                LOG.warn("登录时间戳未前进，疑似服务端时钟回拨或多实例时钟不同步 playerId={} now={} 存档中={}",
                        save.playerId(), now, save.lastLoginAt());
            }
            LOG.info("玩家登录成功 playerId={} cityLevel={} 重复requestId={}",
                    save.playerId(), save.cityLevel(), !firstAttempt);
            return initResp(save, now);
        }

        if (!firstAttempt) {
            // requestId 已被占用但设备没有存档：说明上一次请求在处理途中失败了。
            // 释放幂等键让客户端可以重试，否则这个 requestId 会被永久占住、玩家再也进不来。
            idempotency.release(req.requestId());
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "requestId=" + req.requestId() + " 已占用且无对应存档，已释放请重试");
        }

        try {
            PlayerSave save = createNewPlayer(req, accountKey, now);
            // 第三道防线：原子插入。返回 false 说明并发请求已用同一 deviceId 建号
            if (!players.insertIfAbsent(save)) {
                PlayerSave winner = players.findByDeviceId(accountKey)
                        .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR,
                                "deviceId 唯一索引冲突但读不到存档，deviceId=" + accountKey));
                LOG.info("并发建号竞态，改用已存在的存档 playerId={} deviceId={}",
                        winner.playerId(), accountKey);
                return initResp(winner, now);
            }
            TraceContext.bindPlayer(save.playerId());
            LOG.info("新玩家创建成功 playerId={} nickName={} 资源种类={} 保护到期={}",
                    save.playerId(), save.nickName(), save.resources().size(), save.protectUntil());
            return PlayerDtoMapper.toInitResp(save, now, sessions.issue(save.playerId(), now));
        } catch (RuntimeException e) {
            // 建号失败必须释放幂等键：副作用没有产生，让客户端能安全重试
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 组装登录 / 兜底响应：资源必须结算到本次响应的 {@code now}。
     *
     * <p><b>为什么必须结算</b>：{@code serverNow} 每次都是新的，而存档里的存量停在上次结算的
     * 时刻。客户端按 B00 铁律从不自己结算，所以它拿到「旧存量 + 新时刻」就只能显示旧值，
     * 症状是「重登一次，资源数字倒退回去」。新号建档那条分支不需要走这里：
     * 存档里的值就是同一时刻刚算出来的。
     *
     * <p><b>读路径不写存档</b>（理由见 {@code ResourceRateService.settledView}：结算是纯函数，
     * 而登录做读-改-写会让并发重登互撞乐观锁）。城还没建起来的玩家同样结算 ——
     * 沿用存档里的产率与容量，只推进时间轴。
     */
    private PlayerInitResp initResp(PlayerSave save, long now) {
        // 读不到城时传 null：settledView 会沿用存档里的产率与容量、只结算时间轴，
        // 而不是干脆不结算（那等于让新号登录返回一份停在建档时刻的存量）
        return PlayerDtoMapper.toInitResp(save, now, resourceRates.settledView(
                save, cities.findByPlayerId(save.playerId()).orElse(null), now),
                sessions.issue(save.playerId(), now));
    }

    /**
     * 计算账号键：有微信 code 用 openid，没有就用 deviceId。
     *
     * <p><b>为什么不做成"有 code 就覆盖 deviceId"的第二种存储</b>：账号键本来就是
     * {@code PlayerSave.deviceId} 这一列（唯一索引也在它上面）。把微信登录映射进同一列，
     * "同一微信 = 同一存档"这条与"同设备 = 同一存档"就自动共用同一套幂等与并发保护，
     * 不需要第二张账号表，也不会出现"两套键指向同一个玩家"的裂脑状态。
     */
    private String weChatAccountKey(PlayerInitReq req) {
        String code = req.wxCode();
        if (code == null || code.isBlank()) {
            return req.deviceId();
        }
        var identity = weChat.exchange(code);
        return WeChatCodeExchanger.WECHAT_ACCOUNT_PREFIX + identity.openId();
    }

    /**
     * 从配置表组装新号存档。所有初始数值都来自配置，本方法内没有任何字面量数字（铁律 1）。
     *
     * <p>容量、产率、保护额度一律走 {@link ResourceRateService}，不直接读 resource 表：
     * 这三个值的唯一计算入口必须只有一个，否则新号建档时算一套、第一次打开城内界面又算一套，
     * 玩家会看到资源条数字凭空跳变（例如保护量从 0 跳到 4000）。
     * 传入空城建状态是准确的 —— 新号还没有任何产出建筑，算出来就是配置表的初始值。
     */
    private PlayerSave createNewPlayer(PlayerInitReq req, String accountKey, long now) {
        ResourceRateService.Rates rates = resourceRates.compute(new com.ironoath.core.city.CityState());
        Map<String, PlayerResourceState> resources = new LinkedHashMap<>();
        for (ResourceCfg cfg : configs.allResources()) {
            long cap = rates.cap(cfg.id());
            long amount = Math.min(cfg.initAmount(), cap);
            resources.put(cfg.id(), new PlayerResourceState(
                    amount, cap, resourceRates.protectedAmountOf(cfg.id(), rates),
                    rates.perHour(cfg.id()), now));
        }

        long initMatchPower = configs.longParam("INIT_MATCH_POWER");
        PlayerPower power = new PlayerPower(initMatchPower, initMatchPower, initMatchPower);

        long protectSeconds = configs.longParam("NEWCOMER_PROTECT_SECONDS");
        Long protectUntil = protectSeconds > 0L ? now + protectSeconds * 1000L : null;

        return PlayerSave.createNew(
                newPlayerId(),
                // 账号键而不是原始 deviceId：走微信登录时唯一索引列必须存 openid 派生的键，
                // 否则同一个微信第二次登录会在库里找不到自己刚才建的那份存档（每次都是新号）
                accountKey,
                req.nickName(),
                (int) configs.longParam("INIT_AVATAR_ID"),
                now,
                (int) configs.longParam("INIT_CITY_LEVEL"),
                resources,
                power,
                protectUntil);
    }

    /**
     * 服务端独立校验（铁律 2：客户端校验只是提示，删掉客户端校验后服务端行为不变）。
     *
     * <p>长度上下界全部来自 contract/config/global.json，与 Schema 的 minLength/maxLength 保持一致。
     */
    private void validate(PlayerInitReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        checkLength(req.requestId(), "requestId",
                configs.longParam("REQUEST_ID_MIN_LENGTH"),
                configs.longParam("REQUEST_ID_MAX_LENGTH"),
                ErrorCode.REQUEST_ID_MISSING);
        checkLength(req.deviceId(), "deviceId",
                configs.longParam("DEVICE_ID_MIN_LENGTH"),
                configs.longParam("DEVICE_ID_MAX_LENGTH"),
                ErrorCode.PLAYER_DEVICE_INVALID);
        checkLength(req.nickName(), "nickName",
                1L, configs.longParam("NICKNAME_MAX_LENGTH"),
                ErrorCode.PLAYER_NICKNAME_INVALID);

        if (req.clientTime() <= 0L) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "clientTime 必须为正的服务端时间戳基准，实际=" + req.clientTime());
        }
    }

    private static void checkLength(String value, String field, long min, long max, ErrorCode errorCode) {
        if (value == null || value.isBlank()) {
            throw new BizException(errorCode, field + " 不得为空");
        }
        if (containsControlChar(value)) {
            // 控制字符会破坏日志行结构（换行可伪造日志），必须拒绝而不是转义后放行
            throw new BizException(errorCode, field + " 含非法控制字符");
        }
        if (value.length() < min || value.length() > max) {
            throw new BizException(errorCode,
                    field + " 长度必须在 [" + min + ", " + max + "] 之间，实际=" + value.length());
        }
    }

    private static boolean containsControlChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** 玩家 id：UUID 去掉连字符后加前缀，便于日志里一眼区分玩家 id 与其他 id。 */
    private static String newPlayerId() {
        return "P" + UUID.randomUUID().toString().replace("-", "");
    }
}
