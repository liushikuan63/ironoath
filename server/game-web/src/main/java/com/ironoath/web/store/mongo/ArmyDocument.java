package com.ironoath.web.store.mongo;

import com.ironoath.core.army.ArmyState;
import org.springframework.data.annotation.Id;

import java.util.List;
import java.util.Map;

/**
 * 职责：军队存档的 MongoDB 文档模型。
 * 依赖：{@link ArmyState.TrainingTask}。
 *
 * <p>字段就是 {@link ArmyState.Snapshot} 的展开（理由同 {@link CityDocument}：这份存档只有
 * 一个访问键与一种访问方式，抄一套镜像 {@code *Doc} 换不来查询能力，只会多一个
 * "少抄一个字段就静默丢档"的地方）。
 *
 * @param playerId            主键
 * @param version             乐观锁版本号（多实例下唯一能拦住并发覆盖的东西）
 * @param troops              各兵种兵力（unitId → 数量，含阶级后缀）
 * @param queue               训练队列（每条自带 unitId，所以用列不用映射）
 * @param wounded             伤兵
 * @param treatFinishAt       治疗完成时刻；null 表示没在治疗
 * @param treatTotalSeconds   治疗剩余总时长（加速会改它）
 * @param treatOriginalSeconds 治疗原始总时长（加速不改它，帮助百分比以它为基数）
 * @param treatCost           本次治疗资源明细，取消治疗时按它加回剩余比例
 * @param extraSlots          已购买的额外治疗位
 */
public record ArmyDocument(
        @Id String playerId,
        long version,
        Map<String, Long> troops,
        List<ArmyState.TrainingTask> queue,
        Map<String, Long> wounded,
        Long treatFinishAt,
        long treatTotalSeconds,
        long treatOriginalSeconds,
        Map<String, Long> treatCost,
        int extraSlots,
        /**
         * 自动续训策略（B25 裁决③(a)）。**可空**：这一列是后加的，老文档没有它 ——
         * 领域侧的 {@code Snapshot} 会把 null 当成"关"，所以老号读档后不会突然开始花钱训兵。
         */
        com.ironoath.core.army.AutoTrainPolicy autoTrain,
        List<String> rallyRefunds) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "army";

    static ArmyDocument fromDomain(String playerId, long version, ArmyState army) {
        ArmyState.Snapshot s = army.snapshot();
        return new ArmyDocument(playerId, version, s.troops(), s.queue(), s.wounded(),
                s.treatFinishAt(), s.treatTotalSeconds(), s.treatOriginalSeconds(),
                s.treatCost(), s.extraSlots(), s.autoTrain(), s.rallyRefunds());
    }

    /** 每次调用都新建一份，所以读出去的不是库里的活对象。 */
    ArmyState toDomain() {
        ArmyState army = ArmyState.fromSnapshot(new ArmyState.Snapshot(troops, queue, wounded, treatFinishAt,
                treatTotalSeconds, treatOriginalSeconds, treatCost, extraSlots, autoTrain, rallyRefunds));
        army.bindRepositoryVersion(version);
        return army;
    }
}
