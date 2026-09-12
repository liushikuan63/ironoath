package com.ironoath.web.store.mongo;

import com.ironoath.core.march.March;
import org.springframework.data.annotation.Id;

/**
 * 职责：行军的 MongoDB 文档模型。
 * 依赖：{@link March.Snapshot}。
 *
 * <p>业务字段整体存在 {@code state} 里（就是领域快照），文档级只额外挂三个**查询用的键**：
 * {@code playerId}、{@code fromChunkKey}、{@code toChunkKey}。这三个是纯索引列，
 * 值永远从 {@code state} 派生，写入时一起算，不参与任何判定 —— 判定一律读 {@code state}。
 *
 * <p>为什么不在文档里平铺 20 个业务字段：那等于把 {@link March.Snapshot} 的字段表再抄一遍，
 * 而这条存档最容易漏的字段（{@code rallyId}）恰恰有过"漏了整个系统一声不响"的历史。
 * 嵌套一层就让"完整"只有一份定义。
 *
 * @param id          行军 id（主键）
 * @param version     乐观锁版本号
 * @param playerId    归属玩家，供「我的行军列表」与并发数校验查
 * @param fromChunkKey 起点所在 chunk 的键（viewport 增量下发要用）
 * @param toChunkKey   终点所在 chunk 的键
 * @param state        完整业务状态
 */
public record MarchDocument(
        @Id String id,
        long version,
        String playerId,
        String fromChunkKey,
        String toChunkKey,
        March.Snapshot state) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "march";

    /** 由领域对象造文档；chunk 键用与 {@code InMemoryMarchStore} 同一个 {@code chunkSize} 算。 */
    static MarchDocument fromDomain(March march, long version, int chunkSize) {
        March.Snapshot state = march.snapshot();
        return new MarchDocument(state.id(), version, state.playerId(),
                state.from().chunkKey(chunkSize), state.to().chunkKey(chunkSize), state);
    }

    March toDomain() {
        return March.fromSnapshot(state);
    }
}
