package com.ironoath.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.config.model.GlobalCfg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 均为本测试私有注册表；配置文件只读，整表热更写入JUnit拥有的临时目录。 */
class ConfigRegistrySnapshotTest {
    private static final String UNIT="unit_infantry_t1",PARAM="TRAIN_BATCH_MAX";
    private Path configDir() {
        for(Path p=Path.of("").toAbsolutePath();p!=null;p=p.getParent())
            if(Files.isDirectory(p.resolve("contract/config")))return p.resolve("contract/config");
        throw new IllegalStateException("fixture config directory missing");
    }
    private ConfigRegistry parent(){return ConfigRegistry.loadFromDirectory(configDir());}
    private String changed(String table,String id,String field,long value) throws Exception {
        ObjectNode root=(ObjectNode)ConfigRegistry.parse(Files.readString(configDir().resolve(table+".json")));
        for(JsonNode row:root.get("rows"))if(id.equals(row.get("id").asText()))((ObjectNode)row).put(field,value);
        root.put("version",root.get("version").asInt()+1);return root.toString();
    }
    private Path directory(Path base,String name,long attack,long param) throws Exception {
        Path target=Files.createDirectories(base.resolve(name));
        try(var paths=Files.list(configDir())) {
            for(Path file:paths.filter(p->p.getFileName().toString().endsWith(".json")).toList())
                Files.copy(file,target.resolve(file.getFileName()));
        }
        Files.writeString(target.resolve("unit.json"),changed("unit",UNIT,"attack",attack));
        Files.writeString(target.resolve("global.json"),changed("global",PARAM,"value",param));
        return target;
    }

