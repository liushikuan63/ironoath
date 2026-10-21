package com.ironoath.web.notice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NoticeCfg;
import com.ironoath.web.release.ReleaseRulesAssembler;

/** V26-2：版本是当前可见内容的指纹，不是人工表版本或可见条数。 */
class NoticeVisibleVersionTest {
    @Test
    @DisplayName("同版本改文案、窗口生效与过期均改变可见版本；可见内容不变则版本稳定")
    void versionFollowsVisibleRowsAndServerTime() {
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        NoticeService notices = new NoticeService(configs, new TimeService(() -> 0L),
                new ReleaseRulesAssembler(configs));
        configs.reload("notice", NoticeCfg.class, fixture("第一版"));

        var before = notices.listAt(999L);
        var active = notices.listAt(1_000L);
        var stillActive = notices.listAt(1_999L);
        var next = notices.listAt(2_000L);
        var after = notices.listAt(3_000L);
        assertThat(before.notices()).isEmpty();
        assertThat(active.notices()).extracting(item -> item.id()).containsExactly("first");
        assertThat(active.version()).matches("[0-9a-f]{16}");
        assertThat(active.version()).isNotEqualTo(before.version());
        assertThat(stillActive.version()).isEqualTo(active.version());
        assertThat(next.notices()).extracting(item -> item.id()).containsExactly("second");
        assertThat(next.version()).isNotEqualTo(active.version());
        assertThat(after.notices()).isEmpty();
        assertThat(after.version()).isEqualTo(before.version());

        // 人工表版本仍是 2，且当前生效条数仍是 1；仅文案改变也必须让面板重绘。
        configs.reload("notice", NoticeCfg.class, fixture("第二版"));
        assertThat(configs.rawTable("notice").version()).isEqualTo(2);
        assertThat(notices.listAt(1_000L).notices()).hasSize(1);
        assertThat(notices.listAt(1_000L).version()).isNotEqualTo(active.version());
    }

    private static String fixture(String body) {
        return """
                {"table":"notice","version":2,"comment":"可见版本夹具",
                 "fieldTypes":{"id":"STRING_KEY","title":"STRING","body":"STRING",
                               "startAt":"LONG_NONNEG","endAt":"LONG_NONNEG",
                               "priority":"LONG_NONNEG","why":"STRING"},
                 "rows":[
                   {"id":"first","title":"第一条","body":"%s","startAt":1000,"endAt":2000,"priority":1,"why":"夹具"},
                   {"id":"second","title":"第二条","body":"不变","startAt":2000,"endAt":3000,"priority":1,"why":"夹具"}
                 ]}
                """.formatted(body);
    }
}
