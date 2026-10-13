package com.ironoath.web.activity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.activity.ActivityWindow;

/** 读、事件与领取共用的实例恢复；保旧开放承诺，到期后才选择新规则。 */
@Component
public class ActivityLedger {
    public enum Mode { READ, EVENT, CLAIM }
    public record Book(ActivityProgress progress, List<ActivityInstance> instances, List<ActivityClaimPlan> pendingClaims) {
        public Book(ActivityProgress progress, List<ActivityInstance> instances) { this(progress, instances, List.of()); }
        public ActivityInstance instance(String id) {
            return instances.stream().filter(i -> i.def().activityId().equals(id)).findFirst().orElseThrow();
        }
    }
    private final ActivityProgressStore store;
    private final ActivityRuleHistory history;
    private final ActivityAnchors anchors;
    public ActivityLedger(ActivityProgressStore store, ActivityRuleHistory history, ActivityAnchors anchors) {
        this.store=store; this.history=history; this.anchors=anchors;
    }
    public Book load(String playerId, long now, Mode mode) {
        var stored=store.load(playerId).orElse(null);
        long serverAnchor=stored == null ? anchors.serverOpenMs(playerId) : stored.serverOpenMs();
        long playerAnchor=anchors.playerAnchorMs(playerId);
        var entries=new LinkedHashMap<String, ActivityProgress.Entry>();
        var old=new LinkedHashMap<String, ActivityInstance>();
        if (stored != null) {
            stored.entries().forEach(e -> entries.put(e.activityId(), e));
            stored.instances().forEach(i -> old.put(i.def().activityId(), i));
        }
        var rules=history.snapshot();
        var ids=new java.util.LinkedHashSet<>(rules.ids());
        ids.addAll(old.keySet());
        List<ActivityInstance> instances=new ArrayList<>();
        List<ActivityProgress.Entry> restored=new ArrayList<>();
        var windows=new LinkedHashMap<String, ActivityWindow.Span>();
        for (String id : ids) {
            var entry=entries.get(id);
            ActivityInstance instance=old.get(id);
            if (instance != null && entry != null && instance.startAt()!=entry.windowStart()) {
                throw new IllegalStateException("活动窗口与快照不一致，拒绝猜测旧权益："+id);
            }
            // 旧Mongo缺快照：从持久规则历史恢复，而非当前表；原起点及进度逐字段保留。
            if (instance == null && entry != null) {
                var prior=rules.resolve(id, entry.windowStart(), serverAnchor, playerAnchor);
                if (prior != null) instance=new ActivityInstance(prior.def(), prior.name(), prior.rewards(),
                        prior.battlePassPoints(), entry.windowStart(), ActivityWindow.current(entry.windowStart(),
                        prior.def().durationDays(), entry.windowStart()).endAt());
            }
            boolean expired=instance != null && instance.span().expired(now);
            boolean pending=entry != null && !entry.claimed() && entry.value()>0;
            boolean deliveryPending=stored!=null && stored.pendingClaims().stream().anyMatch(p -> p.instance().def().activityId().equals(id));
            boolean keep=instance != null && instance.openedAt()!=null && (deliveryPending || !expired || mode==Mode.CLAIM || mode==Mode.READ && pending
                    || instance.def().retired());
            if (!keep) {
                var current=rules.resolve(id, now, serverAnchor, playerAnchor);
                if (current != null) instance=current;
                else if (instance!=null && instance.openedAt()==null) instance=null;
                // 已移除的旧实例保留原存档，固定窗口保证不能继续累积或重领。
            }
            if (instance == null) continue;
            if (entry == null || entry.windowStart()!=instance.startAt()) {
                entry=new ActivityProgress.Entry(id,instance.startAt(),0,false,0);
            }
            instances.add(instance); restored.add(entry); windows.put(id,instance.span());
        }
        var progress=ActivityProgress.restoreInstances(playerId,instances.stream().map(ActivityInstance::def).toList(),
                restored,serverAnchor,windows);
        Book book=new Book(progress,List.copyOf(instances),stored==null?List.of():stored.pendingClaims());
        save(book);
        return book;
    }
    public void save(Book book) {
        var p=book.progress();
        store.save(p.playerId(),new ActivityProgressStore.State(p.playerId(),p.entries(),p.serverOpenMs(),book.instances(),book.pendingClaims()));
    }
}
