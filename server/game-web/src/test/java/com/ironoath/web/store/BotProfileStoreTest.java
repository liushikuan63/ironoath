package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.bot.BotProfileStore;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRulesAssembler;
import com.ironoath.web.store.memory.InMemoryBotProfileStore;
import com.ironoath.web.store.mongo.MongoBotProfileStore;

class BotProfileStoreTest {
    static TestMongo db;
    static BotRulesAssembler rules;
    @BeforeAll static void open() {
        db=TestMongo.tryOpen();assertThat(db).as("真实Mongo必须可用").isNotNull();
        Path config=Path.of("../../contract/config");if(!config.toFile().isDirectory())config=Path.of("contract/config");
        rules=new BotRulesAssembler(ConfigRegistry.loadFromDirectory(config));
    }
    @AfterAll static void close() { if(db!=null)db.close(); }
    private List<BotProfileStore> stores() {
        var mongo=new MongoBotProfileStore(db.template());mongo.clear();
        return List.of(new InMemoryBotProfileStore(),mongo);
    }
    private BotProfile profile(String id,long aggression) {
        return new BotProfile(id,"bot_jielue",new BotProfile.AiProfile(aggression,4_000,2_000,6_000),
                new BotProfile.Persona(42,7,99,List.of(0,12,23),3,27,1_000),9_000);
    }
    @Test void completeFieldsAtomicReplaceDeleteAndClearHaveEquivalentSemantics() {
        for(var store:stores()) {
            var original=profile("owned-bot",8_000);var changed=profile("owned-bot",3_000);
            assertThat(store.put(original)).isNull();assertThat(store.find(original.botId())).contains(original);
            assertThat(store.put(changed)).isEqualTo(original);assertThat(store.all()).containsExactly(changed);
            assertThat(store.remove(null)).isFalse();assertThat(store.remove(original.botId())).isTrue();
            assertThat(store.find(original.botId())).isEmpty();assertThat(store.remove(original.botId())).isFalse();
            store.put(original);store.clear();assertThat(store.all()).isEmpty();
        }
    }
    @Test void independentReadersImmediatelySeeIdentityRegistrationAndRetirement() {
        for(var store:stores()) {
            BotProfileStore peer=store instanceof MongoBotProfileStore?new MongoBotProfileStore(db.template()):store;
            var a=new BotRegistry(rules,store);var b=new BotRegistry(rules,peer);
            a.register(profile("owned-bot",8_000));
            assertThat(b.isBot("owned-bot")).isTrue();assertThat(b.mayHoldOffice("owned-bot","NATION",false,true)).isFalse();
            assertThat(b.humansIn(List.of("owned-bot","owned-human"))).containsExactly("owned-human");
            a.unregister("owned-bot");assertThat(b.isBot("owned-bot")).isFalse();
        }
    }
    @Test void identityStoreFailureCannotDefaultToHumanOrSilentlyRegister() {
        BotProfileStore broken=org.mockito.Mockito.mock(BotProfileStore.class);
        org.mockito.Mockito.when(broken.find("owned-bot")).thenThrow(new IllegalStateException("temporary storage failure"));
        var registry=new BotRegistry(rules,broken);
        assertThatThrownBy(()->registry.mayHoldOffice("owned-bot","NATION",false,true)).isInstanceOf(IllegalStateException.class);
    }
    @Test void requestedIdentityProjectionPreservesScopeAndImmediatePeerChanges() {
        for(var store:stores()) {
            BotProfileStore peer=store instanceof MongoBotProfileStore?new MongoBotProfileStore(db.template()):store;
            store.put(profile("owned-a",8000));store.put(profile("outside",4000));
            assertThat(store.existingIds(null)).isEmpty();assertThat(store.existingIds(List.of())).isEmpty();
            var requested=java.util.Arrays.asList("owned-a",null,"owned-human","owned-a");
            assertThat(peer.existingIds(requested)).containsExactly("owned-a");
            store.remove("owned-a");assertThat(peer.existingIds(requested)).isEmpty();
            store.put(profile("owned-human",3000));
            assertThat(peer.existingIds(requested)).containsExactly("owned-human");
            assertThat(new BotRegistry(rules,peer).humansIn(requested)).containsExactly("owned-a","owned-a");
        }
    }
    @Test void identityProjectionFailureCannotTreatEveryoneAsHuman() {
        BotProfileStore broken=org.mockito.Mockito.mock(BotProfileStore.class);
        org.mockito.Mockito.when(broken.existingIds(org.mockito.ArgumentMatchers.anyCollection()))
                .thenThrow(new IllegalStateException("temporary identity projection failure"));
        assertThatThrownBy(()->new BotRegistry(rules,broken).humansIn(List.of("owned-bot","owned-human")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity projection");
    }
}
