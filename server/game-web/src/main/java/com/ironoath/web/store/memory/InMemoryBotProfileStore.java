package com.ironoath.web.store.memory;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.bot.BotProfileStore;

@Component
@ConditionalOnProperty(name="ironoath.storage",havingValue="memory",matchIfMissing=true)
public class InMemoryBotProfileStore implements BotProfileStore {
    private final ConcurrentHashMap<String,BotProfile> profiles=new ConcurrentHashMap<>();
    @Override public Optional<BotProfile> find(String id) {
        return id==null?Optional.empty():Optional.ofNullable(profiles.get(id));
    }
    @Override public Collection<BotProfile> all() {
        return profiles.values().stream().sorted(Comparator.comparing(BotProfile::botId)).toList();
    }
    @Override public Set<String> existingIds(Collection<String> playerIds) {
        if(playerIds==null || playerIds.isEmpty())return Set.of();
        Set<String> found=new HashSet<>();
        for(String id:playerIds)if(id!=null && profiles.containsKey(id))found.add(id);
        return Set.copyOf(found);
    }
    @Override public BotProfile put(BotProfile profile) { return profiles.put(profile.botId(),profile); }
    @Override public boolean remove(String id) { return id!=null&&profiles.remove(id)!=null; }
    @Override public void clear() { profiles.clear(); }
}
