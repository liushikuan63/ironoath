package com.ironoath.web.nation;

import java.util.function.Supplier;
import com.ironoath.core.lock.PlayerLock;

/**
 * 国家集结与组织变更共用的互斥边界，复用现有 JVM/Redisson 可重入锁。
 * 请求只能按玩家锁→国家锁进入；国家锁内不得再拿任何玩家锁。
 * 跨玩家退款靠军队 CAS 与同档幂等凭据，不会形成国王/官员互等的锁环。
 */
public final class NationOperationLock {
    private final PlayerLock locks;

    public NationOperationLock(PlayerLock locks) {
        this.locks = locks;
    }

    public <T> T runLocked(String nationId, Supplier<T> action) {
        return locks.runLocked("@nation:" + nationId, 3000L, action);
    }
}
