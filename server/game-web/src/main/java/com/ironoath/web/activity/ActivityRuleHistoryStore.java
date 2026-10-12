package com.ironoath.web.activity;

import java.util.List;
import com.ironoath.config.cfg.ActivityCfg;

/** 全服规则历史不依赖玩家是否打开活动页；按版本比较后写入，避免多实例覆盖。 */
public interface ActivityRuleHistoryStore {
    record Revision(long activatedAt, List<ActivityCfg> rows) {
        public Revision {
            if (activatedAt <= 0) throw new IllegalArgumentException("活动修订时刻必须为正");
            rows = List.copyOf(rows);
        }
    }
    List<Revision> load();
    boolean saveIfVersion(int expectedVersion, List<Revision> revisions);
}
