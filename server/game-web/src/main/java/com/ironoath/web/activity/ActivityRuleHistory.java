package com.ironoath.web.activity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ActivityCfg;
import com.ironoath.core.activity.ActivityWindow;
import com.ironoath.web.activity.ActivityRuleHistoryStore.Revision;

/** 配置在提交前先持久化修订；每个已开放窗口直到原截止才切新定义。 */
@Component
public class ActivityRuleHistory {
    private final ActivityRuleHistoryStore store;
    private final TimeService time;
    private final ConfigRegistry configs;
    public ActivityRuleHistory(ActivityRuleHistoryStore store, ConfigRegistry configs, TimeService time) {
        this.store = store;
        this.time = time;
        this.configs = configs;
        // 首次升级先写已核实的v2历史，而不是用未来表覆盖没有打开过页面的旧玩家。
        if (store.load().isEmpty()) {
            try (var input = ActivityRuleHistory.class.getResourceAsStream("/migrations/activity-rules-v2.json")) {
                if (input == null) throw new IllegalStateException("活动旧规则迁移快照缺失，拒绝猜测旧权益");
                capture(List.of(com.ironoath.common.json.JsonUtils.mapper().readValue(input, ActivityCfg[].class)));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("活动旧规则迁移快照不可读", e);
            }
        }
        capture(configs.all(ActivityCfg.class));
        configs.addReloadGuard("activity", candidate -> capture(candidate.all(ActivityCfg.class)));
    }
    private synchronized void capture(List<ActivityCfg> rows) {
        // 多实例竞争只做有界CAS重读；失败拒绝热更，不能用新表猜旧条件。
        for (int attempt=0; attempt<3; attempt++) {
            List<Revision> history = store.load();
            if (!history.isEmpty() && history.get(history.size()-1).rows().equals(rows)) return;
            long at = history.isEmpty() ? 1L : time.serverNow();
            if (!history.isEmpty() && at < history.get(history.size()-1).activatedAt()) {
                throw new IllegalStateException("活动修订时钟倒退，拒绝改写规则历史");
            }
            List<Revision> next = new ArrayList<>(history);
            next.add(new Revision(at, rows));
            if (store.saveIfVersion(history.size(), next)) return;
        }
        throw new IllegalStateException("活动规则历史并发更新，未保存快照，拒绝热更");
    }
    /** 每次玩家操作只读取一次规则历史，热更提交锁内取一致快照。 */
    public Catalog snapshot() { synchronized (configs) { return new Catalog(store.load()); } }
    public List<String> ids() { return snapshot().ids(); }
    public ActivityInstance resolve(String id, long now, long serverAnchor, long playerAnchor) {
        return snapshot().resolve(id, now, serverAnchor, playerAnchor);
    }
    public record Catalog(List<Revision> revisions) {
        public Catalog { revisions=List.copyOf(revisions); }
        public List<String> ids() {
            var ids = new LinkedHashSet<String>();
            for (Revision revision : revisions) for (ActivityCfg row : revision.rows()) ids.add(row.id());
            return List.copyOf(ids);
        }
        private record Segment(long startAt, ActivityCfg row) { }
        /** 从全服历史重建个人锚/全服锚的实例，零进度和没有进度文档也走这里。 */
        public ActivityInstance resolve(String id, long now, long serverAnchor, long playerAnchor) {
            List<Segment> segments = new ArrayList<>();
            ActivityCfg last = null;
            for (Revision revision : revisions) {
                ActivityCfg next = revision.rows().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
                if (java.util.Objects.equals(last, next)) continue;
                long cutover = revision.activatedAt();
                // 前一次改表尚未生效时，可以改未来规则；已开放窗口仍保留原承诺。
                segments.removeIf(s -> s.startAt() >= cutover);
                Segment previous = segments.isEmpty() ? null : segments.get(segments.size()-1);
                long start = cutover;
                if (previous != null && previous.row() != null) {
                    ActivityWindow.Span old = ActivityWindow.current(previous.startAt(), previous.row().durationDays(), cutover-1);
                    if (old.endAt() == null) { last=next; continue; }
                    start = old.endAt();
                }
                if (next != null) {
                    long anchor = next.activityType() == ActivityCfg.ActivityType.LOGIN_STREAK && playerAnchor > 0
                            ? playerAnchor : serverAnchor;
                    start = Math.max(start, anchor);
                }
                segments.add(new Segment(start, next));
                last = next;
            }
            Segment selected = null;
            for (Segment segment : segments) if (segment.startAt() <= now) selected = segment;
            // 未来首登/开服锚还没开始：仍提供窗口，但核心拒绝累计及领取。
            if (selected == null && !segments.isEmpty()) selected=segments.get(0);
            if (selected == null || selected.row() == null) return null;
            ActivityWindow.Span span = ActivityWindow.current(selected.startAt(), selected.row().durationDays(), Math.max(1,now));
            return ActivityRulesAssembler.instance(selected.row(), span);
        }
    }
}
