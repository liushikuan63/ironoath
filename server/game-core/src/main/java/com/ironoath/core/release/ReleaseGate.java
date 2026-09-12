package com.ironoath.core.release;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：发布闸门 —— 版本检查、强制更新、灰度开关、配置热更清单（B16 §5，验收 7/8）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>禁止项：不要在没有灰度开关的情况下全量发布</b>。
 * 灰度的意义不是「慢慢放」，而是「出问题时受影响的人少」——
 * 一个 5% 的灰度批次里崩溃率翻倍，回滚只影响 5% 的玩家；
 * 全量发布时同样的问题会让整个服都进不去，而那时回滚已经来不及了。
 *
 * <p><b>强制更新与灰度是两件正交的事</b>：
 * 灰度决定「谁能拿到新版本」，强制更新决定「拿到旧版本的人还能不能玩」。
 * 把两者混成一个开关的后果是：想灰度就必须让没被灰到的人无法游戏，
 * 而那等于用强制更新做灰度 —— 95% 的玩家会被挡在门外。
 *
 * <p><b>配置热更靠 hash 而不是版本号</b>（验收 7）：版本号是人给的，
 * 会出现「改了内容忘了升版本」，那时客户端认为自己是最新的而实际上不是。
 * hash 由内容算出，改一个字符它就变 —— 内容变了而 hash 没变在数学上不可能。
 */
public final class ReleaseGate {

    /**
     * @param latestVersion    最新客户端版本（形如 "1.4.0"）
     * @param minSupportedVersion 最低可玩版本。低于它的客户端强制更新
     * @param grayPercent      灰度比例（定点 0~1.0）。来源 global.RELEASE_GRAY_PERCENT
     * @param forceUpdateNotice 强制更新时的提示文案。<b>必须给</b>：
     *                         只说「请更新」而不说为什么，玩家会以为游戏坏了
     */
    public record Rules(String latestVersion, String minSupportedVersion, long grayPercentFixed,
                        String forceUpdateNotice) {
        public Rules {
            if (latestVersion == null || latestVersion.isBlank()) {
                throw new IllegalArgumentException("latestVersion 不得为空");
            }
            if (minSupportedVersion == null || minSupportedVersion.isBlank()) {
                throw new IllegalArgumentException("minSupportedVersion 不得为空");
            }
            if (grayPercentFixed < 0 || grayPercentFixed > 10_000L) {
                throw new IllegalArgumentException("grayPercent 必须落在 [0, 1.0] 的定点区间，实际="
                        + grayPercentFixed);
            }
            if (compareVersions(minSupportedVersion, latestVersion) > 0) {
                throw new IllegalArgumentException("minSupportedVersion(" + minSupportedVersion
                        + ") 高于 latestVersion(" + latestVersion
                        + ")：那样所有客户端都会被要求更新到一个还不存在的版本，全服进不去");
            }
            if (forceUpdateNotice == null || forceUpdateNotice.isBlank()) {
                throw new IllegalArgumentException("forceUpdateNotice 不得为空：只说「请更新」而不说为什么，"
                        + "玩家会以为游戏坏了");
            }
        }
    }

    /** 一次版本检查的结果（AppVersionResp 的领域侧对应物）。 */
    public record Verdict(boolean forceUpdate, boolean grayEnabled, String latestVersion, String notice) {
    }

