package com.ironoath.common;

/**
 * 职责：全项目统一错误码枚举。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p>段位划分（新增错误码必须落在对应段位，禁止跨段复用数字）：
 * <pre>
 *   0        成功
 *   1000~1999  系统 / 通用（参数、幂等、限流、内部错误）
 *   2000~2999  玩家 / 账号
 *   3000~3999  城建（B03）
 *   4000~4999  资源 / 背包 / 道具（B04）
 *   5000~5999  兵种 / 战斗（B05）
 *   6000~6999  武将（B06）
 *   7000~7999  大地图 / 行军 / 迷雾（B07）
 *   8000~8999  战力圈层 / 匹配校验（B08）
 *   9000~9999  PVE / 关卡（B09）
 *   10000~10999 小队 / 联盟（B10）
 *   11000~11999 Bot 生态（B11）
 *   12000~12999 外围系统：任务 / 邮件 / 战报 / 红点（B12）
 *   13000~13999 国家 / 国战（B13）
 *   14000~14999 赛季（B14）
 *   15000~15999 商业化 / 支付 / 合规（B15）
 *   16000~16999 性能 / 埋点（B16）
 * </pre>
 *
 * <p>msg 为中文，直接可下发给客户端展示（合规要求：错误提示需可读，见 B15）。
 */
public enum ErrorCode {

    // ---------- 0 成功 ----------
    OK(0, "成功"),

    // ---------- 1xxx 系统 / 通用 ----------
    SYSTEM_ERROR(1000, "系统繁忙，请稍后再试"),
    PARAM_INVALID(1001, "请求参数不合法"),
    REQUEST_DUPLICATED(1002, "请求重复提交"),
    REQUEST_ID_MISSING(1003, "缺少幂等标识 requestId"),
    RATE_LIMITED(1004, "操作过于频繁，请稍后再试"),
    CONFIG_INVALID(1005, "配置表校验失败"),
    CONFIG_NOT_FOUND(1006, "配置项不存在"),
    NOT_IMPLEMENTED(1007, "功能尚未开放"),
    /**
     * 运维令牌不通过（或服务端压根没配令牌）。给「只应由运维/调度系统调用」的端点用，
     * 例如 {@code POST /season/settle} —— 它由外部按时钟打一次，不该对客户端开放。
     */
    OPS_UNAUTHORIZED(1009, "运维鉴权未通过"),

    // ---------- 2xxx 玩家 / 账号 ----------
    PLAYER_NOT_FOUND(2000, "玩家不存在"),
    PLAYER_DEVICE_INVALID(2002, "设备标识不合法"),
    PLAYER_NICKNAME_INVALID(2003, "昵称不合法"),
    /**
     * 身份校验未通过（B15 §三）。<b>「没带票据」与「票据不对」共用一个码是刻意的</b>：
     * 分成两个码等于对外送一个探针 —— 攻击者能据此判断某个 playerId 是否已有有效会话，
     * 也就是白送「哪些账号是活的」。差什么只进日志与 detail（detail 在 prod 不下发）。
     */
    PLAYER_IDENTITY_UNVERIFIED(2006, "身份校验未通过"),

    // ---------- 3xxx 城建（B03 填充） ----------
    CITY_MAIN_LEVEL_LOW(3000, "主城等级不足"),
    CITY_BUILDING_NOT_FOUND(3001, "建筑不存在"),
    CITY_BUILDING_MAX_LEVEL(3002, "建筑已达最高等级"),
    CITY_QUEUE_FULL(3003, "建造队列已满"),
    CITY_UPGRADING(3004, "建筑正在升级中"),
    CITY_PRE_BUILDING_LOW(3005, "前置建筑等级不足"),
    CITY_RESOURCE_LOW(3006, "资源不足"),
    CITY_GRID_INVALID(3007, "地块不可用"),
    CITY_MOVE_COOLDOWN(3008, "建筑换位冷却中"),

    // ---------- 4xxx 资源 / 背包（B04 填充） ----------
    RESOURCE_NOT_ENOUGH(4000, "资源不足"),
    ITEM_NOT_FOUND(4002, "道具不存在"),
    ITEM_NOT_ENOUGH(4003, "道具数量不足"),
    ITEM_CANNOT_USE(4004, "该道具当前不可使用"),

    // ---------- 5xxx 兵种 / 战斗（B05 填充） ----------
    UNIT_NOT_UNLOCKED(5000, "兵种尚未解锁"),
    UNIT_TRAIN_QUEUE_FULL(5001, "训练队列已满"),
    UNIT_NOT_ENOUGH(5002, "兵力不足"),
    BATTLE_REPORT_NOT_FOUND(5005, "战报不存在"),
    BATTLE_REPORT_EXPIRED(5006, "战报已过期"),

