import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：证明 `Map.copyOf` **不保持插入顺序** —— #617 的根因复现器（可失败判据）。
 *
 * <p>背景：`PlayerSave.resources()` 的 javadoc 写着「资源快照的只读视图（保持配置表顺序）」，
 * 实现却是 `Map.copyOf(resources)`。`Map.copyOf` 返回 `ImmutableCollections.MapN`，
 * 它按 **SALT** 散列摆放键来防碰撞攻击，而 SALT 每个 JVM 进程都不同 ⇒ 迭代顺序每次启动都可能变。
 * 这条链一路传到 `/city/list` 的 `resources` 字段、再传到客户端资源条的格子顺序，
 * 于是**玩家每次进游戏看到的资源排列可能不同**，而所有按「第 N 格」写死的量具都会时绿时红。
 *
 * <p>用法：反复起几次 JVM，看输出行是否一致。
 *   {@code for i in 1 2 3 4 5; do java -cp <classes> tools/MapCopyOrderProof; done}
 * 顺序**一致** ⇒ 根因不成立（本判据失败，说明还有别的因素）；
 * 出现**任意两行不同** ⇒ 根因成立。
 *
 * <p>为什么值得留成文件：#610~#616 连续七格都在追「点不中」，每一格的结论都被下一格推翻，
 * 根因是**没有人核过这一行 javadoc 与实现的矛盾**。一个能反复跑的最小复现，比再写一条
 * 「可能是顺序问题」的推测有用。
 */
public final class MapCopyOrderProof {

    /** 配置表里的资源顺序（`resource.json` 里的声明序）。 */
    private static final List<String> CONFIG_ORDER =
            List.of("WOOD", "STONE", "IRON", "GRAIN", "GOLD", "STAMINA");

    private MapCopyOrderProof() {
    }

    public static void main(String[] args) {
        Map<String, Integer> byConfig = new LinkedHashMap<>();
        for (String key : CONFIG_ORDER) {
            byConfig.put(key, 1);
        }
        // 与 PlayerSave.resources() 逐字相同的那一句
        Map<String, Integer> view = Map.copyOf(byConfig);
        System.out.println("copyOf  " + String.join(",", view.keySet()));
        // 对照组：真要保序该用的那个
        Map<String, Integer> kept = Collections.unmodifiableMap(new LinkedHashMap<>(byConfig));
        System.out.println("保序方案 " + String.join(",", kept.keySet()));
    }
}