package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.QuestCfg;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.season.SeasonTier;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.OrderStatus;

import java.nio.file.Path;

/**
 * 职责：把协议/配置里<b>声称存在</b>的枚举一致性守卫真的建立起来。
 * 依赖：JUnit 5 + AssertJ + game-core 的领域枚举 + 生成的协议枚举 + 配置表。
 *
 * <p><b>本类的存在理由有点特殊</b>：{@code nation.schema.json} 写着 NationOffice
 * 「由 NationContractParityTest 断言」、{@code pay.schema.json} 写着 OrderStatus
 * 「由 PayContractParityTest 断言」、{@code GoalType} 的注释写着「由枚举一致性检查守着」——
 * 而这三个测试当时都不存在。<b>文档里承诺的守卫如果不存在，比没有承诺更糟</b>：
 * 下一个人看到那句话就会假定漂移已经被拦住，于是不再自己检查。
 *
 * <p>三条守卫各自拦的是不同的漂移：
 * <ol>
 *   <li><b>NationOffice ↔ Nation.Office</b>：生成器只支持同文件 $ref，所以协议里重新声明了一份枚举。
 *       漂移的症状是服务端认得的官职客户端显示成未知，UI 只会空白而不报错</li>
 *   <li><b>OrderStatus ⊂ PayOrder.Status</b>：这是<b>刻意的收窄</b>（5 → 3），
 *       所以不能断言相等，要断言的是「领域的每个状态都在映射表里有交代」。
 *       领域将来新增第 6 个状态时，本用例会红，逼着人去改 {@code PayAppService.toStatus} ——
 *       那裡的 default 分支是运行时的最后一道，而这里是编译期的第一道</li>
 *   <li><b>GoalType ↔ quest.json 的 goalType 枚举</b>：配置表的 ENUM 声明与领域枚举各写一份，
 *       漂移的症状是「表里填了一个代码不认识的目标类型」，而那行任务永远不会有任何进度</li>
 *   <li><b>TreasuryPayeeType / TreasurySink ↔ Nation.Payee.Kind / Sink</b>（2026-09-11 随国库支出一起加）：
 *       协议里那两个枚举是从 schema 生成的，而领域侧是手写的两个嵌套枚举，
 *       映射走 {@code valueOf} —— 漂移的症状是玩家点一次俸禄看到 500</li>
 * </ol>
 */
class NationPayEnumParityTest {

    @Test
    @DisplayName("NationOffice 与 Nation.Office 逐字对应（含顺序）：协议里那份是重新声明的，必须被钉住")
    void nationOfficeMatchesTheDomainEnum() {
        List<String> protocol = Arrays.stream(NationOffice.values())
                .map(Enum::name).collect(Collectors.toList());
        List<String> domain = Arrays.stream(com.ironoath.core.nation.Nation.Office.values())
                .map(Enum::name).collect(Collectors.toList());

        assertThat(protocol)
                .as("顺序也要一致：客户端按序号显示官职列表时，乱序会让「大将军」显示在「国王」前面")
                .containsExactlyElementsOf(domain);
    }