    // ---------- 6xxx 世界地图 / 行军 / 迷雾（B07 填充） ----------
    /** B07 §二 MarchErrorCode.MARCH_NO_TROOP。 */
    MARCH_NO_TROOP(6000, "未派兵"),
    /** B07 §二 MarchErrorCode.MARCH_OVER_LOAD。 */
    MARCH_OVER_LOAD(6001, "超出负载上限"),  // 契约预列：B07_世界大地图行军与迷雾.md（该文件列了这个码）。实现按负载上限截断采集量而不是拒绝出征（B07 §63），所以恒不抛
    /** B07 §二 MarchErrorCode.MARCH_OUT_OF_RANGE。 */
    MARCH_OUT_OF_RANGE(6002, "超出射程"),  // 契约预列：B07_世界大地图行军与迷雾.md（该文件列了这个码）。B07 没有射程上限这条规则，任意界内坐标都能去，所以恒不抛
    /** B07 §二 MarchErrorCode.MARCH_TARGET_PROTECTED。 */
    MARCH_TARGET_PROTECTED(6003, "目标处于保护状态"),
    /** B07 §二 MarchErrorCode.MARCH_POWER_OUT_OF_BAND，实际校验在 B08 战力圈层落地。 */
    MARCH_POWER_OUT_OF_BAND(6004, "战力圈层校验未通过"),
    MARCH_QUEUE_FULL(6005, "出征队伍已达上限"),
    MARCH_NOT_FOUND(6006, "行军不存在"),
    MARCH_STATE_INVALID(6007, "当前行军状态不允许该操作"),
    WORLD_COORD_INVALID(6008, "坐标不合法"),
    WORLD_TARGET_INVALID(6009, "目标不合法"),
    EXILE_ON_COOLDOWN(6011, "流亡迁城冷却中"),
    EXILE_TROOPS_AWAY(6012, "有队伍在外，无法迁城"),

    // ---------- 8xxx 战力圈层（B08 填充） ----------

    // ---------- 9xxx 章节副本（B09 填充） ----------
    STAGE_LOCKED(9000, "关卡尚未解锁"),
    STAGE_NOT_SWEEPABLE(9001, "关卡未达到扫荡条件"),
    STAGE_UNIT_RESTRICTED(9002, "兵种不符合本关限制"),

    // ---------- 10xxx 小队 / 联盟（B10 填充） ----------
    /** 小队不存在或已解散。与「未加入小队」分开：后者是正常状态，不该报错。 */
    SQUAD_NOT_FOUND(10000, "小队不存在或已解散"),
    SQUAD_ALREADY_IN(10001, "你已经在一个小队里"),
    SQUAD_FULL(10002, "小队人数已满"),
    SQUAD_NOT_MEMBER(10003, "对方不是本小队成员"),
    /** 小队尚未解锁。差什么放进 detail 字段：一个灰掉的入口不说明原因，玩家会以为是 bug。 */
    SQUAD_LOCKED(10004, "小队尚未解锁"),
    SQUAD_NAME_INVALID(10005, "小队名不合法"),
    SQUAD_NAME_TAKEN(10006, "小队名已被占用"),
    SQUAD_NOT_LEADER(10007, "只有队长能执行该操作"),

    ALLIANCE_NOT_FOUND(10010, "联盟不存在或已解散"),
    ALLIANCE_ALREADY_IN(10011, "你已经在一个联盟里"),
    ALLIANCE_FULL(10012, "联盟人数已满"),
    /** 主城等级 / 开服天数 / 曾加入过小队，三个前置任一不满足。具体差什么放进 detail。 */
    ALLIANCE_LOCKED(10013, "联盟尚未解锁"),
    ALLIANCE_CREATE_COST_LACK(10014, "金币不足，无法创建联盟"),
    ALLIANCE_NAME_INVALID(10015, "联盟名或标签不合法"),
    ALLIANCE_NAME_TAKEN(10016, "联盟名或标签已被占用"),
    /** 解散保护期内不能再创建（验收 7）。剩余秒数放进 detail，UI 据此画倒计时。 */
    ALLIANCE_DISBAND_PROTECTED(10020, "解散保护期内不能再次创建联盟"),
    ALLIANCE_APPLY_NOT_FOUND(10021, "申请不存在或已处理"),
    ALLIANCE_APPLY_DUPLICATED(10022, "你已经申请过这个联盟"),
    ALLIANCE_NOT_MEMBER(10023, "对方不是本联盟成员"),
    ALLIANCE_FUND_LACK(10024, "联盟资金不足"),
    ALLIANCE_LEVEL_MAX(10025, "联盟已达最高等级"),
    ALLIANCE_TECH_LEVEL_MAX(10026, "该科技已达当前联盟等级下的上限"),
    /** 捐献档位已用完（每日 3 档）。照实说明而不是笼统拒绝，否则玩家以为捐献坏了。 */
    ALLIANCE_DONATE_DAILY_LIMIT(10028, "今日捐献档位已用完"),
    ALLIANCE_DONATE_COST_LACK(10029, "捐献所需资源或金币不足"),
    ALLIANCE_CONTRIBUTION_LACK(10030, "贡献值不足"),
    ALLIANCE_NOT_LEADER(10031, "只有盟主能执行该操作"),
    ALLIANCE_TRANSFER_TO_SELF(10032, "不能把盟主转让给自己"),