    /** 一张配置表的元信息。 */
    public record TableMeta(String name, String version, String hash) {
        public TableMeta {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("表名不得为空");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("表 " + name + " 的版本不得为空");
            }
            if (hash == null || hash.isBlank()) {
                throw new IllegalArgumentException("表 " + name + " 的 hash 不得为空："
                        + "hash 是热更判定的唯一依据，缺了它客户端只能靠版本号判断，"
                        + "而「改了内容忘了升版本」是配置表最常见的事故");
            }
        }
    }

    /** 配置清单。 */
    public record Manifest(String manifestVersion, List<TableMeta> tables) {
        public Manifest {
            if (manifestVersion == null || manifestVersion.isBlank()) {
                throw new IllegalArgumentException("manifestVersion 不得为空");
            }
            if (tables == null || tables.isEmpty()) {
                throw new IllegalArgumentException("配置清单不得为空：空清单会让客户端以为所有表都不需要更新");
            }
            Map<String, TableMeta> byName = new LinkedHashMap<>();
            for (TableMeta table : tables) {
                TableMeta previous = byName.put(table.name(), table);
                if (previous != null) {
                    throw new IllegalArgumentException("配置清单里表 " + table.name()
                            + " 出现了两次：客户端不知道该用哪一份");
                }
            }
            tables = List.copyOf(tables);
        }

        public TableMeta table(String name) {
            for (TableMeta table : tables) {
                if (table.name().equals(name)) {
                    return table;
                }
            }
            return null;
        }
    }

    private final Rules rules;

    public ReleaseGate(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /**
     * 检查一个客户端版本。
     *
     * @param clientVersion 客户端自报版本
     * @param playerId      玩家 id。灰度按它做稳定哈希 —— 同一个玩家的灰度结果必须每次一致，
     *                      否则他刷新一次就可能从灰度里掉出去，
     *                      而「刚才能玩现在不能玩」是最难排查的一类投诉
     */
    public Verdict check(String clientVersion, String playerId) {
        if (clientVersion == null || clientVersion.isBlank()) {
            throw new IllegalArgumentException("clientVersion 不得为空");
        }
        boolean forced = compareVersions(clientVersion, rules.minSupportedVersion()) < 0;
        boolean gray = inGray(playerId);
        String notice = forced ? rules.forceUpdateNotice() : null;
        return new Verdict(forced, gray, rules.latestVersion(), notice);
    }

    /**
     * 灰度判定。
     *
     * <p>用 playerId 的稳定哈希而不是随机数：随机的话每次请求结果都可能不同，
     * 玩家会在灰度内外反复横跳，而灰度的意义正是「同一批人稳定地拿到新版本」。
     */
    public boolean inGray(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            // 没有玩家 id（未登录）时不给灰度：灰度批次里的崩溃会归因不到具体玩家。
            // 这条排在 100% 之前 —— 100% 灰度说的是「所有玩家」，而未登录的人还不是玩家
            return false;
        }
        if (rules.grayPercentFixed() >= 10_000L) {
            return true;
        }
        if (rules.grayPercentFixed() <= 0L) {
            return false;
        }
        int bucket = Math.floorMod(playerId.hashCode(), 10_000);
        return bucket < rules.grayPercentFixed();
    }

    /**
     * 比较两个点分版本号。
     *
     * <p>按段比较而不是按字符串比较："1.10.0" 字符串序小于 "1.9.0"，
     * 而它其实是更高的版本。用字符串比较的话，1.9 之后的所有版本都会被判定为「更旧」，
     * 于是全服被要求强制更新 —— 那是一个会直接停服的 bug。
     *
     * @return 负数表示 a 更旧，0 表示相同，正数表示 a 更新
     */
    public static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            long lv = i < left.length ? parseSegment(left[i]) : 0L;
            long rv = i < right.length ? parseSegment(right[i]) : 0L;
            if (lv != rv) {
                return Long.compare(lv, rv);
            }
        }
        return 0;
    }

    /** 版本号的一段。允许 "1.4.0-beta" 这种带后缀的写法，只取数字前缀。 */
    private static long parseSegment(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0L;
        }
        // 逐位累加而不是 parseLong 后吞掉溢出：溢出时回 0 会让一个特别长的号段判成「比 1 还旧」，
        // 于是最低版本判定反向 —— 被强制更新的恰好是最新的客户端。钳在上限至少顺序不会颠倒。
        long value = 0L;
        for (int i = 0; i < end; i++) {
            int digit = segment.charAt(i) - '0';
            if (value > (Long.MAX_VALUE - digit) / 10L) {
                return Long.MAX_VALUE;
            }
            value = value * 10L + digit;
        }
        return value;
    }

    /**
     * 找出客户端需要更新的表（验收 7：改配置不改包生效）。
     *
     * @param server 服务端当前清单
     * @param clientHashes 客户端手里各表的 hash
     * @return 需要下发的表名，按清单顺序。<b>只比 hash，不比版本号</b>
     */
    public static List<String> outdatedTables(Manifest server, Map<String, String> clientHashes) {
        if (server == null) {
            throw new IllegalArgumentException("server manifest 不得为 null");
        }
        Map<String, String> have = clientHashes == null ? Map.of() : clientHashes;
        List<String> out = new ArrayList<>();
        for (TableMeta table : server.tables()) {
            String clientHash = have.get(table.name());
            if (clientHash == null || !clientHash.equals(table.hash())) {
                out.add(table.name());
            }
        }
        return Collections.unmodifiableList(out);
    }

    public Rules rules() {
        return rules;
    }
}
