package com.ironoath.web.store.mongo;

import com.ironoath.web.world.NeutralTargetGarrisonStore;
import org.springframework.data.annotation.Id;

/** 独立集合，保留中立NPC损耗与退出状态，不修改旧玩家档或归属集合。 */
public record NeutralTargetGarrisonDocument(@Id String targetId,NeutralTargetGarrisonStore.Snapshot state) {
    public static final String COLLECTION="world_target_neutral_garrison";
}
