package com.ironoath.web.nation;

import java.util.Objects;
import java.util.function.Supplier;
import com.ironoath.core.nation.Nation;

/** One player's successful nation lookup within one output calculation, never a tick/global cache. */
public final class NationOutputReadScope {
    private final String playerId;
    private boolean resolved;
    private Nation snapshot;

    public NationOutputReadScope(String playerId) { this.playerId = playerId; }

    Nation resolve(String requestedPlayer, Supplier<Nation> lookup) {
        if (!Objects.equals(playerId, requestedPlayer)) {
            throw new IllegalArgumentException("国家产出读取范围与玩家不一致");
        }
        if (!resolved) {
            // Assign only after success: null is a valid absence; exceptions stay retryable.
            snapshot = lookup.get();
            resolved = true;
        }
        return snapshot;
    }

    /** The same successful identity snapshot used by nation-tech and policy reads in this output calculation. */
    public String resolvedNationId() { return resolved && snapshot!=null ? snapshot.id() : null; }
}
