package com.ironoath.web.store.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ironoath.core.nation.Nation;
import com.ironoath.web.nation.NationStore;

/**
 * 职责：国家存储的内存实现（dev / 单测零依赖启动）。
 * 依赖：{@link NationStore} 端口。
 *
 * <p>与其它内存存储同一套约定：进程重启即丢，生产必须设 {@code ironoath.storage=mongo}
 * （那笔债见上线检查清单 §四 2）。
 *
 * <p><b>两个索引都在 {@link #save} 里重建，不单独提供绑定/解绑方法</b>：
 * 国家与联盟的关系只由 {@code Nation} 自己维护（admitAlliance / removeAlliance），
 * 若再给存储开一个「绑定某联盟」的入口，就有了两个可以改这份关系的地方 ——
 * 而两处改动的症状是「联盟退国了，但按联盟还能查到那个国家」，玩家会看到自己同时属于两边。
 */
public final class InMemoryNationStore implements NationStore {

    private final Map<String, Nation> byId = new LinkedHashMap<>();
    private final Map<String, String> idByName = new LinkedHashMap<>();
    private final Map<String, String> idByAlliance = new LinkedHashMap<>();
    /** 规则不是存档的一部分，所以每次"把快照拼回一个能用的国家"都要现取（热更立刻生效）。 */
    private final com.ironoath.web.nation.NationRulesAssembler rules;

    public InMemoryNationStore(com.ironoath.web.nation.NationRulesAssembler rules) {
        this.rules = java.util.Objects.requireNonNull(rules, "rules 不得为 null");
    }

    /**
     * 原子地结算一国本周的税（B13 §3 验收 5）。
     *
     * <p><b>为什么收税动作放在存储层做，而不是让 service 改了再 save</b>：
     * {@code collectTax} 的幂等靠「同一个周键只收一次」这个判断，而判断与写入是两步。
     * 两个官员同时操作国家时，各自都会拿到一份「本周还没缴」的状态、各自加一遍、再各自保存 ——
     * 这一周的税就被收了两遍。领域层的幂等键挡的是<b>重放</b>，挡不住<b>并发</b>。
     * 本方法在 store 的监视器内直接改在册对象，判断与写入因此是不可分割的一步。
     *
     * <p><b>自从读改成返回副本（2026-09-10），调用方必须重读一次才能看到这笔入账</b>：
     * 原先 {@code findById} 返回的就是这个被改过的实例，所以 {@code view} 与 {@code appoint}
     * 可以"顺手"看到它；现在不重读就会拿到结算前的国库，而 {@code appoint} 那条更糟 ——
     * 把结算前那份副本 save 回去会把刚入账的税整笔覆盖掉。这条耦合由
     * {@code NationStoreEquivalenceTest} 在两套实现上钉住（内存版返回活对象的时代它测不出来）。
     *
     * <p>改的是国库存款、上次缴税周键与一条审计日志；国名与成员联盟索引都不受影响，所以不重建索引。
     *
     * @return 本次实际入账金额（0 表示本周已收过、国库已满或国家不存在）
     */
    @Override
    public synchronized long settleWeeklyTax(String nationId, long now) {
        Nation stored = byId.get(nationId);
        if (stored == null) {
            return 0L;
        }
        // 周键在这里算，不在调用方算：传进来的只有一个时刻，日志的 at 与它归属的周因此必然同源
        long weekBefore = stored.snapshot().lastTaxWeekKey();
        long credited = stored.collectTax(com.ironoath.common.time.WeekKey.number(now), now);
        if (stored.snapshot().lastTaxWeekKey() != weekBefore) {
            // 真的换了一版才推进版本（哪怕因为国库满了收到 0，"本周已结清"这件事本身也是状态变化）。
            // 不推进的话，一个还拿着旧版本的任命请求会照样写进来，把这一行的周税整档盖掉
            stored.incrementVersion();
        }
        return credited;
    }

