package com.ironoath.web.nation;

import java.util.Optional;

import com.ironoath.core.nation.WarScoreBoard;

/**
 * 职责：国战积分板的存储端口（B13 §一 §7 的承载）。
 * 依赖：game-core 的 {@link WarScoreBoard}。
 *
 * <p><b>为什么端口在 game-web 而不在 game-core</b>：与 {@code NationStore}、
 * {@code BattleReportStore}、{@code TrackEventStore} 同一条理由 —— game-core 里没有任何逻辑需要
 * 「取出当前这一场仗」或「把所有战事列出来」，那是服务层与运维的诉求。把端口下沉到 core
 * 只会让 core 多一个没人用的抽象（而 {@code WarScoreBoard} 的类注释本来就写着
 * 「由 game-web 在国战开始时载入、结束时落盘一次」—— 承载的责任从一开始就落在这一层）。
 *
 * <p><b>{@link WarScoreBoard} 是可变对象</b>（击杀、关卡、疲劳都改它自己），所以约定与 {@code Nation} 一致：
 * 调用方改完必须 {@link #save} 写回，而<b>两套实现读出来都是副本</b>
 * （{@code InMemoryNationStore} 2026-09-10 那次教训：原先直接持有同一个引用，
 * 于是「忘记写回」在 dev 下看不出来、换 Mongo 就丢改动）。差异由 {@code WarStoreEquivalenceTest} 守着。
 *
 * <p><b>为什么这一份不带乐观锁版本，而 {@code NationStore.save} 带</b>：写形状不一样。
 * 国家那一族是<b>多个官员各改各的字段</b>（任命、外交、周税），且 {@code PlayerLock} 按玩家加锁，
 * 拦不住两个不同联盟同时改同一个国家 —— 只有版本号能把「后写的整档覆盖先写的」变成一次响。
 * 国战板的既定形状是内核类注释那一条：<b>开战载入、结束落盘一次</b>，中间不落盘
 * （禁止项明写「不要让国战积分在数据库层聚合」，200 QPS 的行军事件若在库里做 SUM，MongoDB 先崩）。
 * 所以本切片没有「两条读-改-写并发抢同一档」的形状，版本没有对手可挡。
 *
 * <p><b>这一条不适用于下一切片，届时必须回来改这里</b>：一旦击杀累计改成「中途定期 flush」
 * 或多实例各自累加再合并，端口就必须加版本（与 {@code CityRepository} 那四个版本化仓储同一条契约），
 * 或改成存储层的原子累加口。<b>现在留的口子是</b>：{@code save} 是整档替换，两个实例各持一份板子
 * 先后落盘，后写的会整块盖掉先写的积分与疲劳，而全链路不报错。这条窗口记在收口清单，不藏在注释里。
 */
public interface WarStore {

    /**
     * 存档主键的推导口径：<b>两套实现必须用这一个函数</b>，各写一份就会在某处漂掉
     * （内存版按 A 键、Mongo 按 B 键时，等价测试照样全绿，因为它两边都读自己写的键）。
     *
     * <p>为什么用 {@code startedAt} 而不是再造一个 warId：{@code WarScoreBoard} 里没有 id 字段，
     * 而 {@code startedAt} 是 {@code final} 的 —— 主键因此<b>永不受写操作影响</b>，
     * 一次 {@code save} 不可能把这一档改到另一个键上去（那等于把历史挪了个位置）。
     * 同毫秒开两场在现实中不成立（同一时刻只立一场），真撞上时 {@link #insertIfAbsent} 返回 false，
     * 那正是「不该有两场」的正确反应。
     */
    static String documentIdOf(WarScoreBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null：没有板子就推不出主键");
        }
        return "war_" + board.startedAt();
    }

    /**
     * 原子地建档：<b>只在没有人写过这一场时</b>插入（{@code _id} 唯一约束）。
     *
     * @return false 表示这一场已经建过 —— 与 {@code NationStore#insertIfAbsent} 同一条：
     *         写成「先查后插」会插出两份同开场的战事，两份各自算各自的积分与疲劳，
     *         而玩家会看到两个进度条
     */
    boolean insertIfAbsent(WarScoreBoard board);

    /**
     * 整档落盘（内核类注释那一条「结束时落盘一次」）。
     *
     * <p><b>不许静默插入</b>：建档只走 {@link #insertIfAbsent}。理由与 {@code NationStore#save} 同一条 ——
     * 静默插入会让「读不到就新建一份」这种错误写法的并发后果变成两份档，而不是一个异常。
     *
     * @throws IllegalStateException 这一场还没建过（调用方走错了方法）
     */
    void save(WarScoreBoard board);

    /**
     * 最近开战的那一场（{@code startedAt} 最大的一份），读端点用它。
     *
     * <p><b>「最近一场」在这一切片是展示口径而不是领域规则</b>：现在没有任何写入路径，
     * 生产上这一口恒空（表现是 {@code GET /nation/war} 回 {@code hasWar=false}）。
     * 等开战那一步落地，如果同一时刻只允许一场未结束的仗，这里应当收紧成
     * 「那一场未结束的」而不是「最新那一场」—— 收紧点在这一个方法里，不在调用方，
     * 所以调用方不需要跟着改。
     *
     * @return 重建出来的板子（规则现取，见 {@link WarRulesAssembler}）；一份都没有时为 empty
     */
    Optional<WarScoreBoard> findLatest();

    /**
     * 测试辅助：清空。
     */
    void clear();
}
