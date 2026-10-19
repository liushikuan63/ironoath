package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ironoath.common.time.TimeService;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.reward.RewardNames;

/**
 * 职责：一次「活动可领数」查询里，<b>玩家的整份存档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：活动红点（{@code activity/claimable} 叶子）在同一次请求里既让活动域读一次
 * 收据、也让签到域再读一次**同一份存档的同一个字段**（{@code goldClaims}），而两次之间没有玩家存档写入
 * ⇒ 一次请求多搬回一整份存档（资源表、PVP 账本、科技、礼包弹窗账本、头像框集合全在里面）。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>为什么能省</b>：{@code ActivityAppService.load} 先 {@code recoverGoldClaims}（可能补发并落库）
 * <b>再</b>读玩家档 ⇒ 它读到的那份是<b>补发之后</b>的；把它交给签到域，两处看到的就是同一份收据。
 * ⚠ 反过来（先读再 load）会把**过期**收据喂给签到域 ⇒ 活动红点可能少亮 —— 判据里钉住了顺序。
 *
 * <p>依赖：Mockito（{@code @InjectMocks} 构造那个 12 参数的 service）。
 */
@ExtendWith(MockitoExtension.class)
class ActivityClaimableReadCountTest {

    @Mock private PlayerRepository players;
    @Mock private PlayerLock playerLock;
    @Mock private ActivityLedger ledger;
    @Mock private ActivityAnchors anchors;
    @Mock private SignInRewards signIn;
    @Mock private RewardService rewardService;
    @Mock private TimeService timeService;
    @Mock private ConfigRegistry configs;
    @Mock private IdempotencyStore idempotency;
    @Mock private RewardNames names;
    @Mock private com.ironoath.web.battlepass.BattlePassService battlePass;
    @InjectMocks private ActivityAppService service;

    @Test
    @DisplayName("一次可领数查询：玩家档案只读一遍（活动域与签到域共用同一份收据）")
    void claimableCountReadsThePlayerDocumentOnce() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get())
                .when(playerLock).runLocked(anyString(), anyLong(), any());
        ActivityLedger.Book book = org.mockito.Mockito.mock(ActivityLedger.Book.class);
        ActivityProgress progress = org.mockito.Mockito.mock(ActivityProgress.class);
        when(book.progress()).thenReturn(progress);
        when(ledger.load(anyString(), anyLong(), any())).thenReturn(book);
        PlayerSave save = org.mockito.Mockito.mock(PlayerSave.class);
        org.mockito.Mockito.lenient().when(save.playerId()).thenReturn("p1");
        when(players.findByPlayerId("p1")).thenReturn(Optional.of(save));
        when(signIn.list(anyString(), anyLong(), any())).thenReturn(List.of());

        service.claimableCount("p1");

        verify(players, times(1)).findByPlayerId("p1");
        verify(signIn, times(1)).list(anyString(), anyLong(), any());
    }
}
