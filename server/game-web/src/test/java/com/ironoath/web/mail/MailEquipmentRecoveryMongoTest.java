package com.ironoath.web.mail;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.mongodb.client.MongoClients;
/** Same actual persistence cuts on production ports, isolated database only. */
class MailEquipmentRecoveryMongoTest extends MailEquipmentRecoveryTest {
    private static final String DATABASE="ironoath_mail_equipment_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void mongo(DynamicPropertyRegistry properties){
        properties.add("ironoath.storage",()->"mongo");properties.add("spring.data.mongodb.uri",()->"mongodb://127.0.0.1:27017/"+DATABASE+"?serverSelectionTimeoutMS=3000");
    }
    @AfterAll static void dropOwned(){try(var client=MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000")){client.getDatabase(DATABASE).drop();}}

    @org.junit.jupiter.api.Test void historicalEquipmentCountsWithoutFrozenReceiptStillMigrate() {
        String uid=init.init(new com.ironoath.web.dto.generated.PlayerInitReq(UUID.randomUUID().toString(),
                "legacy-equip-"+UUID.randomUUID(),"旧装备档合成夹具",time.serverNow(),"")).playerId();
        inventories.insertIfAbsent(uid,com.ironoath.core.bag.Inventory.empty(100));
        var old=inventories.findByPlayerId(uid).orElseThrow();old.setCapacityMax(100);old.add(EQUIP,2,10);
        inventories.save(uid,old,inventories.versionOf(uid));
        var restored=inventories.findByPlayerId(uid).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(restored.equipSnapshot()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(restored.snapshot()).doesNotContainKey(EQUIP);
        org.assertj.core.api.Assertions.assertThat(restored.capacityUsed()).isEqualTo(2);
    }

    @org.junit.jupiter.api.Test void acceptedEquipmentClassificationStillMigratesLegacyCountsWhenConfigBecomesOrdinary() {
        String uid=init.init(new com.ironoath.web.dto.generated.PlayerInitReq(UUID.randomUUID().toString(),"frozen-equip-"+UUID.randomUUID(),"冻结装备合成夹具",time.serverNow(),"")).playerId();
        inventories.insertIfAbsent(uid,com.ironoath.core.bag.Inventory.empty(100));var old=inventories.findByPlayerId(uid).orElseThrow();old.setCapacityMax(100);old.add(EQUIP,2,10);inventories.save(uid,old,inventories.versionOf(uid));
        String mailId="frozen-equip-"+UUID.randomUUID();long now=time.serverNow();
        var source=new MailStore.MailRecord(mailId,uid,"SYSTEM","冻结装备","合成夹具",java.util.List.of(new MailStore.RewardLine("ITEM",EQUIP,1,"铁剑")),"fixture",now,now+86400000L,null,null);store.save(source);
        var plan=new MailResourceRecovery(store,configs,rewards).freeze(source,now);org.assertj.core.api.Assertions.assertThat(store.acceptClaim(uid,mailId,now,plan)).isTrue();
        var row=configs.get(com.ironoath.config.cfg.ItemCfg.class,EQUIP);
        org.mockito.Mockito.doReturn(new com.ironoath.config.cfg.ItemCfg(row.id(),row.name(),com.ironoath.config.cfg.ItemCfg.Type.MATERIAL,row.rarity(),row.effectKind(),row.effectValue(),row.effectTarget(),row.stackMax(),row.obtainFrom())).when(configs).get(com.ironoath.config.cfg.ItemCfg.class,EQUIP);
        new MailAppService(store,time,configs,rewards,idempotency,locks,names).list(uid);
        var after=inventories.findByPlayerId(uid).orElseThrow();org.assertj.core.api.Assertions.assertThat(after.equipSnapshot()).hasSize(3);org.assertj.core.api.Assertions.assertThat(after.snapshot()).doesNotContainKey(EQUIP);
    }
}