    @Test void capturedViewHasIndependentLazyTypedCacheAndGlobalsAfterParentReload() throws Exception {
        var live=parent();long attack=live.get(UnitCfg.class,UNIT).attack(),param=live.longParam(PARAM);
        var frozen=live.readOnlySnapshot();String fingerprint=frozen.fingerprint();
        live.reload("unit",UnitCfg.class,changed("unit",UNIT,"attack",attack+7));
        live.reload("global",GlobalCfg.class,changed("global",PARAM,"value",param+9));
        assertThat(live.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack+7);assertThat(live.longParam(PARAM)).isEqualTo(param+9);
        assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);assertThat(frozen.longParam(PARAM)).isEqualTo(param);
        assertThat(frozen.fingerprint()).isEqualTo(fingerprint);
        frozen.invalidate(UnitCfg.class);
        assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
        assertThat(live.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack+7);
    }

    @Test void laterParentRawNodeMutationCannotChangeACapturedView() {
        var live=parent();long attack=live.rawTable("unit").row(UNIT).get("attack").asLong();
        var frozen=live.readOnlySnapshot();
        ((ObjectNode)live.rawTable("unit").row(UNIT)).put("attack",attack+99);
        live.invalidate(UnitCfg.class);
        assertThat(live.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack+99);
        assertThat(frozen.rawTable("unit").row(UNIT).get("attack").asLong()).isEqualTo(attack);
        assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
    }

    @Test void snapshotRawDiagnosticsAreDefensiveCopiesIncludingTheirIndex() {
        var live=parent();var frozen=live.readOnlySnapshot();
        long attack=live.get(UnitCfg.class,UNIT).attack();var diagnostic=frozen.rawTable("unit");
        JsonNode fromRows=diagnostic.rows().stream().filter(row->UNIT.equals(row.get("id").asText())).findFirst().orElseThrow();
        assertThat(diagnostic.row(UNIT)).isSameAs(fromRows);
        ((ObjectNode)diagnostic.rowsById().get(UNIT)).put("attack",attack+77);
        assertThat(frozen.rawTable("unit").row(UNIT).get("attack").asLong()).isEqualTo(attack);
        assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
        assertThat(live.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
        assertThat(frozen.tableNames()).containsExactlyInAnyOrderElementsOf(live.tableNames());
    }

    @Test void nestedRawArraysAreDetachedFromParentAndReturnedDiagnostics() {
        var live=parent();String table="bot_name",id="bot_name_surname",field="namePool";
        JsonNode expected=live.rawTable(table).row(id).get(field).deepCopy();assertThat(expected.size()).isGreaterThan(0);
        var frozen=live.readOnlySnapshot();
        ((com.fasterxml.jackson.databind.node.ArrayNode)live.rawTable(table).row(id).get(field)).removeAll();
        assertThat(frozen.rawTable(table).row(id).get(field)).isEqualTo(expected);
        ((com.fasterxml.jackson.databind.node.ArrayNode)frozen.rawTable(table).row(id).get(field)).removeAll();
        assertThat(frozen.rawTable(table).row(id).get(field)).isEqualTo(expected);
    }

    @Test void snapshotRejectsReloadBeforeParsingOrFilesystemIOButParentStaysWritable() throws Exception {
        var live=parent();var frozen=live.readOnlySnapshot();long attack=live.get(UnitCfg.class,UNIT).attack();
        assertThatThrownBy(()->frozen.reload("unit",UnitCfg.class,"not valid JSON")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->frozen.reloadAllFromDirectory(Path.of("nonexistent-owned-snapshot-fixture"))).isInstanceOf(IllegalStateException.class);
        live.reload("unit",UnitCfg.class,changed("unit",UNIT,"attack",attack+1));
        assertThat(live.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack+1);
        assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
        var again=frozen.readOnlySnapshot();
        assertThatThrownBy(()->again.reload("unit",UnitCfg.class,"invalid")).isInstanceOf(IllegalStateException.class);
        assertThat(again.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
    }

    @Test void fullReloadCannotMixSnapshotUnitsAndGlobals(@TempDir Path temp) throws Exception {
        var live=parent();long attack=live.get(UnitCfg.class,UNIT).attack(),param=live.longParam(PARAM);
        var frozen=live.readOnlySnapshot();Path dir=directory(temp,"complete",attack+3,param+5);
        live.reloadAllFromDirectory(dir);
        assertThat(live.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack+3);assertThat(live.longParam(PARAM)).isEqualTo(param+5);
        assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);assertThat(frozen.longParam(PARAM)).isEqualTo(param);
        var later=live.readOnlySnapshot();assertThat(later.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack+3);
        assertThat(later.longParam(PARAM)).isEqualTo(param+5);
        assertThat(ConfigRegistry.loadFromDirectory(configDir()).get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
    }

    @Test void snapshotDoesNotWaitForTheParentRegistryReloadMonitor() throws Exception {
        var live=parent();long attack=live.get(UnitCfg.class,UNIT).attack();String next=changed("unit",UNIT,"attack",attack+1);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        live.addReloadGuard("unit",candidate->{
            entered.countDown();try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("owned guard timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        });
        try {
            var writer=pool.submit(()->live.reload("unit",UnitCfg.class,next));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var reader=pool.submit(live::readOnlySnapshot);
            var frozen=reader.get(2,TimeUnit.SECONDS);
            assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
            release.countDown();writer.get(5,TimeUnit.SECONDS);
            assertThat(live.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack+1);
            assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
        } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void failedParentGuardKeepsTheParentAndPreviouslyCapturedViewUnchanged() throws Exception {
        var live=parent();long attack=live.get(UnitCfg.class,UNIT).attack();var frozen=live.readOnlySnapshot();
        var failure=new IllegalStateException("owned rejected candidate");live.addReloadGuard("unit",candidate->{throw failure;});
        String next=changed("unit",UNIT,"attack",attack+2);
        assertThatThrownBy(()->live.reload("unit",UnitCfg.class,next)).isSameAs(failure);
        assertThat(live.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
        assertThat(frozen.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
        assertThat(live.readOnlySnapshot().get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);
    }

    @Test void concurrentCapturesObserveWholePublishedRawMaps(@TempDir Path temp) throws Exception {
        var live=parent();long attack=live.get(UnitCfg.class,UNIT).attack(),param=live.longParam(PARAM);
        Path first=directory(temp,"first",attack+1,param+1),second=directory(temp,"second",attack+2,param+2);
        var start=new CountDownLatch(1);var oldObserved=new CountDownLatch(1);
        var firstPublished=new CountDownLatch(1);var newObserved=new CountDownLatch(1);
        var observed=ConcurrentHashMap.<Long>newKeySet();var pool=Executors.newFixedThreadPool(2);
        try {
            var writer=pool.submit(()->{
                try {
                    start.await();if(!oldObserved.await(5,TimeUnit.SECONDS))throw new IllegalStateException("old view not observed");
                    live.reloadAllFromDirectory(first);firstPublished.countDown();
                    if(!newObserved.await(5,TimeUnit.SECONDS))throw new IllegalStateException("new view not observed");
                    for(int i=0;i<12;i++)live.reloadAllFromDirectory(i%2==0?second:first);
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            });
            var reader=pool.submit(()->{
                try {
                    start.await();var old=live.readOnlySnapshot();
                    assertThat(old.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack);assertThat(old.longParam(PARAM)).isEqualTo(param);
                    observed.add(0L);oldObserved.countDown();
                    if(!firstPublished.await(5,TimeUnit.SECONDS))throw new IllegalStateException("first new map not published");
                    var next=live.readOnlySnapshot();
                    assertThat(next.get(UnitCfg.class,UNIT).attack()).isEqualTo(attack+1);assertThat(next.longParam(PARAM)).isEqualTo(param+1);
                    observed.add(1L);newObserved.countDown();
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                for(int i=0;i<30;i++) {
                    var frozen=live.readOnlySnapshot();long unitDelta=frozen.get(UnitCfg.class,UNIT).attack()-attack;
                    assertThat(unitDelta).isBetween(0L,2L);assertThat(frozen.longParam(PARAM)-param).isEqualTo(unitDelta);
                    observed.add(unitDelta);
                }
            });
            start.countDown();writer.get(15,TimeUnit.SECONDS);reader.get(15,TimeUnit.SECONDS);
            assertThat(observed).contains(0L,1L);
        } finally {start.countDown();oldObserved.countDown();firstPublished.countDown();newObserved.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
}
