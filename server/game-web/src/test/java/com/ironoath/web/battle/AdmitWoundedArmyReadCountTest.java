package com.ironoath.web.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;

/**
 * 职责：一次战斗结算里，<b>同一个玩家的军队档被读了几遍</b>。
 *
 * <p><b>缺陷形状</b>：{@code PlayerCityBattleService.admitWounded} 原来连着读两次同一份军队档 ——
 * 第一次取内容、第二次只为判「这份档在不在」，而「在不在」第一次就读到了。
 * 攻守双方各走一次这条路径 ⇒ <b>一场战斗要多读两次同一份军队档</b>（每次都是一次 Mongo 往返）。
 * 结果完全正确，所以任何结果断言都抓不到它，只有读次数能。
 *
 * <p><b>为什么合并不改语义</b>（这条是本判据的前提，不能只测「少读了几次」）：
 * 原写法里第二次读与第一次读之间，别人可能刚好建好了这份档 ⇒ 原写法看到「已存在」就不建；
 * 新写法会调 {@code insertIfAbsent}，而它是<b>原子插入：已存在则返回 false、不覆盖</b> ⇒ 结果相同。
 * 所以判据同时钉住两件事：读次数从 2 降到 1，且「档已存在时绝不调 insertIfAbsent」。
 *
 * <p>依赖：Mockito（{@code @InjectMocks} 构造那个 25 参数的 service，只有 {@code ArmyRepository} 是真 mock）。
 */
@ExtendWith(MockitoExtension.class)
class AdmitWoundedArmyReadCountTest {

    @Mock private ArmyRepository armies;
    @InjectMocks private PlayerCityBattleService service;

    @Test
    @DisplayName("档已存在：只读一次，且不碰 insertIfAbsent")
    void existingArmyIsReadOnce() {
        ArmyState existing = mock(ArmyState.class);
        when(armies.findByPlayerId("defender-1")).thenReturn(Optional.of(existing));

        ArmyState loaded = service.loadOrInsertArmy("defender-1");

        assertThat(loaded).as("已存在的档原样返回").isSameAs(existing);
        verify(armies, times(1)).findByPlayerId("defender-1");
        verify(armies, never()).insertIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("档不存在：仍然只读一次，并原子建一份空的")
    void missingArmyIsReadOnceAndInserted() {
        when(armies.findByPlayerId("defender-2")).thenReturn(Optional.empty());

        ArmyState loaded = service.loadOrInsertArmy("defender-2");

        assertThat(loaded).as("没有档时给一份空的").isNotNull();
        verify(armies, times(1)).findByPlayerId("defender-2");
        verify(armies, times(1)).insertIfAbsent(org.mockito.ArgumentMatchers.eq("defender-2"),
                org.mockito.ArgumentMatchers.any());
    }
}
