package com.ironoath.web.nation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ironoath.core.nation.Nation;

/**
 * 职责：国家存储端口。
 * 依赖：game-core 的 {@link Nation}。
 *
 * <p><b>为什么端口在 game-web 而不在 game-core</b>：与 {@code BattleReportStore}、
 * {@code TrackEventStore} 同一条理由 —— game-core 里没有任何逻辑需要「列出全部国家」，
 * 那是服务层与运维的诉求。把端口下沉到 core 只会让 core 多一个没人用的抽象。
 *
 * <p><b>Nation 是可变对象</b>（任命、税收、外交都改它自己），所以约定与 {@code ArmyState} 一致：
 * 调用方改完必须 {@link #save} 写回。<b>两套实现读出来都是副本</b>（内存版原先直接持有同一个引用，
 * 于是"忘记写回"在 dev 下看不出来、换 Mongo 就丢改动，2026-09-10 已改掉，见收口清单 #54），
 * 所以这条写回义务在 dev 与生产上是同一件事 —— 差异由 {@code NationStoreEquivalenceTest} 守着。
 */
public interface NationStore {

    /**
     * 带乐观锁落库：<b>只有 {@code nation.version()} 与库里那一版相同才写</b>。
     *
     * <p>为什么必须带版本：官职、外交、周税全是"读-改-写"，而 {@code PlayerLock} 是<b>按玩家</b>
     * 加锁的 —— 两个不同联盟的官员同时操作同一个国家时，两把锁互不相干。没有版本号就是
     * 后写的整档覆盖先写的（表现是"我的任命没了"，且全链路不报错）。
     *
     * <p>与 {@code CityRepository} 那四个版本化仓储同一条契约；内存版与 Mongo 版必须给出
     * 同一套结果（{@code NationStoreEquivalenceTest} 里那三条版本用例）。
     *
     * @param nation          要写入的国家（其版本等于调用方读到的那一版）
     * @param expectedVersion 调用方读到的版本，而不是 {@code nation.version()} 之外的任何数
     * @return 写入后的新版本
     * @throws IllegalStateException 版本不匹配（有并发写入，调用方必须重读重试），
     *                               或该国家还不存在
     */
    long save(Nation nation, long expectedVersion);

    /**
     * 原子地建档：<b>只在没有人写过这个国家时</b>插入（{@code _id} / 键唯一约束）。
     *
     * @return false 表示已存在 —— 与 {@code PlayerRepository#insertIfAbsent} 同一条：
     *         写成"先查后插"会插出两份国家档，两份各自改各自的官职与国库
     */
    boolean insertIfAbsent(Nation nation);

    /**
     * 原子地结算一国本周的税（B13 §3 验收 5）。
     *
     * <p><b>为什么这条能力在仓储接口上，而不是 service 里 findById → 改 → save</b>：
     * {@code Nation.collectTax} 的幂等靠「同一周键只收一次」，而判断与写入是两步 ——
     * 两个人同时操作国家时各自都会拿到「本周还没缴」的状态，这一周的税就被收两遍。
     * 领域层的周键挡得住<b>重放</b>，挡不住<b>并发</b>；只有把「判断 + 写入」收进存储层的
     * 同一个临界区才是对的。
     *
     * <p><b>结算会推进版本号</b>，所以"结算 → 改自己手里那份旧副本 → save"这个顺序不再能盖掉那笔钱
     * —— 第二次写会因为版本过期而被拒（两侧同一条，由 {@code NationStoreEquivalenceTest} 钉住）。
     * 但调用方<b>仍然要重读一次</b>才能把那笔入账显示给玩家：读端口给的是副本，
     * 手里那份的国库还停在结算前 —— 少一次重读的表现不再是丢钱，而是"看一眼国库，数字没变"。
     * 正确顺序仍是 <b>settle → findById 重读 → 改 → save</b>，见 {@code NationAppService#settleTax}。
     *
     * <p><b>参数是时刻而不是周键</b>：周键由实现内部按 {@code WeekKey.number(now)} 现算。
     * 让调用方各传一份 key 与一个 now，就留下了"两者不一致"这种不报错的状态，
     * 而周税日志的 {@code at} 必须与它归属的那一周同源。
     *
     * @param nationId 国家 id
     * @param now      本次结算的时刻（服务端时间）。全项目唯一的"什么叫本周"实现是
     *                 {@code WeekKey}，这里只传时刻、键在实现里算
     * @return 本次实际入账金额；0 表示本周已收过、国库已满或国家不存在
     */
    long settleWeeklyTax(String nationId, long now);

    Optional<Nation> findById(String nationId);

    Optional<Nation> findByName(String name);

    /** 某个联盟所属的国家（联盟 ⊂ 国家，一个联盟最多属于一个国家）。 */
    Optional<Nation> findByAlliance(String allianceId);

    /**
     * 一批联盟各自所属的国家（allianceId → 国家），口径与逐个 {@link #findByAlliance} 完全一致。
     *
     * <p>国家榜同样是投影（{@code RankBoardService.projectOrgBoard}）：它要先知道"榜上这些人各在哪个
     * 盟"，再知道"这些盟各在哪个国"，两跳都要按整张 POWER 榜的长度问一遍才算得出分。第一跳走
     * {@code SocialStore.alliancesOf}，第二跳就是这里。
     *
     * <p><b>值里带着名字，所以一次就够</b>：投影同时要国名，逐个 {@code findById} 会把第二趟点查
     * 留在同一个循环里。国家总数上限是 {@code global.NATION_MAX_PER_KINGDOM}（个位数，见
     * {@link #all()} 的注释），所以这一口省下的往返虽然不多，却把"这个类只剩批量口"这条不变量补齐了
     * —— 计数判据（{@code RankOrgBoardQueryCountTest}）正是靠它写成"点查为零"。
     *
     * <p><b>约定</b>：没入籍的联盟直接不出现在结果里（不返回 null 值），null 或空集合返回空表，
     * 与 {@code SocialStore.alliancesOf} 同一条口径。值是副本，与 {@link #findByAlliance} 一样。
     */
    Map<String, Nation> nationsByAlliance(Collection<String> allianceIds);

    /** 全部国家。数量上限是 global.NATION_MAX_PER_KINGDOM（4），所以这不是一个大列表。 */
    List<Nation> all();

    /** 测试辅助：清空。 */
    void clear();
}
