package com.ironoath.web.store.mongo;

import com.ironoath.web.world.TargetOccupationStore;
import org.springframework.data.annotation.Id;

/** 单目标一文档；归属、操作凭据和版本在同一个原子写中提交，不另维护可分叉的归属索引。 */
public record TargetOccupationDocument(@Id String targetId, TargetOccupationStore.Snapshot state) {
    public static final String COLLECTION="world_target_occupation";
}
