package com.ironoath.web.store.mongo;

import com.ironoath.core.bag.Inventory;
import org.springframework.data.annotation.Id;

import java.util.Map;

/**
 * 职责：背包的 MongoDB 文档模型。
 * 依赖：无（纯数据 + Spring Data 注解）。
 *
 * <p>字段直接取 {@code Inventory.snapshot()} 的产物（道具 id → 数量）加容量：
 * 那对 snapshot/restore 本来就在领域里，内存版深拷贝也走它，所以这里不再另立一份"什么算完整背包"
 * （理由与 {@link CityDocument} 同源，也同样是 #34 契约要避免的两份定义）。
 *
 * <p><b>字段就是 {@code Inventory.snapshot()} 与 {@code equipSnapshot()} 的产物</b>，
 * 与 {@link CityDocument}、{@link HeroDocument} 同一做法：不在文档层另立一份"什么算完整背包"。
 * 少抄一个字段的表现不是报错，而是玩家的东西在重启之后不见 —— 那份定义只能有一个家。
 *
 * @param playerId     主键
 * @param version      乐观锁版本号
 * @param capacityMax  容量上限（会随扩容增长，属于存档状态，不能从配置反推）
 * @param counts       道具 id → 数量；空 map 表示空背包（不省略字段，省了就要在读取处判缺字段）。
 *                     <b>装备不在这里</b>（B20 §五⑤），老档里那些 {@code eq_xxx: 2} 由读取侧迁移成实例
 * @param equips       装备实例账本（uid → 哪一行、强化到几、穿着没有）
 * @param nextEquipUid 铸造序号的下一个值。落档而不是每次现算：随机号会让同一份档在两处
 *                     （内存对象与 Mongo 副本）铸出两套 uid，而武将槽位里存的正是 uid
 */
public record InventoryDocument(
        @Id String playerId,
        long version,
        int capacityMax,
        Map<String, Long> counts,
        java.util.List<Inventory.EquipInstance> equips,
        int nextEquipUid) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "inventory";
}
