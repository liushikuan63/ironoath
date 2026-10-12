package com.ironoath.web.store.memory;

import java.util.List;
import com.ironoath.web.activity.ActivityRuleHistoryStore;

/** 测试/开发规则历史；生产使用持久化实现。 */
public final class InMemoryActivityRuleHistoryStore implements ActivityRuleHistoryStore {
    private List<Revision> revisions = List.of();
    @Override public synchronized List<Revision> load() { return revisions; }
    @Override public synchronized boolean saveIfVersion(int version, List<Revision> next) {
        if (version != revisions.size()) return false;
        if (next.size() != version + 1) throw new IllegalArgumentException("规则历史必须逐笔追加");
        revisions = List.copyOf(next);
        return true;
    }
}
