package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.player.PlayerSave;

/**
 * 职责：钉住"名单里查不到人时**不许把内部编号印给玩家**"这一条（收口清单 #323）。
 * 依赖：无（纯函数，不起 Spring 上下文）。
 *
 * <p>为什么值得一条用例：关注列表 / 小队成员 / 联盟成员三处的名字原先各写一遍
 * {@code save == null ? id : save.nickName()}，于是"这个人查不到"在界面上表现成一串
 * {@code P9179c…} —— 与 #255/#268/#320/#322 同族。收成 {@link PlayerDtoMapper#displayName} 之后
 * 判据只有一条且能失败：把实现改回 `return save == null ? playerId : …` 时第一条断言就红。
 */
class PlayerDtoMapperTest {

    /** 与真实 playerId 同形（`P` + 32 位十六进制），用来断言"显示名不是内部编号"。 */
    private static final String PLAYER_ID_SHAPE = "P[0-9a-f]{32}";

    @Test
    @DisplayName("查不到存档时给一句人话，且**不是** id 形态、也不是空串")
    void unknownPlayerGetsHumanCopyInsteadOfAnId() {
        assertThat(PlayerDtoMapper.displayName(null))
                .as("查不到存档时的显示名").isEqualTo(PlayerDtoMapper.UNKNOWN_PLAYER_NAME);
        assertThat(PlayerDtoMapper.UNKNOWN_PLAYER_NAME)
                .as("这句人话不能长得像内部编号").doesNotMatch(PLAYER_ID_SHAPE);
        assertThat(PlayerDtoMapper.UNKNOWN_PLAYER_NAME)
                .as("也不该是空的 —— 空名字在列表里就是一行没人").isNotBlank();
    }

    @Test
    @DisplayName("有存档就原样用昵称，不做任何加工")
    void knownPlayerKeepsTheirNickname() {
        assertThat(PlayerDtoMapper.displayName(newSave("卫无咎"))).isEqualTo("卫无咎");
    }

    /** 最小可用的存档：本用例只碰昵称那一位，所以走无参构造 + setter，不惊动资源/战力那些不变量。 */
    private static PlayerSave newSave(String nickname) {
        PlayerSave save = new PlayerSave();
        save.setNickName(nickname);
        return save;
    }
}
