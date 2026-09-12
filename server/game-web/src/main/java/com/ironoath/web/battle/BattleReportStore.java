package com.ironoath.web.battle;

import java.util.List;
import java.util.Optional;

/**
 * 职责：战报存储端口。
 * 依赖：无。
 *
 * <p><b>为什么不像其它仓储那样把端口放在 game-core</b>：game-core 依赖不到 game-battle
 * （战斗内核是 core 的兄弟模块，不是它的下游），而战报的核心内容就是 {@code BattleResult}。
 * 硬要放到 core 就得把战果拆成一堆裸 long 再拼回来，那是为了迁就分层而把类型信息扔掉。
 * game-core 里没有任何逻辑需要读战报（复仇加成的判定在 B13 落地时会读，届时它也在 web 层），
 * 所以端口留在 game-web 是当前最诚实的位置。
 *
 * <p>实现与 {@code InMemoryWorldStore} 同一套约定：内存版供 dev/test，
 * MongoDB 版由 B16 补；两者语义必须一致，否则单测过了上线就炸。
 */
public interface BattleReportStore {

    /** 写入一份战报。同一 reportId 重复写入视为幂等（不覆盖）。 */
    void save(BattleReport report);

    Optional<BattleReport> findById(String reportId);

    /** 某玩家的全部战报，按生成时刻倒序（最新的在前）。 */
    List<BattleReport> reportsOf(String ownerId);

    /** 清理过期战报，返回清理条数。惰性调用，不跑定时器（B00 陷阱 2）。 */
    int purgeExpired(long nowMillis);

    /** 测试辅助：清空。 */
    void clear();
}