    /**
     * 权限不足（验收 4）。
     *
     * <p>msg 刻意不写明「需要什么职位」：权限矩阵在 role_permission 表里，
     * 把结论写死在这条 msg 上就等于在代码里硬编码了一份权限（B10 禁止项）。
     * 具体缺哪个权限位由 detail 字段带上，那里是查表得出的。
     */
    SOCIAL_PERMISSION_DENIED(10040, "你没有执行该操作的权限"),
    /** 帮助次数用完。小队互助与联盟帮助共用一个每日额度，所以不分两条错误码。 */
    /** 聊天限流（验收 9）。走业务错误码而不是静默丢弃：假装发成功会让玩家以为对方收到了。 */
    SOCIAL_CHAT_RATE_LIMITED(10044, "同一句话发得太快，请稍后再试"),
    SOCIAL_CHAT_CONTENT_INVALID(10045, "消息内容不合法"),
    SOCIAL_CHAT_CHANNEL_INVALID(10046, "你没有在该频道发言的资格"),
    SOCIAL_SQUAD_COIN_LACK(10047, "小队币不足"),

    RALLY_NOT_FOUND(10050, "集结不存在或已结束"),
    RALLY_ALREADY_JOINED(10051, "你已经加入了这次集结"),
    RALLY_FULL(10052, "集结人数已满"),
    /** 人数不足下限（global.RALLY_MIN_SIZE）。一个人出发不叫集结，那只是普通出征。 */
    RALLY_MEMBER_NOT_ENOUGH(10053, "集结人数不足，无法出发"),
    RALLY_ALREADY_DEPARTED(10054, "集结已经出发"),
    RALLY_NOT_INITIATOR(10055, "只有发起人能取消这次集结"),
    RALLY_NO_TROOP(10056, "未承诺任何兵力"),
    RALLY_PREPARE_INVALID(10057, "准备时长不在允许区间内"),

    // ---------- 11xxx Bot 生态（B11） ----------
    /**
     * Bot 合规拦截。由 {@code BotRegistry.requireMayHoldOffice} 抛出，
     * 用于「这个职位只对真人开放」的红线：国家官职（B13 §2）。
     * msg 不解释判定依据 —— 客户端拿不到「为什么被拒」的细节是刻意的，
     * 那等于告诉调用方怎么绕过。
     */
    BOT_NOT_ELIGIBLE(11000, "该操作只对真人玩家开放"),

    /**
     * 频控拦截（B11 §五）：同一真人在 24 小时内被托管账号攻击的次数已达上限。
     *
     * <p>与 {@link #BOT_NOT_ELIGIBLE} 分开两个码：那一条是「这个位置不对 Bot 开放」（合规红线），
     * 这一条是「这个真人今天被打够了」（防骚扰）。合成一个码的话，日志里就分不清
     * 「Bot 试图当官」与「Bot 打了同一个真人第四次」—— 前者要改代码，后者等一等就好。
     */
    BOT_ATTACK_QUOTA_EXCEEDED(11001, "该玩家 24 小时内受到的攻击次数已达上限，请稍后再来"),

    // ---------- 12xxx 外围系统：任务 / 邮件 / 战报 / 红点（B12） ----------
    /**
     * 任务不存在（quest 表里没有这一行）。
     *
     * <p>与「没完成」「已领过」分开的三个码，客户端要用它们给出三句不同的话：
     * 前者是「这个任务已经不在了」（表改过或客户端缓存过期），后两者见下面两条。
     */
    QUEST_NOT_FOUND(12000, "任务不存在或已下线"),
    /** 任务还没完成就点了领取。detail 里带上 current/goalValue，客服一眼就能看出差多少。 */
    QUEST_NOT_COMPLETE(12001, "任务尚未完成，不能领取奖励"),
    /**
     * 奖励已经领过了。**与「没完成」分开是刻意的**：玩家看到「已领取」会去翻背包，
     * 看到「没完成」会去继续做任务 —— 混成一个码会让一半的人去错的地方找原因。
     */
    QUEST_ALREADY_CLAIMED(12002, "该任务的奖励已经领取过了"),
    /** 前置任务还没领，任务尚未解锁。客户端据此提示「先完成 X」。 */
    QUEST_LOCKED(12003, "前置任务尚未完成，该任务还没有解锁"),

