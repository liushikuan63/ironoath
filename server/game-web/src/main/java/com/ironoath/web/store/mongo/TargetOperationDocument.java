package com.ironoath.web.store.mongo;

import com.ironoath.web.world.TargetOperationStore;
import org.springframework.data.annotation.Id;

public record TargetOperationDocument(@Id String targetId,TargetOperationStore.Snapshot state) {
    public static final String COLLECTION="world_target_operation";
}
