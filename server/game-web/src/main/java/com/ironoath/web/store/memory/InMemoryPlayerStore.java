package com.ironoath.web.store.memory;

import com.ironoath.core.player.PlayerBrief;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：玩家仓储的内存实现 —— 供 dev 本地零依赖启动与全部单测使用。
 * 依赖：game-core 的 PlayerRepository 端口。
 *
 * <p>刻意与 Mongo 实现保持<b>相同语义</b>，否则单测过了、上线就炸：
 * <ul>
 *   <li>读写都返回<b>副本</b>：调用方拿到的对象改了不 save 就不生效，
 *       这能提前暴露「忘记持久化」的 bug（Mongo 天然是这个语义）</li>
 *   <li>{@link #insertIfAbsent} 用 {@code putIfAbsent} 保证原子性，
 *       模拟 Mongo 的 deviceId 唯一索引，让并发建号的竞态在单测里也能复现</li>
 *   <li>{@link #save} 做乐观锁版本比对，版本不匹配抛异常</li>
 * </ul>
 *
 * <p>进程内存储，重启即丢。仅用于开发与测试，生产必须用 mongo（见 application-prod.yml）。
 */
public final class InMemoryPlayerStore implements PlayerRepository {

    private final Map<String, PlayerSave> byPlayerId = new ConcurrentHashMap<>();
    private final Map<String, String> deviceIdToPlayerId = new ConcurrentHashMap<>();

    @Override
    public Optional<PlayerSave> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPlayerId.get(playerId)).map(PlayerSave::copy);
    }

    @Override
    public Optional<PlayerSave> findByDeviceId(String deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        String playerId = deviceIdToPlayerId.get(deviceId);
        return playerId == null ? Optional.empty() : findByPlayerId(playerId);
    }

    @Override
    public Map<String, PlayerSave> findByPlayerIds(Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PlayerSave> out = new LinkedHashMap<>();
        for (String playerId : playerIds) {
            if (playerId == null) {
                continue;
            }
            // 与单条读同一语义：返回副本。批量读若是唯一能拿到内部对象引用的口子，
            // 「改了不 save 就不生效」这条纪律就会在搜索这条路径上悄悄失效
            PlayerSave stored = byPlayerId.get(playerId);
            if (stored != null) {
                out.put(playerId, stored.copy());
            }
        }
        return out;
    }

    @Override
    public Map<String, PlayerBrief> findBriefs(Collection<String> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PlayerBrief> out = new LinkedHashMap<>();
        for (String playerId : playerIds) {
            if (playerId == null) {
                continue;
            }
            PlayerSave stored = byPlayerId.get(playerId);
            if (stored != null) {
                // 与 findByPlayerIds 同一把锁：投影不需要 copy（它是新造的不可变记录），
                // 但仍要锁 —— lastLoginAt 是个非 volatile 的 long，touchLogin 正在写它，
                // 而无锁读一个 long 在 JVM 规范里并不保证一次读完（撕裂出的半个新半个旧
                // 会画出一个 1970 年附近的"最近活跃"，且只在并发登录时偶发）。
                // Mongo 侧没有这个问题（服务端单文档更新是原子的），所以这条只能在这里钉。
                synchronized (stored) {
                    out.put(playerId, new PlayerBrief(playerId, stored.nickName(), stored.cityLevel(),
                            stored.lastLoginAt(),
                            stored.power() == null ? 0L : stored.power().displayPower()));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized boolean insertIfAbsent(PlayerSave save) {
        if (save == null) {
            throw new IllegalArgumentException("待插入的存档不得为 null");
        }
        // 先写存档、后占用 deviceId，顺序不能反：
        // 反过来的话两步之间留了窗口，并发的另一方会看到「deviceId 已冲突、
        // 但按 playerId 读不到存档」，上层只能报「系统繁忙」—— 而读方是不加锁的，
        // 光靠 synchronized 挡不住它，必须让 deviceId 的映射只在存档已存在之后才出现。
        // synchronized 则是为了挡住两个并发插入同时走到「清理自己那份」的竞态。
        // Mongo 实现靠唯一索引 + 单次插入天然是原子的，这里必须自己补上，
        // 否则两个实现的语义就不一致了（而这个类的存在意义就是与 Mongo 同语义）
        byPlayerId.put(save.playerId(), save.copy());
        String existing = deviceIdToPlayerId.putIfAbsent(save.deviceId(), save.playerId());
        if (existing != null) {
            byPlayerId.remove(save.playerId());
            return false;
        }
        return true;
    }

    @Override
    public void save(PlayerSave save) {
        if (save == null) {
            throw new IllegalArgumentException("待保存的存档不得为 null");
        }
        PlayerSave stored = byPlayerId.get(save.playerId());
        if (stored == null) {
            throw new IllegalStateException("存档不存在，无法更新：playerId=" + save.playerId());
        }
        synchronized (stored) {
            if (stored.version() != save.version()) {
                throw new IllegalStateException("乐观锁冲突：playerId=" + save.playerId()
                        + "，存储版本=" + stored.version() + "，提交版本=" + save.version()
                        + "。请重读存档后重试。");
            }
            save.incrementVersion();
            byPlayerId.put(save.playerId(), save.copy());
        }
    }

    @Override
    public void touchLogin(String playerId, long now) {
        PlayerSave stored = byPlayerId.get(playerId);
        if (stored == null) {
            // 存档可能刚被删号；登录时间戳不是关键数据，静默忽略即可
            return;
        }
        // 内部持有的是私有副本（对外一律返回 copy()），因此可以在锁内直接改，
        // 不需要走「读-改-写 + 版本校验」，也就不会与其他并发登录互相冲突
        synchronized (stored) {
            stored.touchLogin(now);
        }
    }

    /** 测试辅助：清空全部数据。 */
    public void clear() {
        byPlayerId.clear();
        deviceIdToPlayerId.clear();
    }

    public int size() {
        return byPlayerId.size();
    }
}
