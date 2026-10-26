package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.TechCfg;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.BuildingStatus;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.formula.Formula;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.TechResearchReq;
import com.ironoath.web.dto.generated.TechView;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.tech.TechAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** B20 row-base consumption through the real service; private config/context never reloads another suite's registry. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TechTimeBaseTest.PrivateConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TechTimeBaseTest {
    private static final String ROW = "tech_agri_wood";
    private static final String OTHER = "tech_agri_stone";
    private static final long NOW = 1_700_000_000_000L;
    @Autowired Fixture fixture;
    @Autowired ConfigRegistry configs;
    @Autowired Formula formula;
    @Autowired TechAppService service;
    @Autowired PlayerInitService init;
    @Autowired CityAppService cityService;
    @Autowired PlayerRepository players;
    @Autowired CityRepository cities;

    @TestConfiguration(proxyBeanMethods = false)
    static class PrivateConfig {
        @Bean Fixture techTimeFixture() throws IOException { return new Fixture(); }
        @Bean @Primary ConfigRegistry privateTechConfigs(Fixture f) { return f.registry; }
        @Bean @Primary TimeService privateTechTime(Fixture f) { return new TimeService(f.now::get); }
    }

    static final class Fixture {
        final AtomicLong now = new AtomicLong(NOW);
        final ObjectMapper mapper = new ObjectMapper();
        final ObjectNode original;
        final ConfigRegistry registry;
        Fixture() throws IOException {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.isDirectory(root.resolve("contract/config"))) root = root.getParent();
            if (root == null) throw new IOException("No project contract/config ancestor");
            Map<String, String> tables = new LinkedHashMap<>();
            try (var paths = Files.list(root.resolve("contract/config"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    tables.put(path.getFileName().toString().replace(".json", ""), Files.readString(path));
                }
            }
            original = (ObjectNode) mapper.readTree(tables.get("tech"));
            registry = ConfigRegistry.loadFromJson(tables);
        }
        ObjectNode table() {
            ObjectNode table = original.deepCopy();
            ((ObjectNode) table.get("fieldTypes")).put("timeBaseSec", "?LONG_NONNEG");
            table.withArray("rows").forEach(row -> ((ObjectNode) row).put("timeBaseSec", 0));
            return table;
        }
        ObjectNode row(ObjectNode table, String id) {
            for (var row : table.withArray("rows")) if (id.equals(row.path("id").asText())) return (ObjectNode) row;
            throw new AssertionError("Missing fixture row " + id);
        }
        void load(ObjectNode table) { registry.reload("tech", TechCfg.class, table.toString()); }
        void base(long base) {
            ObjectNode t = table(); row(t, ROW).put("timeBaseSec", base); load(t);
        }
    }

    @BeforeEach void reset() {
        fixture.now.set(NOW);
        fixture.load(fixture.table());
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
    }

    private String player(int level) {
        String id = init.init(new PlayerInitReq("req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "科技夹具", NOW, "")).playerId();
        cityService.list(id);
        CityState city = cities.findByPlayerId(id).orElseThrow();
        long version = cities.versionOf(id);
        BuildingInstance academy = city.findByConfigId("academy");
        if (academy == null) city.restoreBuilding(new BuildingInstance("b-private-academy", "academy", 30, 2, 2));
        else academy.restore(30, academy.gridX(), academy.gridY(), BuildingStatus.IDLE,
                null, 0L, 0L, 0L, academy.helpCount(), academy.lastMovedAt(), academy.lastFinishedAt());
        cities.save(id, city, version);
        PlayerSave p = players.findByPlayerId(id).orElseThrow();
        Map<String, Integer> levels = new LinkedHashMap<>();
        if (level > 0) for (TechCfg cfg : configs.all(TechCfg.class)) levels.put(cfg.id(), Math.min(level, (int) cfg.maxLevel()));
        p.setTech(new PlayerTech(levels, null, null, 0, 0));
        for (String r : new String[]{"WOOD", "STONE", "IRON", "GRAIN"}) {
            PlayerResourceState s = p.resource(r);
            p.putResource(r, new PlayerResourceState(2_000L, 2_000L,
                    Math.min(s.protectedAmount(), 2_000L), s.perHour(), NOW));
        }
        players.save(p);
        return id;
    }
    private TechView view(String player, String tech) {
        return service.list(player).techs().stream().filter(t -> t.techId().equals(tech)).findFirst().orElseThrow();
    }

    @Test void productionContractUsesNullableGeneratedLongAndAllZeroDefaults() {
        var component = java.util.Arrays.stream(TechCfg.class.getRecordComponents())
                .filter(c -> c.getName().equals("timeBaseSec")).findFirst().orElseThrow();
        assertThat(component.getType()).isEqualTo(Long.class);
        assertThat(fixture.original.path("version").asInt()).isEqualTo(2);
        assertThat(fixture.original.path("fieldTypes").path("timeBaseSec").asText()).isEqualTo("?LONG_NONNEG");
        assertThat(fixture.original.withArray("rows")).hasSize(11);
        fixture.original.withArray("rows").forEach(row ->
                assertThat(row.path("timeBaseSec").isIntegralNumber() && row.path("timeBaseSec").longValue() == 0)
                        .as("production %s keeps zero fallback", row.path("id").asText()).isTrue());
    }

    @Test void rowBaseDrivesPreviewResearchAndStoredQueueAtThreeLevels() {
        fixture.base(20);
        long[] expected = {20, 26, 33};
        for (int level = 0; level < 3; level++) {
            String id = player(level);
            assertThat(view(id, ROW).nextTimeSec()).isEqualTo(expected[level]);
            assertThat(view(id, OTHER).nextTimeSec()).isEqualTo(formula.evaluateSeconds("TECH_TIME", level + 1));
            var response = service.research(id, new TechResearchReq(UUID.randomUUID().toString(), ROW));
            assertThat(response.timeSec()).isEqualTo(expected[level]);
            PlayerTech stored = players.findByPlayerId(id).orElseThrow().tech();
            assertThat(stored.totalSeconds()).isEqualTo(expected[level]);
            assertThat(stored.finishAt() - stored.startedAt()).isEqualTo(expected[level] * 1000);
        }
    }

    @Test void legacyMissingNullAndZeroPreserveEveryRowsCurveFallback() {
        for (String mode : new String[]{"legacy", "missing", "null", "zero"}) {
            ObjectNode table = fixture.table();
            if (mode.equals("legacy")) ((ObjectNode) table.get("fieldTypes")).remove("timeBaseSec");
            table.withArray("rows").forEach(n -> {
                ObjectNode row = (ObjectNode) n;
                if (mode.equals("legacy") || mode.equals("missing")) row.remove("timeBaseSec");
                else if (mode.equals("null")) row.putNull("timeBaseSec");
            });
            fixture.load(table);
            for (int current = 0; current < 40; current++) {
                String id = player(current);
                for (TechView t : service.list(id).techs()) {
                    assertThat(t.nextTimeSec()).as("%s %s current=%d", mode, t.techId(), current)
                            .isEqualTo(t.level() == t.maxLevel() ? 0 : formula.evaluateSeconds("TECH_TIME", t.level() + 1));
                }
            }
            String id = player(1);
            assertThat(service.research(id, new TechResearchReq(UUID.randomUUID().toString(), ROW)).timeSec()).isEqualTo(17);
        }
    }

    @Test void hotReloadChangesFutureDurationsWithoutRewritingRunningQueue() {
        fixture.base(20);
        String id = player(0);
        var first = service.research(id, new TechResearchReq(UUID.randomUUID().toString(), ROW));
        fixture.base(30);
        PlayerTech frozen = players.findByPlayerId(id).orElseThrow().tech();
        assertThat(frozen.totalSeconds()).isEqualTo(20);
        assertThat(frozen.finishAt()).isEqualTo(first.finishAt());
        assertThat(view(id, ROW).nextTimeSec()).isEqualTo(30);
        fixture.now.set(first.finishAt() + 1);
        assertThat(view(id, ROW).level()).isEqualTo(1);
        assertThat(view(id, ROW).nextTimeSec()).isEqualTo(38);
        assertThat(service.research(id, new TechResearchReq(UUID.randomUUID().toString(), ROW)).timeSec()).isEqualTo(38);
    }

    @Test void arithmeticOverflowRejectsBeforeDebitAndReleasesRequestKey() {
        for (long base : new long[]{Long.MAX_VALUE, 800_000_000_000_000L}) {
            fixture.base(base);
            String id = player(1);
            long wood = players.findByPlayerId(id).orElseThrow().resource("WOOD").current();
            String request = UUID.randomUUID().toString();
            assertThatThrownBy(() -> service.research(id, new TechResearchReq(request, ROW)))
                    .isInstanceOf(ConfigException.class).hasMessageContaining(ROW);
            PlayerSave unchanged = players.findByPlayerId(id).orElseThrow();
            assertThat(unchanged.tech().isResearching()).isFalse();
            assertThat(unchanged.resource("WOOD").current()).isEqualTo(wood);
            fixture.base(20);
            assertThat(service.research(id, new TechResearchReq(request, ROW)).timeSec()).isEqualTo(26);
        }
    }

    @Test void invalidFieldValuesRejectReloadAndRetainOldConfig() {
        fixture.base(20);
        String id = player(0);
        for (String raw : new String[]{"-1", "1.5", "\"20\""}) {
            ObjectNode table = fixture.table();
            try { fixture.row(table, ROW).set("timeBaseSec", fixture.mapper.readTree(raw)); }
            catch (IOException e) { throw new AssertionError(e); }
            assertThatThrownBy(() -> fixture.load(table)).isInstanceOf(ConfigException.class);
            assertThat(view(id, ROW).nextTimeSec()).isEqualTo(20);
        }
    }
}
