package com.ironoath.web.world;

import com.ironoath.core.world.WorldGenerator;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 战略目标归属端口。只保存变化；缺档是中立，不为旧世界回填13份默认档。 */
public interface TargetOccupationStore {
    /** null 引用是旧归属档的未知资料；EMPTY 是已知撤防，不得回填 NPC。 */
    record Garrison(Kind kind, String marchId) {
        public enum Kind { EMPTY, STATIONED }
        public Garrison {
            if(kind==null || (kind==Kind.EMPTY && marchId!=null)
                    || (kind==Kind.STATIONED && (marchId==null || marchId.isBlank()))) {
                throw new IllegalArgumentException("战略驻军引用无效，空驻军与缺档不可混同");
            }
        }
        public static Garrison empty() { return new Garrison(Kind.EMPTY,null); }
        public static Garrison stationed(String marchId) { return new Garrison(Kind.STATIONED,marchId); }
    }

    /** A nation's fortress-output ownership interval, frozen in the same CAS document as the current owner. */
    record OwnershipPeriod(String nationId,long fromAt,long untilAt,String operationId) {
        public OwnershipPeriod {
            if(nationId==null || nationId.isBlank() || fromAt<0 || untilAt<fromAt
                    || operationId==null || operationId.isBlank())
                throw new IllegalArgumentException("战略目标历史归属时段无效");
        }
    }

    /** 坐标/类型锚定防止改表静默搬走已占目标；国家名在国家域目前不可改名，随占领冻结。 */
    record Snapshot(String targetId, WorldGenerator.EntityType kind, int x, int y,
                    String nationId, String nationName, long capturedAt, String operationId, long version,
                    Garrison garrison,List<OwnershipPeriod> ownershipHistory) {
        /** 旧元数据档保持未知驻军；缺字段不代表空兵，更不代表恢复配置守军。 */
        public Snapshot(String targetId, WorldGenerator.EntityType kind, int x, int y,
                        String nationId, String nationName, long capturedAt, String operationId, long version) {
            this(targetId,kind,x,y,nationId,nationName,capturedAt,operationId,version,null,List.of());
        }
        public Snapshot(String targetId, WorldGenerator.EntityType kind, int x, int y,
                        String nationId, String nationName, long capturedAt, String operationId, long version,
                        Garrison garrison) {
            this(targetId,kind,x,y,nationId,nationName,capturedAt,operationId,version,garrison,List.of());
        }
        public Snapshot {
            if (targetId==null || targetId.isBlank() || kind==null || !kind.isStrategic()
                    || x<0 || y<0 || nationId==null || nationId.isBlank()
                    || nationName==null || nationName.isBlank() || capturedAt<0
                    || operationId==null || operationId.isBlank() || version<1) {
                throw new IllegalArgumentException("战略目标归属快照无效");
            }
            ownershipHistory=ownershipHistory==null?List.of():List.copyOf(ownershipHistory);
            long previousUntil=0;
            for(OwnershipPeriod period:ownershipHistory) {
                if(period.untilAt()>capturedAt || period.fromAt()<previousUntil)
                    throw new IllegalArgumentException("战略目标历史归属时段重叠或晚于当前归属");
                previousUntil=period.untilAt();
            }
        }

        /** Ownership immediately before/after a fortress capture boundary. */
        public String nationAt(long at,boolean beforeBoundary) {
            if(at<0)throw new IllegalArgumentException("战略归属查询时刻不得为负");
            for(OwnershipPeriod period:ownershipHistory) {
                boolean inside=beforeBoundary?period.fromAt()<at && at<=period.untilAt()
                        :period.fromAt()<=at && at<period.untilAt();
                if(inside)return period.nationId();
            }
            boolean current=beforeBoundary?capturedAt<at:capturedAt<=at;
            return current?nationId:null;
        }

        /** Copy prior epochs and close this owner's epoch at a later capture event. */
        public List<OwnershipPeriod> ownershipHistoryClosedAt(long transitionAt) {
            if(transitionAt<capturedAt)throw new IllegalArgumentException("目标归属变更早于原占领时刻");
            if(transitionAt==capturedAt)return ownershipHistory;
            var next=new java.util.ArrayList<>(ownershipHistory);
            next.add(new OwnershipPeriod(nationId,capturedAt,transitionAt,operationId));
            return List.copyOf(next);
        }

        public List<Long> ownershipChangeTimes(long afterExclusive,long throughInclusive) {
            if(afterExclusive<0 || throughInclusive<afterExclusive)
                throw new IllegalArgumentException("战略归属变化查询区间无效");
            var times=new java.util.TreeSet<Long>();
            for(OwnershipPeriod period:ownershipHistory) {
                addInRange(times,period.fromAt(),afterExclusive,throughInclusive);
                addInRange(times,period.untilAt(),afterExclusive,throughInclusive);
            }
            addInRange(times,capturedAt,afterExclusive,throughInclusive);
            return List.copyOf(times);
        }

        private static void addInRange(java.util.Set<Long> times,long value,long afterExclusive,long throughInclusive) {
            if(value>afterExclusive && value<=throughInclusive)times.add(value);
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
