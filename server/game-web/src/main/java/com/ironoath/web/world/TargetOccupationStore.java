package com.ironoath.web.world;

import com.ironoath.core.world.WorldGenerator;
import java.util.Collection;
import java.util.Map;

/** 战略目标归属端口。只保存变化；缺档是中立，不为旧世界回填13份默认档。 */
public interface TargetOccupationStore {
    /** 坐标/类型锚定防止改表静默搬走已占目标；国家名在国家域目前不可改名，随占领冻结。 */
    record Snapshot(String targetId, WorldGenerator.EntityType kind, int x, int y,
                    String nationId, String nationName, long capturedAt, String operationId, long version) {
        public Snapshot {
            if (targetId==null || targetId.isBlank() || kind==null || !kind.isStrategic()
                    || x<0 || y<0 || nationId==null || nationId.isBlank()
                    || nationName==null || nationName.isBlank() || capturedAt<0
                    || operationId==null || operationId.isBlank() || version<1) {
                throw new IllegalArgumentException("战略目标归属快照无效");
            }
        }
    }

    Map<String, Snapshot> findByTargetIds(Collection<String> targetIds);
    /** 启动/配置切换时有界检查孤立档，不能把删除配置后的旧归属当作不存在。 */
    Map<String, Snapshot> findAll(int limit);

    /**
     * 版本0仅建档，后续严格CAS；完全相同快照重放成功，旧战斗不能覆盖新版本。
     * 未知写入结果只在独立读回完全一致时确认；不能确认的异常必须向上传递。
     * 这不是战斗、积分或玩家兵力的跨文档事务，生产战斗写口尚须接可恢复结算链。
     * expectedVersion须随战斗计划冻结，冲突后不得盲取新版本重试；本档只保留最近操作凭据。
     */
    boolean compareAndSet(Snapshot next, long expectedVersion);

    static void validateWrite(Snapshot next, long expectedVersion) {
        if (next==null || expectedVersion<0 || next.version()!=Math.addExact(expectedVersion,1L)) {
            throw new IllegalArgumentException("归属版本必须等于期望版本加一");
        }
    }
}