    @Test
    @DisplayName("外交关系的三份定义必须逐字一致：协议枚举、领域枚举、global 表参数")
    void diplomacyRelationMatchesTheDomainEnum() {
        List<String> protocol = Arrays.stream(com.ironoath.web.dto.generated.DiplomacyRelation.values())
                .map(Enum::name).collect(Collectors.toList());
        List<String> domain = Arrays.stream(com.ironoath.core.nation.Nation.Diplomacy.values())
                .map(Enum::name).collect(Collectors.toList());
        assertThat(protocol).containsExactlyInAnyOrderElementsOf(domain);

        // 表里那份 NATION_DIPLOMACY_RELATIONS 以前谁都不读 —— 一个没人读的参数比没有参数更坏：
        // 策划改了它以为换了档位集合，实际什么都不会变，而且不会有任何地方报错。
        // 现在把它拉进同一条断言，三处漂移任何一处都会红。
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(java.nio.file.Path.of("contract/config"));
        List<String> inTable = java.util.Arrays.stream(
                configs.stringParam("NATION_DIPLOMACY_RELATIONS").split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
        assertThat(inTable).as("global 表里的外交关系集合必须与两份枚举完全相同")
                .containsExactlyInAnyOrderElementsOf(domain);
    }

    @Test
    @DisplayName("OrderStatus 是 PayOrder.Status 的刻意收窄：领域每个状态都必须在映射里有交代")
    void orderStatusCoversEveryDomainState() {
        Set<String> protocol = Arrays.stream(OrderStatus.values())
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(protocol).containsExactlyInAnyOrder("PENDING", "SUCCESS", "FAILED");

        // 领域有 5 个状态，协议只有 3 个。多出来的两个必须被显式映射（见 PayAppService.toStatus）：
        // PAID_UNFULFILLED → SUCCESS + retryQueued=true（钱收了货没发，对玩家而言是「已购买」，
        //   映射成 FAILED 会让他再付一次或发起一笔不该发生的退款）
        // CANCELLED → FAILED（对客户端是同一件事：不要发货、可以重新下单）
        Set<String> narrowed = Set.of("PAID_UNFULFILLED", "CANCELLED");
        for (PayOrder.Status status : PayOrder.Status.values()) {
            assertThat(protocol.contains(status.name()) || narrowed.contains(status.name()))
                    .as("领域状态 %s 既不在协议里、也不在已登记的收窄名单里："
                            + "说明 PayOrder 新增了一个状态而 PayAppService.toStatus 没有跟上。"
                            + "那条 switch 的 default 分支会在运行时抛，但那时已经上线了", status)
                    .isTrue();
        }
        // 反向：收窄名单里的每个状态都必须真的存在于领域，否则名单会慢慢变成一份过期的注释
        for (String name : narrowed) {
            assertThat(Arrays.stream(PayOrder.Status.values()).anyMatch(s -> s.name().equals(name)))
                    .as("收窄名单里的 %s 在 PayOrder.Status 里已经不存在了，应当从名单里删掉", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("GoalType 与 quest.json 的 goalType 枚举逐字对应：表里填了代码不认识的类型，那行任务永远不会有进度")
    void goalTypeMatchesTheQuestTable() {
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        Set<String> inTable = configs.all(QuestCfg.class).stream()
                .map(cfg -> cfg.goalType().name())
                .collect(Collectors.toSet());
        Set<String> inCode = Arrays.stream(GoalType.values())
                .map(Enum::name).collect(Collectors.toSet());

        assertThat(inTable)
                .as("表里出现的每个目标类型代码都必须认识：不认识的话那行任务永远不会累加进度，"
                        + "而玩家看到的是一个永远停在 0/N 的任务")
                .isSubsetOf(inCode);
        assertThat(inCode)
                .as("代码里的每个目标类型都应当至少被一行任务用到：用不到说明它是死枚举，"
                        + "或者表里漏配了对应的任务")
                .isSubsetOf(inTable);
    }

    @Test
    @DisplayName("协议 SeasonTier 与领域 SeasonTier.Tier 名字与顺序都要一致：段位高低是按 ordinal 比的")
    void seasonTierMatchesTheDomainEnumInNameAndOrder() {
        assertThat(Arrays.stream(com.ironoath.web.dto.generated.SeasonTier.values())
                .map(Enum::name).toList())
                .as("顺序也要一致：账本里比较「哪一档更高」用的是 ordinal，"
                        + "而主存档缓存把段位存成名字 —— 只比名字的话，插一档进去不会有任何东西变红，"
                        + "但历史最高段位会集体错位")
                .containsExactly(Arrays.stream(SeasonTier.Tier.values())
                        .map(Enum::name).toArray(String[]::new));
    }

    @Test
    @DisplayName("国库落点的两个枚举与领域一一对应：toSink 走 valueOf，漂移会变成运行期 500")
    void treasuryPayeeEnumsMatchTheDomain() {
        assertThat(Arrays.stream(com.ironoath.web.dto.generated.TreasuryPayeeType.values())
                .map(Enum::name).toList())
                .as("协议与领域各声明一份是分层的必然结果，但名字必须一致 —— "
                        + "NationAppService.toPayee 两边对着 switch，而 toSink 直接 valueOf")
                .containsExactlyElementsOf(Arrays.stream(
                        com.ironoath.core.nation.Nation.Payee.Kind.values()).map(Enum::name).toList());
        assertThat(Arrays.stream(com.ironoath.web.dto.generated.TreasurySink.values())
                .map(Enum::name).toList())
                .as("消耗性用途也一一对应：名字会进日志的 counterparty 列，改了名历史流水就对不上文档")
                .containsExactlyElementsOf(Arrays.stream(
                        com.ironoath.core.nation.Nation.Payee.Sink.values()).map(Enum::name).toList());
    }
}