    @Override
    public synchronized boolean insertIfAbsent(Nation nation) {
        if (nation == null) {
            throw new IllegalArgumentException("nation 不得为 null");
        }
        // 国名这条唯一性规则必须同时管住建档与更新：原先它只在 save 里查，
        // 于是"两个国家同名"可以绕 insertIfAbsent 进得来 —— 而 Mongo 侧的 name 是唯一索引，
        // 那里插不进来。两侧必须同一条，否则 dev 全绿、生产第一次并发建国就炸。
        String nameOwner = idByName.get(nation.name());
        if (nameOwner != null && !nameOwner.equals(nation.id())) {
            throw new IllegalStateException("国名已被其它国家占用，无法保存：" + nation.name()
                    + "。国名是 findByName 的唯一入口，两个国家共用一个名字等于其中一个从名字上消失");
        }
        if (byId.putIfAbsent(nation.id(), detached(nation)) != null) {
            return false;
        }
        index(nation);
        return true;
    }

    @Override
    public synchronized long save(Nation nation, long expectedVersion) {
        if (nation == null) {
            throw new IllegalArgumentException("nation 不得为 null");
        }
        Nation stored = byId.get(nation.id());
        if (stored == null) {
            throw new IllegalStateException("国家不存在，无法更新：nationId=" + nation.id()
                    + "。建档请走 insertIfAbsent —— save 静默插入会让并发建档插出两份档");
        }
        if (stored.version() != expectedVersion) {
            throw new IllegalStateException("乐观锁冲突：nationId=" + nation.id()
                    + "，存储版本=" + stored.version() + "，提交版本=" + expectedVersion
                    + "。请重读后重试：两个官员同时改同一个国家时，PlayerLock 是按玩家的，拦不住这里");
        }
        // 国名被别的国家占着就必须拒绝：原先这里直接 put，等于把前一个国家从"按名字查"这条路上
        // 悄悄抹掉（它还能按 id 查到，但 findByName 永远只回后一个）。Mongo 版的 name 是唯一索引，
        // 会直接抛，所以两边同一条：响亮拒绝，文案也一致
        String nameOwner = idByName.get(nation.name());
        if (nameOwner != null && !nameOwner.equals(nation.id())) {
            throw new IllegalStateException("国名已被其它国家占用，无法保存：" + nation.name()
                    + "。国名是 findByName 的唯一入口，两个国家共用一个名字等于其中一个从名字上消失");
        }
        Nation next = detached(nation);
        next.incrementVersion();
        byId.put(nation.id(), next);
        index(next);
        return next.version();
    }

    /**
     * 脱离调用方的一份拷贝。
     *
     * <p>不这么做就等于违反本类自己声明的"读写都返回副本"：调用方拿着同一个引用继续改，
     * 库里就跟着变，而"改了不 save 就不生效"这条约定（DEVELOPMENT.md §四）在 dev 下会被悄悄违反。
     */
    private Nation detached(Nation nation) {
        return Nation.fromSnapshot(nation.snapshot(), rules.rules());
    }

    /** 名字与联盟两张派生索引。两条写路径共用，避免其中一条记得、另一条忘了。 */
    private void index(Nation nation) {
        idByName.put(nation.name(), nation.id());
        // 先摘掉这个国家旧的联盟映射，再按当前成员重建：
        // 只增量添加的话，被开除的联盟会永远指向这个国家
        idByAlliance.values().removeIf(nation.id()::equals);
        for (String allianceId : nation.memberAllianceIds()) {
            idByAlliance.put(allianceId, nation.id());
        }
    }

    @Override
    public synchronized Optional<Nation> findById(String nationId) {
        Nation stored = nationId == null ? null : byId.get(nationId);
        return stored == null ? Optional.empty()
                : Optional.of(Nation.fromSnapshot(stored.snapshot(), rules.rules()));
    }

    @Override
    public synchronized Optional<Nation> findByName(String name) {
        String id = name == null ? null : idByName.get(name);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public synchronized Optional<Nation> findByAlliance(String allianceId) {
        String id = allianceId == null ? null : idByAlliance.get(allianceId);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public synchronized List<Nation> all() {
        List<Nation> out = new ArrayList<>();
        // 逐份重建：List.copyOf(values) 只是不复制列表本身，元素还是活对象，
        // 那等于"读返回副本"在这条方法上悄悄失效
        for (Nation stored : byId.values()) {
            out.add(Nation.fromSnapshot(stored.snapshot(), rules.rules()));
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void clear() {
        byId.clear();
        idByName.clear();
        idByAlliance.clear();
    }
}
