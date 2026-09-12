package com.ironoath.web.store.mongo;

import com.ironoath.core.nation.Nation;
import org.springframework.data.annotation.Id;

import java.util.List;

/**
 * 职责：国家的 MongoDB 文档模型。
 * 依赖：{@link Nation.Snapshot}（业务状态整体就是它）。
 *
 * <p>文档级三列都是<b>纯索引列</b>（值永远由快照派生，不参与任何判定）：
 * {@code name} 供"这个国名有没有被占"查询，{@code memberAllianceIds} 供"某个联盟属于哪国"查询。
 * 与 {@link MarchDocument} 同一套取舍：把可派生列提到文档级只是为了走索引，
 * 判定时读的还是 {@code state} 里那一份。
 */
public record NationDocument(
        @Id String nationId,
        String name,
        List<String> memberAllianceIds,
        Nation.Snapshot state) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "nation";

    static NationDocument fromDomain(Nation nation) {
        Nation.Snapshot state = nation.snapshot();
        return new NationDocument(state.id(), state.name(),
                List.copyOf(nation.memberAllianceIds()), state);
    }
}
