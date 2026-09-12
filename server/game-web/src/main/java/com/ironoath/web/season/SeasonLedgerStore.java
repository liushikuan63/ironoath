package com.ironoath.web.season;

import com.ironoath.core.player.PlayerGlory;
import com.ironoath.core.season.SeasonTier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：赛季账本的存储端口 —— 记录"这一季这个人结算过、拿到了什么"。
 * 依赖：game-core 的 {@link SeasonTier}（纯枚举）。
 *
 * <p><b>它同时是付款闸门，这是它必须持久的唯一充分理由</b>：结算的幂等原本只记在
 * {@code SeasonSettlement} 的进程内 map 里，重启即空；而运维再触发一次结算用的是新的
 * {@code requestId}（幂等键只管"同一个请求重放"，管不了"换一次请求重跑"）。
 * 于是"重启 + 再结算一次"就会<b>给同一批人再发一遍金币</b> —— 发出去的收不回来。
 * 账本里那条记录恰好就是"这一季已经付过"的事实，所以：
 * <ul>
 *   <li>{@link #recordIfAbsent} 必须是<b>原子</b>的（内存用 putIfAbsent、Mongo 靠 {@code _id}
 *       唯一约束），返回 false 表示已经有人记过 —— 调用方据此<b>不再发奖</b>；</li>
 *   <li>记账在发奖<b>之前</b>。失败方向选"记了但没发出去"：那笔可由补偿队列与客服补齐，
 *       而"发了两次"没有任何补偿能追回（与支付域同一条取舍）。</li>
 * </ul>
 *
 * <p><b>为什么单独一个存储而不往 {@code PlayerSave} 里加字段</b>：B14 §4 的硬约束是
 * 「绝不能让赛季数据混入玩家主存档表，否则几个赛季后主表会膨胀到不可维护」。
 * 把这条约束做成类型边界比写成注释可靠 —— 在这里没有"往主存档加字段"的入口。
 *
 * <p><b>赛季币记在这里而不是资源表</b>：{@code resource.json} 没有 SEASON_COIN 这一行，
 * 而 global 表里 {@code SEASON_COIN_PER_RANK} 的 todo 明写「赛季币的用途（荣耀商店？）尚未定义」。
 * 造一个不存在的资源 id 走发放器会直接报错，把它塞进金币等于让两种货币混账；
 * 所以如实记在赛季账本上，等商店批次来定义它的消耗端。
 */
public interface SeasonLedgerStore {

    /** 一条赛季结算记录。 */
    record Record(String playerId, int rank, SeasonTier.Tier tier, long seasonCoin, long gold) {
    }

    /**
     * 原子地记一条结算记录。
     *
     * @return true 表示这条是本次记上的（调用方可以发奖）；false 表示这一季这个人已经有账
     *         （<b>调用方必须因此跳过发奖</b>，否则重启后重跑结算就是重复发钱）
     */
    boolean recordIfAbsent(String seasonId, Record record);

    /** 一条都没有记上时覆盖旧记录。只给测试与人工改账用；结算路径一律走 {@link #recordIfAbsent}。 */
    void overwrite(String seasonId, Record record);

    /** @return 没有记录时返回 null（不是抛，也不是空对象 —— "没结算过"是一个必须能表达的状态） */
    Record find(String seasonId, String playerId);

    /** 一个赛季的完整结算记录，按 playerId 索引。归档与申诉还原读它。 */
    Map<String, Record> seasonRecords(String seasonId);

    Set<String> seasonIds();

    /** 测试与分服用：清空账本。 */
    void clear();

    /**
     * 写入侧的入参校验。放在端口上而不是两份实现里各写一遍，理由很具体：
     * 同一条报错文案抄两份，早晚会被改得不一样，而"同一个非法调用在 dev 与生产报出不同的话"
     * 正是这一族存储反复在防的东西（本文件其余部分两侧共用 default 方法同理）。
     *
     * <p>Mongo 版尤其需要它：{@code seasonId} 与 {@code playerId} 会拼进 {@code _id}，
     * null 不会被拒绝而是变成一条 {@code "null:null"} 的垃圾档 —— 错了还留痕，
     * 之后既查不出来也没人能删干净。
     */
    static void requireWriteKey(String seasonId, Record record) {
        if (record == null) {
            throw new IllegalArgumentException("结算记录不得为 null");
        }
        if (seasonId == null || seasonId.isBlank()) {
            throw new IllegalArgumentException("seasonId 不得为空：它是账本的分片键，"
                    + "空值在 Mongo 侧会被拼进 _id 变成一条谁也查不到的档");
        }
        if (record.playerId() == null || record.playerId().isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空：没有归属的结算记录无法申诉也无法核对");
        }
    }

    /**
     * 本赛季已入账的赛季币余额（消耗端尚未定义，先如实累计）。
     *
     * <p>默认实现走一次 {@link #find}：两套实现因此共用同一条"没有记录就是 0"的口径，
     * 不必各写一遍（各写一遍的结局是两边对"没记录"的处理不一样，而那不报错）。
     */
    default long seasonCoinBalance(String seasonId, String playerId) {
        Record record = find(seasonId, playerId);
        return record == null ? 0L : record.seasonCoin();
    }

    /**
     * 荣耀三件套。
     *
     * <p>做成默认方法而不是各实现一遍：荣耀等级 = 参与过的赛季数、历史最高段位取各季最好的一次，
     * 这条算法属于业务口径，写两遍就会漂移。赛季数是归档保留数（个位数），
     * 所以逐季点查的代价可以接受，换成一次全表扫描反而是给未来埋雷。
     *
     * <p><b>徽章只算这个人自己有结算记录的那些季</b> —— 早先的实现把「全部赛季 id」当成徽章集合，
     * 等于给一个中途才进服的人发满了历史赛季的章；那不是"荣誉"，是随手发的通货。
     * 返回类型与主存档那份缓存共用 {@link PlayerGlory}，派生与缓存之间不再需要手写转换。
     */
    default PlayerGlory gloryOf(String playerId) {
        int seasons = 0;
        SeasonTier.Tier best = null;
        List<String> badges = new java.util.ArrayList<>();
        for (String seasonId : seasonIds()) {
            Record record = find(seasonId, playerId);
            if (record == null) {
                continue;
            }
            seasons++;
            badges.add(seasonId);
            if (best == null || record.tier().ordinal() > best.ordinal()) {
                best = record.tier();
            }
        }
        return new PlayerGlory(seasons, best == null ? SeasonTier.Tier.BRONZE : best, badges);
    }

    /** 按插入顺序整理的记录表（两个实现都给出稳定顺序，归档列表才不会随机变）。 */
    static Map<String, Record> ordered(Map<String, Record> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
