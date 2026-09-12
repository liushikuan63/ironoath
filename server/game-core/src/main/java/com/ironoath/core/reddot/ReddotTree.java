package com.ironoath.core.reddot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 职责：红点树 —— 叶子注册条件函数，父节点自动聚合（B12 §4，验收 1/2）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>B12 把红点列为「最容易被低估、也最容易做砸」的一块，理由是维护成本会指数上升</b>：
 * 如果每个业务模块自己判断「我该不该亮红点」，那么每加一个功能就要回去改一堆散落的判断，
 * 而漏改的表现是「有红点点进去却没东西」或「明明有事可做却没有红点」——
 * 两种都不会报错，只会被玩家当成 bug 报上来。
 *
 * <p><b>本类的三条设计约束</b>：
 * <ol>
 *   <li><b>只注册叶子</b>。父节点由路径自动派生（{@code city/building/woodmill} 隐含
 *       {@code city/building} 与 {@code city}），不需要也不允许单独声明 ——
 *       手动维护父子关系迟早会和路径写法漂移，而漂移的表现就是聚合错</li>
 *   <li><b>父节点亮 = 任一后代叶子亮</b>，没有其他规则。「父节点自己有条件」这种需求
 *       应当表达成一个叶子（例如 {@code city/building/queue_free}），
 *       否则同一个节点既是聚合者又是条件源，它的亮灭就有两种解释</li>
 *   <li><b>没有注册叶子的父节点永远不亮</b>（验收 1 的「无假红点」）。
 *       一个空的父路径亮起来，玩家点进去只会看到一片空白</li>
 * </ol>
 *
 * <p><b>条件函数抛异常时按「不亮」处理并记数</b>：一个算不出来的红点不该让整个树查询失败，
 * 但也不能静默 —— 所以 {@link #errorCount()} 暴露出来给单测与埋点断言。
 * 静默吞掉的话，那个叶子会永远不亮，而没人知道为什么。
 *
 * <p><b>本类不缓存结果</b>：红点条件是「现在有没有可做的事」，它依赖的存档随时在变，
 * 缓存就必须有一套失效机制，而失效漏掉一次的后果正是验收 1 要防的「1 帧内没聚合对」。
 * 每次查询现算，叶子数量是几十个量级，一次遍历的成本远低于维护缓存一致性的成本。
 */
public final class ReddotTree {

    /** 路径分隔符。与 B12 §4 的示例（{@code city/building}）一致。 */
    public static final String SEPARATOR = "/";

    /**
     * 一个叶子条件。
     *
     * @param playerId 玩家 id。红点是<b>每个玩家各自的</b>，
     *                 所以条件函数必须带这个参数 —— 不带的话就只能写成全服红点
     */
    public interface Condition extends Function<String, Boolean> {
    }

    /** 一条叶子注册记录。 */
    public record Leaf(String key, Condition condition, String description) {
        public Leaf {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("红点叶子的 key 不得为空");
            }
            if (condition == null) {
                throw new IllegalArgumentException("红点叶子的条件函数不得为 null：" + key);
            }
            validateKey(key);
        }
    }

    /** 一个节点的聚合结果。 */
    public record NodeState(String key, boolean lit, List<NodeState> children) {
        public NodeState {
            children = List.copyOf(children);
        }
    }

    /** 叶子 key → 注册记录。LinkedHashMap 让遍历顺序稳定，便于抓包对比与日志排查。 */
    private final Map<String, Leaf> leaves = new LinkedHashMap<>();
    private int errorCount;

    /**
     * 注册一个叶子。
     *
     * <p><b>同 key 重复注册直接抛错</b>：两个条件挂在一个叶子上，意味着其中一个是死代码，
     * 而删掉哪一个都不敢 —— 这种状态留着只会让下一个人猜。
     *
     * @param description 这个红点在提示玩家什么。写进注册而不是留在业务代码的注释里，
     *                    是因为「红点为什么亮」这个问题在排查时最先被问到，
     *                    而那时业务代码里的那行判断早就被重构走了
     */
    public void register(String key, Condition condition, String description) {
        Leaf leaf = new Leaf(key, condition, description);
        Leaf previous = leaves.putIfAbsent(key, leaf);
        if (previous != null) {
            throw new IllegalStateException("红点叶子重复注册：" + key
                    + "。已有描述「" + previous.description() + "」，新描述「" + description
                    + "」。两个条件挂在一个叶子上意味着其中一个是死代码");
        }
    }

    /** 注销一个叶子（功能下线时）。返回是否存在过。 */
    public boolean unregister(String key) {
        return leaves.remove(key) != null;
    }

    /**
     * 某个节点此刻是否亮。
     *
     * <p>传入叶子 key 就是它自己的条件；传入父路径就是「任一后代叶子亮」。
     * 传入一个既不是叶子也不是任何叶子的前缀的路径 ⇒ 永远不亮（验收 1：无假红点）。
     */
    public boolean isLit(String key, String playerId) {
        validateKey(key);
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空：红点是每个玩家各自的");
        }
        String prefix = key + SEPARATOR;
        boolean lit = false;
        for (Map.Entry<String, Leaf> entry : leaves.entrySet()) {
            String leafKey = entry.getKey();
            if (!leafKey.equals(key) && !leafKey.startsWith(prefix)) {
                continue;
            }
            if (evaluate(entry.getValue(), playerId)) {
                lit = true;
                // 不 break：剩下的叶子仍然要求值，否则 errorCount 会随查询路径变化，
                // 那个计数就没法用来断言「所有条件都没抛异常」
            }
        }
        return lit;
    }

    /**
     * 某个子树的完整状态（含所有中间节点）。
     *
     * <p>这是给客户端 {@code ReddotTreeResp} 用的：客户端只消费这棵树，
     * 不做任何业务判断（B12 §4 的注释里明写了这条分工）。
     *
     * @param root 子树根路径；传空串表示整棵树
     */
    public NodeState subtree(String root, String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        String normalized = (root == null || root.isBlank()) ? "" : root;
        if (!normalized.isEmpty()) {
            validateKey(normalized);
        }
        // 收集这棵子树里出现过的全部路径（叶子 + 它们的所有祖先），按路径排序后自底向上聚合。
        // 用 TreeMap 保证父节点一定在它的全部子节点之后被处理（字典序下 "a" < "a/b"，
        // 所以倒序遍历时子节点先入表）
        Map<String, Boolean> litByKey = new HashMap<>();
        Map<String, List<String>> childrenByKey = new LinkedHashMap<>();
        Set<String> allPaths = new LinkedHashSet<>();
        String prefix = normalized.isEmpty() ? "" : normalized + SEPARATOR;

        for (String leafKey : leaves.keySet()) {
            if (!normalized.isEmpty() && !leafKey.startsWith(prefix) && !leafKey.equals(normalized)) {
                continue;
            }
            allPaths.addAll(ancestorsWithin(leafKey, normalized));
        }
        List<String> ordered = new ArrayList<>(allPaths);
        Collections.sort(ordered);
        // 倒序：深的先算，浅的后算，于是父节点聚合时子节点的结果已经就绪
        for (int i = ordered.size() - 1; i >= 0; i--) {
            String path = ordered.get(i);
            Leaf leaf = leaves.get(path);
            boolean self = leaf != null && evaluate(leaf, playerId);
            boolean fromChildren = false;
            for (String child : childrenByKey.getOrDefault(path, List.of())) {
                if (Boolean.TRUE.equals(litByKey.get(child))) {
                    fromChildren = true;
                }
            }
            litByKey.put(path, self || fromChildren);
            int slash = path.lastIndexOf(SEPARATOR);
            // parent 永远是 path 的严格前缀；根节点的 parent 是空串，
            // buildNode 从子树根开始查，不会读到它，多一条记录无害
            String parent = slash < 0 ? "" : path.substring(0, slash);
            childrenByKey.computeIfAbsent(parent, k -> new ArrayList<>()).add(path);
        }
        return buildNode(normalized, litByKey, childrenByKey);
    }

    /** 已注册叶子的数量。用于断言「红点判断没有散落在业务模块里」—— 数量应当随功能数增长。 */
    public int leafCount() {
        return leaves.size();
    }

    /** 全部叶子 key（按注册顺序）。 */
    public Set<String> leafKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(leaves.keySet()));
    }

    /**
     * 条件函数抛异常的次数。
     *
     * <p>单测与埋点都应当断言它为 0：一个算不出来的红点会永远不亮，
     * 而玩家看到的是「明明有事可做却没有红点」，他不会报这个 bug —— 他只是不点进去。
     */
    public int errorCount() {
        return errorCount;
    }

    /** 清空（单测用）。 */
    public void clear() {
        leaves.clear();
        errorCount = 0;
    }

    // ---------- 内部 ----------

    private boolean evaluate(Leaf leaf, String playerId) {
        try {
            Boolean value = leaf.condition().apply(playerId);
            return Boolean.TRUE.equals(value);
        } catch (RuntimeException e) {
            errorCount++;
            // 记日志而不是抛出：红点是辅助信息，一个叶子的异常不该让整个面板打不开。
            // 但也不能静默 —— errorCount 会被单测与埋点盯着
            System.err.println("[ReddotTree] 红点条件抛异常，按不亮处理 key=" + leaf.key()
                    + " playerId=" + playerId + " 描述=" + leaf.description() + " 原因=" + e.getMessage());
            return false;
        }
    }

    /** 一个叶子在指定子树内的全部祖先路径（含它自己）。 */
    private static List<String> ancestorsWithin(String leafKey, String root) {
        List<String> out = new ArrayList<>();
        String current = leafKey;
        while (true) {
            if (!root.isEmpty() && !current.startsWith(root)) {
                break;
            }
            out.add(current);
            int slash = current.lastIndexOf(SEPARATOR);
            if (slash < 0) {
                break;
            }
            current = current.substring(0, slash);
            if (current.isEmpty()) {
                break;
            }
        }
        return out;
    }

    private NodeState buildNode(String key, Map<String, Boolean> litByKey,
                                Map<String, List<String>> childrenByKey) {
        List<NodeState> children = new ArrayList<>();
        for (String child : childrenByKey.getOrDefault(key, List.of())) {
            children.add(buildNode(child, litByKey, childrenByKey));
        }
        // 按 key 排序，让下发给客户端的树结构稳定（客户端要做 diff）
        children.sort((a, b) -> a.key().compareTo(b.key()));
        return new NodeState(key, Boolean.TRUE.equals(litByKey.get(key)), children);
    }

    private static void validateKey(String key) {
        if (key.startsWith(SEPARATOR) || key.endsWith(SEPARATOR)) {
            throw new IllegalArgumentException("红点路径不得以 " + SEPARATOR + " 开头或结尾，实际=" + key
                    + "。带首尾分隔符的路径会多出一个空段，聚合时它会被当成一个真实节点");
        }
        if (key.contains(SEPARATOR + SEPARATOR)) {
            throw new IllegalArgumentException("红点路径不得含连续分隔符，实际=" + key);
        }
    }
}
