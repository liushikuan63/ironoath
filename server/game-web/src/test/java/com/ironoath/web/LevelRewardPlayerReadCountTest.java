package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerBrief;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.levelreward.LevelRewardAppService;
import com.ironoath.web.levelreward.LevelRewardClaimStore;

/**
 * 职责：等级奖励这一条路径上，<b>主城等级是不是被整份搬了回来</b>。
 *
 * <p><b>缺陷形状</b>：等级奖励只要一个 {@code cityLevel}，却用 {@code findByPlayerId} 取整份存档 ——
 * 资源表、PVP 账本、科技、礼包弹窗账本、已拥有的头像框集合全被搬回来，而这一位一个都用不上。
 * 仓库里 {@code PlayerRepository} 的注释就点名了这个坑（「不要用 findByPlayerId：那为一位数搬回整份存档」）。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读范围能。
 *
 * <p><b>为什么窄读口是对的</b>：{@code findBriefs} 的投影含 {@code cityLevel}，
 * 它与整档的一致性由 {@code PlayerBriefEquivalenceTest} 钉住 ⇒ 取值口径不用在这里重新论证。
 *
 * <p>依赖：Mockito（构造那个 8 参数的 service，只有 PlayerRepository 是真 mock）。
 */
@ExtendWith(MockitoExtension.class)
class LevelRewardPlayerReadCountTest {

    @Mock private PlayerRepository players;
    @Mock private PlayerLock playerLock;
    @Mock private com.ironoath.common.time.TimeService timeService;
    @Mock private com.ironoath.config.ConfigRegistry configs;
    @Mock private com.ironoath.web.levelreward.LevelRewardClaimStore claims;
    @InjectMocks private LevelRewardAppService service;

    @BeforeEach
    void unlockDirectly() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get())
                .when(playerLock).runLocked(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("只要等级：走窄读口，绝不把整份存档搬回来")
    void claimableCountUsesTheNarrowRead() {
        when(claims.load(anyString())).thenReturn(Optional.empty());
        when(players.findBriefs(List.of("p1")))
                .thenReturn(Map.of("p1", new PlayerBrief("p1", "某人", 7, 0L, 100L)));

        int claimable = service.claimableCount("p1");

        assertThat(claimable).as("表里没行 ⇒ 可领数为 0（这里只关心读范围）").isZero();
        verify(players, times(1)).findBriefs(List.of("p1"));
        verify(players, never()).findByPlayerId(anyString());
    }

    @Test
    @DisplayName("玩家不存在：仍报同一个业务码与文案（窄读口拿不到 brief）")
    void missingPlayerStillReportsNotFound() {
        when(players.findBriefs(List.of("nobody"))).thenReturn(Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claimableCount("nobody"))
                .isInstanceOf(com.ironoath.common.BizException.class)
                .hasMessageContaining("玩家不存在");
        verify(players, never()).findByPlayerId(anyString());
    }
}
