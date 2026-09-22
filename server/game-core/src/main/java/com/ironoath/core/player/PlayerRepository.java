package com.ironoath.core.player;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 职责：玩家存档仓储端口（六边形架构的抽象侧）。
 * 依赖：无（纯接口）。
 *
 * <p>定义在 game-core 而非 game-web，是为了让玩法逻辑可以脱离 MongoDB 跑单测：
 * game-web 提供 MongoDB 实现，单测提供内存实现，两者对玩法代码完全等价。
 *
 * <p>并发约定（B00 Java 五大技术陷阱第 3 条）：禁止「先查后改」扣资源。
 * 写入必须走分布式锁 + 事务 + {@code requestId} 幂等，具体由实现类保证；
 * {@link #save} 采用乐观锁（{@link PlayerSave#version()}），版本不匹配即抛异常让上层重试。
 */
public interface PlayerRepository {

    /** 按玩家 id 查存档。 */
    Optional<PlayerSave> findByPlayerId(String playerId);

    /** 按设备 id 查存档。同一设备重复 init 必须返回同一份存档（B01 验收 11）。 */
    Optional<PlayerSave> findByDeviceId(String deviceId);

    /**
     * 批量按玩家 id 查存档，返回 playerId -> 存档。
     *
     * <p>B08 的目标搜索要读入半径内的全部候选（昵称、活跃时刻、护盾、库存富度、匹配战力），
     * 逐个 {@link #findByPlayerId} 在候选池上千时就是上千次查询 ——
     * 一次搜索请求打出上千次存储往返是不能接受的，而这件事只有批量端口能在存储层解决。
     *
     * <p><b>约定</b>：查不到的 id 直接不出现在结果里，不返回 null 值、不抛异常。
     * 候选池来自世界仓储的城位置，而删号不会同步清理坐标（那是 B14 赛季重置的事），
     * 所以「有城没人」是一个正常状态，调用方必须自己处理缺失。
     *
     * @param playerIds 要查的玩家 id；null 或空集合返回空 map
     */
    Map<String, PlayerSave> findByPlayerIds(Collection<String> playerIds);

    /**
     * 批量按玩家 id 查<b>列表行所需的那几项</b>，返回 playerId -> 投影，
     * 语义与 {@link #findByPlayerIds} 逐字相同（缺失的 id 不出现、null 或空集合返回空 map），
     * 唯一区别是返回的是 {@link PlayerBrief} 而不是整份存档。
     *
     * <p><b>为什么要单独开这个口</b>：{@link #findByPlayerIds} 省的是<b>往返次数</b>
     * （N 个人一趟 $in 拿回来），但那一趟搬回来的是 N 份<b>整档</b>。批量列表类调用方
     * （联盟成员 150 人、关注列表 50 人、一页申请 50 人、一个块内的城）只要昵称、城等、
     * 活跃时刻、展示战力这几项，剩下的资源表 / PVP 账本 / 荣耀 / 引导 / 付费权益 / 科技 /
     * 礼包弹窗 / 头像框集合全是被白搬白反序列化的字节 —— 而存档的字段数随玩法批次只增不减，
     * 这一趟的代价跟着涨，列表行却一字不变。
     *
     * <p><b>它省的不是往返</b>：Mongo 侧与 {@link #findByPlayerIds} 打的是同一个
     * {@code _id $in}，只是带上了字段投影。所以别指望用计数判据看出"少搬了整档"，
     * 那一维由本口与 {@link PlayerBrief} 的<b>类型</b>守住：返回值里没有整档，
     * 调用方就连编都编不出"顺手多读一项"。
     *
     * <p><b>什么时候该用 {@link #findByPlayerIds}</b>：需要玩法状态本身的时候。
     * 目标搜索要 PVP 账本算护盾与暴虐值、Bot 校准要战力三元组做均值样本、
     * 暴露城要按天补暴虐值衰减 —— 那些是真的在读档，不是在报名字，不要为了少几个字节改到这里来。
     *
     * <p><b>两份实现必须同语义</b>（内存版给 dev 与全部单测、Mongo 版给生产），
     * 由 {@code PlayerBriefEquivalenceTest} 钉住：同名的字段接错来源、投影列漏一项，
     * 症状都是"单测全绿、生产那列是 0"。
     *
     * @param playerIds 要查的玩家 id；null 或空集合返回空 map
     */
    Map<String, PlayerBrief> findBriefs(Collection<String> playerIds);

    /**
     * 只读<b>建档时刻</b>（注册时刻）—— 活动窗口锚点这类"只要一个时间戳"的读法用这条，
     * 不要用 {@link #findByPlayerId}：那为一位数搬回整份存档（资源表、PVP 账本、科技、
     * 礼包弹窗账本、已拥有的头像框集合），而它在锚点上没有任何用处。
     *
     * <p>与 {@link #findBriefs} 的分工不重叠：那一条是<b>批量列表行</b>（昵称 / 城等 / 活跃 / 展示战力），
     * 这一条是<b>单人的一个时间戳</b>。把 {@code createdAt} 塞进 {@link PlayerBrief} 看似省一个方法，
     * 实际会让每一张列表都白投一列，而那四列是判据点名的字段集合 —— 类型上就该看得见"没人用它"。
     *
     * <p><b>为什么这一位可以不加锁也不 copy</b>：建档时刻是身份字段，建号写一次之后不再有写手
     * （{@code PlayerDocument} 上那条 mongo-save-exempt 注释就是这件事的登记），
     * 所以不存在 {@link #findBriefs} 里 {@code lastLoginAt} 那种与并发登录抢读的撕裂风险。
     *
     * @param playerId 玩家 id；null / 空白 / 查不到都返回 {@link OptionalLong#empty()}
     */
    OptionalLong findCreatedAt(String playerId);

    /**
     * 原子插入：仅当 deviceId 尚未存在时才写入。
     *
     * <p>必须由存储层的唯一索引保证原子性，不能用「先查后插」——
     * 两个并发请求会同时查到不存在，然后都插入，产生一个设备两份存档。
     *
     * @return true 表示本次真的插入了；false 表示已存在（调用方应改为读取现有存档）
     */
    boolean insertIfAbsent(PlayerSave save);

    /**
     * 保存修改。
     *
     * @throws IllegalStateException 当乐观锁版本不匹配（说明有并发写入），调用方应重读后重试
     */
    void save(PlayerSave save);

    /**
     * 定向更新登录时间戳 —— <b>不做版本校验</b>。
     *
     * <p>为什么不走 {@link #save}：{@code lastLoginAt} 是单调、可交换的字段，
     * 并发登录时「读-改-写 + 乐观锁」必然让其中一方冲突失败，玩家看到的是「登录失败」。
     * 而登录失败是最不该出现的错误 —— 它发生在玩家刚点开游戏的第一秒。
     * 乐观锁应当只保护玩法状态（资源、建筑、兵力），不该保护一个时间戳。
     *
     * <p>实现要求：用存储层的条件更新保证单调（如 MongoDB 的
     * {@code filter: lastLoginAt < now, update: $set lastLoginAt = now}），
     * 不要先读出来再写回。存档不存在时静默忽略（可能刚被删号）。
     *
     * @param playerId 玩家 id
     * @param now      服务端当前时间戳
     */
    void touchLogin(String playerId, long now);
}
