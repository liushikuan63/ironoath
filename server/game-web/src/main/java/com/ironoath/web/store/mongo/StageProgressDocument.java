package com.ironoath.web.store.mongo;

import com.ironoath.core.stage.StageProgress;
import org.springframework.data.annotation.Id;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：章节副本进度的 MongoDB 文档模型。
 * 依赖：{@link StageProgress}（业务状态整体就是它的 {@code all()} + 版本）。
 *
 * <p>文档体只有三列，且刻意不再拆出"每关一行"：关卡进度是一个玩家的整体（星级只升不降、
 * 首通时刻只写一次），拆开后一次挑战要写多行、还要额外解决"这一章三星了没有"的聚合，
 * 而那些判定本来就在 {@link StageProgress} 里。 playerId 就是 {@code _id}，
 * 所以读写都走主键，不需要额外索引。
 *
 * <p>键集合是动态的（stageId → 成绩），用 Map 落子文档；stageId 的格式是
 * {@code stage_章_关}，不含点号，所以不会撞上 MongoDB 对字段名的限制。
 */
public record StageProgressDocument(
        @Id String playerId,
        long version,
        Map<String, StageProgress.Record> stages) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "stage_progress";

    static StageProgressDocument fromDomain(String playerId, StageProgress progress) {
        return new StageProgressDocument(playerId, progress.version(),
                new LinkedHashMap<>(progress.all()));
    }

    StageProgress toDomain() {
        StageProgress progress = new StageProgress();
        progress.restore(stages, version);
        return progress;
    }
}