    // ---------- 13xxx 国家 / 国战（B13） ----------
    NATION_NOT_FOUND(13000, "国家不存在或已解散"),
    NATION_NAME_TAKEN(13001, "国名已被占用"),
    /** 单个 kingdom 的国家数已达上限（global.NATION_MAX_PER_KINGDOM）。 */
    NATION_CREATE_LIMIT(13002, "本服国家数量已达上限"),
    /** 建国前置未满足：主城 16 级 + 开服 D14 + 当前在某联盟中。差哪一条放进 detail。 */
    NATION_LOCKED(13003, "建国条件未满足"),
    /** 被任命者不属于本国任何成员联盟（个人不能脱离联盟单独入籍，所以也不能被单独任命）。 */
    NATION_NOT_MEMBER(13004, "该玩家不属于本国的任何联盟"),
    NATION_OFFICE_FULL(13005, "该官职席位已满"),
    /**
     * 联盟入籍处于冷却期（B13 验收 2：退出或被开除后 24h 内不能再加入任何国家）。
     *
     * <p>为什么不并进 {@link #NATION_LOCKED}：客户端拿到这条要显示的是<b>倒计时</b>（还有多久能再申请），
     * 而 NATION_LOCKED 的语义是"建国前置未满足"，玩家能做的是去升主城等级或等开服天数 —— 两件不同的事
     * 共用一个码，客户端就只能猜文案。
     */
    NATION_JOIN_COOLDOWN(13006, "该联盟处于入籍冷却期"),
    /**
     * 目标国家可容纳的联盟数已满（上限由国家等级推导，见 {@code Nation.maxAllianceCount()}）。
     *
     * <p>玩家的可执行动作是<b>换一家</b>或等国家升级，与冷却期那条不同，所以码也不同。
     * 至于「该联盟已经属于另一个国家」这一因由，仍然复用 {@link #NATION_LOCKED} ——
     * 建国路径早就用它回答过同一件事（"你的联盟已经属于一个国家"），
     * 同一个事实在两个端点上给出两个码，客户端就得写两份分支。
     */
    NATION_ALLIANCE_FULL(13007, "该国可容纳的联盟数已满"),
    /**
     * 与对方国家有约在先（盟约或朝贡），不能发起攻击（B13 §5、验收 12）。
     *
     * <p>不复用 {@link #MARCH_TARGET_PROTECTED}：护盾是<b>针对这个目标、而且会自己到期</b>，
     * 玩家等就行；条约是<b>两个国家之间的关系</b>，等到天荒地老也不会变，能做的事只有让国王改外交
     * 或者换个国家打。把两件事揉成一个码，客户端就会对一个"根本等不来"的提示显示倒计时。
     *
     * <p>也不复用 {@link #SEASON_PVP_LOCKED}：禁战期是全服时间轴状态，<b>换目标没用</b>；
     * 而这一条恰恰是换目标就有用 —— 两者给玩家的可执行动作正好相反。
     */
    NATION_TREATY_PROTECTED(13008, "与对方国家有约在先，不能攻击"),
    /**
     * 同属一个国家的玩家之间不能互相攻击（2026-09-11 的口径裁决：国家内部不是无政府状态，
     * 国战的对手只能是别的国）。
     *
     * <p>不复用 {@link #NATION_TREATY_PROTECTED}：prod 只下发 {@code msg}、不下发 {@code detail}，
     * 所以玩家看到的整句必须准确 —— 对着一个同胞说"你们有约在先"是一句假话。
     * 两条的可执行动作确实相同（换个目标），但条约是会被撕掉的临时状态，而"同国"是结构事实，
     * 客户端要给出的提示与埋点也不该混在同一个码上。
     */
    NATION_SAME_KINGDOM(13009, "同属一个国家的玩家之间不能互相攻击"),

    /**
     * 国库余额不足以支付这一笔支出（B13 §3）。
     *
     * <p><b>不做部分出账</b>：不够就整笔拒绝。半笔俸禄比不发更难解释 ——
     * 领到一半的人会以为系统算错了，而账本上那一行又是"完整的一笔"。
     */
    NATION_TREASURY_NOT_ENOUGH(13010, "国库余额不足，无法支付这笔支出"),

