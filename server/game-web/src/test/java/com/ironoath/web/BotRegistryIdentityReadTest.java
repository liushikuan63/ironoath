package com.ironoath.web;

import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.bot.BotProfileStore;
import com.ironoath.web.bot.BotRegistry;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotRegistryIdentityReadTest {
    /** A persistent identity port can answer these queries without materializing profiles. */
    static class IdentityStore implements BotProfileStore {
        final Set<String> identities = new LinkedHashSet<>();
        RuntimeException unavailable;
        @Override public Collection<String> ids() {
            if (unavailable != null) throw unavailable;
            return List.copyOf(identities);
        }
        @Override public int size() {
            if (unavailable != null) throw unavailable;
            return identities.size();
        }
        @Override public Collection<BotProfile> all() {
            throw new AssertionError("Identity reads must not load complete profiles");
        }
        @Override public Optional<BotProfile> find(String id) { return Optional.empty(); }
        @Override public Set<String> existingIds(Collection<String> ids) {
            Set<String> result = new LinkedHashSet<>(identities);
            result.retainAll(ids);
            return result;
        }
        @Override public BotProfile put(BotProfile profile) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(String id) { return identities.remove(id); }
        @Override public void clear() { identities.clear(); }
    }

    @Test void enumerationUsesIdentityPortInsteadOfCompleteProfiles() {
        IdentityStore store = new IdentityStore();
        store.identities.addAll(List.of("bot-b", "bot-a"));
        assertEquals(List.of("bot-b", "bot-a"), List.copyOf(new BotRegistry(null, store).botIds()));
    }

    @Test void sizeUsesAuthoritativeCountInsteadOfCompleteProfiles() {
        IdentityStore store = new IdentityStore();
        BotRegistry registry = new BotRegistry(null, store);
        assertEquals(0, registry.size());
        store.identities.addAll(List.of("bot-a", "bot-b"));
        assertEquals(2, registry.size());
    }

    @Test void independentRegistriesObserveNewAndRemovedIdentitiesWithoutPrivateCache() {
        IdentityStore store = new IdentityStore();
        BotRegistry first = new BotRegistry(null, store), second = new BotRegistry(null, store);
        assertTrue(first.botIds().isEmpty());
        store.identities.add("registered-by-another-instance");
        assertEquals(List.of("registered-by-another-instance"), List.copyOf(second.botIds()));
        assertEquals(1, first.size());
        store.remove("registered-by-another-instance");
        assertTrue(first.botIds().isEmpty());
        assertEquals(0, second.size());
    }

    @Test void unavailableIdentityStoreFailsClosedForBothQueries() {
        IdentityStore store = new IdentityStore();
        store.unavailable = new IllegalStateException("authoritative identity unavailable");
        BotRegistry registry = new BotRegistry(null, store);
        assertSame(store.unavailable, assertThrows(IllegalStateException.class, registry::botIds));
        assertSame(store.unavailable, assertThrows(IllegalStateException.class, registry::size));
    }
}
