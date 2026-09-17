package com.ironoath.web.store.mongo;

import com.ironoath.config.ConfigException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

/**
 * 职责：背包的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link InventoryDocument}。
 *
 * <p>四条契约（读返回副本、保存推进版本、过期版本被拒且不留半个写入、并发插入只有一个赢家）
 * 由 {@code InventoryStoreContractTest}（内存）与 {@code MongoInventoryStoreContractTest}（真实 Mongo）
 * 跑同一份断言保证，不再靠 {@code DEVELOPMENT.md} §四 那句"两套实现语义等价"的承诺。
 *
 * <p>写入用「按 version 条件的 update」而不是整档 replace，理由与 {@link MongoCityStore} 同一条：
 * {@code versionOf} 这种只读一个版本的调用不该付整档传输的代价。
 */
public final class MongoInventoryStore implements InventoryRepository {

    private final MongoTemplate mongo;
    private final ConfigRegistry configs;

    public MongoInventoryStore(MongoTemplate mongo, ConfigRegistry configs) {
        this.mongo = mongo;
        this.configs = configs;
    }

    @Override
    public Optional<Inventory> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        InventoryDocument doc = mongo.findById(playerId, InventoryDocument.class,
                InventoryDocument.COLLECTION);
        return Optional.ofNullable(doc).map(this::toDomain);
    }

    @Override
    public boolean insertIfAbsent(String playerId, Inventory inventory) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (inventory == null) {
            throw new IllegalArgumentException("背包不得为 null");
        }
        try {
            mongo.insert(new InventoryDocument(playerId, 0L, inventory.capacityMax(),
                    inventory.snapshot(), inventory.equipSnapshot(), inventory.nextEquipUid()),
                    InventoryDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public long save(String playerId, Inventory inventory, long expectedVersion) {
        if (inventory == null) {
            throw new IllegalArgumentException("背包不得为 null");
        }
        Query query = Query.query(Criteria.where("_id").is(playerId).and("version").is(expectedVersion));
        Update update = new Update()
                .set("counts", inventory.snapshot())
                // 实例账本必须同一条更新里写：漏了它，"装备去哪了"就成了只有重启才能复现的问题
                .set("equips", inventory.equipSnapshot())
                .set("nextEquipUid", inventory.nextEquipUid())
                .set("capacityMax", inventory.capacityMax())
                .inc("version", 1L);
        UpdateResult result = mongo.updateFirst(query, update, InventoryDocument.class,
                InventoryDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            boolean exists = mongo.exists(Query.query(Criteria.where("_id").is(playerId)),
                    InventoryDocument.class, InventoryDocument.COLLECTION);
            if (!exists) {
                throw new IllegalStateException("背包不存在，无法更新：playerId=" + playerId);
            }
            throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                    + "，提交版本=" + expectedVersion + "。请重读背包后重试。");
        }
        return expectedVersion + 1L;
    }

    @Override
    public long versionOf(String playerId) {
        InventoryDocument doc = mongo.findById(playerId, InventoryDocument.class,
                InventoryDocument.COLLECTION);
        if (doc == null) {
            throw new IllegalStateException("背包不存在：playerId=" + playerId);
        }
        return doc.version();
    }

    /**
     * 每次调用都新建一份，所以读出去的不是库里的活对象。
     *
     * <p><b>老档的装备在这里迁移</b>：实例化之前 {@code counts} 里就躺着 {@code eq_xxx: 2}，
     * 而那两个字段的读法在 §五⑤ 之后互相矛盾（数量说不清"哪一件强化到几"）。
     * 判据由 {@code item} 表给（哪些 id 是 EQUIP），而"读成没有装备"是明令禁止的结果 ——
     * 迁移失败的那一条会留在 counts 里当普通道具，看得见但穿不了，
     * 而不是从玩家的背包里消失。
     */
    private Inventory toDomain(InventoryDocument doc) {
        Inventory inventory = Inventory.empty(doc.capacityMax());
        inventory.restore(doc.counts(), doc.equips(), doc.capacityMax(),
                doc.nextEquipUid() <= 0 ? 1 : doc.nextEquipUid(),
                // 老文档没有这两个字段：null 视作"一件也没有"、0 视作"序号未初始化"
                itemId -> {
                    try {
                        return configs.get(ItemCfg.class, itemId).type() == ItemCfg.Type.EQUIP;
                    } catch (ConfigException e) {
                        // item 表里查不到的 id 不该顺手铸成实例（铸出来也是一件不存在的装备），
                        // 留在 counts 里让背包列表把它显出来，比静默变成一件怪装备好查
                        return false;
                    }
                });
        return inventory;
    }
}