    // ---------- 14xxx 赛季（B14） ----------
    /**
     * 当前赛季阶段禁止玩家间攻击（备战期与休赛期）。
     *
     * <p>与「目标处于保护状态」分开是刻意的：护盾是<b>针对这个目标</b>的，换个目标或等盾结束
     * 就能打，玩家有可执行的动作；而禁战期是<b>全服时间轴状态</b>，换目标没用，
     * 玩家唯一能做的是去打野或等赛季开始。两条揉成一个码会让提示说出一句假话。
     */
    SEASON_PVP_LOCKED(14000, "当前赛季阶段禁止玩家间攻击"),

    // ---------- 15xxx 商业化 / 支付 / 合规（B15） ----------
    /**
     * 订单号重复、订单不存在、或订单不在补单队列里。三种情况共用一个码是刻意的：
     * 对客户端而言它们都是「这笔订单现在不能这么处理」，而区分它们只会给探测订单号的人提供信息。
     */
    PAY_ORDER_DUPLICATE(15000, "订单状态不允许该操作"),
    /**
     * 回调验签失败。<b>必须拒绝而不是忽略</b>：不验签的回调端点等于任何人 POST 一下就能给自己发货。
     * detail 不回传签名内容 —— 那等于把校验材料交给攻击者。
     */
    PAY_SIGN_INVALID(15001, "支付签名校验失败"),
    /**
     * 未成年限额（B15 §三 合规）。<b>这是友好提示而不是硬拦截</b>：
     * msg 会被客户端直接展示，所以它必须说明限额是多少、什么时候恢复，
     * 而不是一句冷冰冰的「不允许购买」—— 后者会让未成年玩家以为是 bug 而反复重试。
     */
    PAY_MINOR_LIMIT(15002, "本次支付超出未成年人付费限额。可选择更小的档位，或等下月额度刷新；如需帮助请通过设置页的客服入口咨询"),
    PAY_PRODUCT_OFFLINE(15003, "商品已下架或暂不可购买"),
    /**
     * 商店里没有这一行（或行 id 为空）。<b>不复用 {@link #PAY_PRODUCT_OFFLINE}</b>：
     * 那个码说的是「这笔真实支付买不了」，而这里是「你点的这个货架行不存在」——
     * 后者几乎总是客户端拿了一份过期货架（热更表之后），提示应当引导刷新而不是引导客服。
     */
    SHOP_ROW_NOT_FOUND(15004, "商店里没有这一行"),
    /**
     * 限购已满（`shop.limitCount` + `refreshType`）。单独成码的理由与抽卡次数上限同一条：
     * 客户端要能把它显示成「本周已用完，X 小时后再来」而不是一句「操作失败」。
     */
    SHOP_LIMIT_REACHED(15005, "该商品的限购次数已用完"),
    /**
     * 这一页签当前不开放兑换。今天只有一个成员用到：`SEASON_COIN` ——
     * 赛季币的用途口径还没裁决（收口清单 #6b），而唯一那一行的货品效果也没有定义数值（#19）。
     * <b>这不是「未实现」也不是「下架」</b>：玩家能看见货架，只是还不能换，
     * 所以提示必须说清是「还没开」，否则玩家会以为自己条件不够。
     */
    SHOP_CURRENCY_CLOSED(15006, "该货币的兑换尚未开放"),

    // ---------- 16xxx 性能 / 埋点 / 发布（B16） ----------
    /**
     * 整批埋点被拒。注意<b>单条事件不合法不走错误码</b>：那会计入响应的 failed 计数，
     * 因为一批里有一条脏数据就让整批失败，等于用一条脏数据换掉九条好数据 ——
     * 而埋点是尽力而为的数据，不是账目。只有整批都不该存在（空批、超体积）才拒绝。
     */
    TRACK_BATCH_EMPTY(16000, "上报的事件批次为空"),
    CRASH_REPORT_INCOMPLETE(16003, "崩溃上报内容不完整"),
    RELEASE_VERSION_MISSING(16004, "缺少客户端版本号");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int code() {
        return code;
    }

    /** 中文提示，可直接下发客户端。 */
    public String msg() {
        return msg;
    }

    public boolean isSuccess() {
        return code == 0;
    }

    /** 按 code 反查枚举，找不到抛异常（与配置读取同策略：绝不返回 null）。 */
    public static ErrorCode of(int code) {
        for (ErrorCode e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("未定义的 ErrorCode: " + code);
    }
}
