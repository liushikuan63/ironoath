package com.ironoath.web.nation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.ironoath.core.nation.Nation;

/** Scope lifetime, absence and failure isolation without a server or global state. */
class NationOutputReadScopeTest {
    @Test void successfulAbsenceIsResolvedOnlyOnce(){
        var scope=new NationOutputReadScope("a");var reads=new AtomicInteger();
        assertThat(scope.resolve("a",()->{reads.incrementAndGet();return null;})).isNull();
        assertThat(scope.resolve("a",()->{throw new AssertionError("absence must be reused");})).isNull();
        assertThat(reads).hasValue(1);
    }
    @Test void successfulSnapshotIsReusedWithoutCallingAnotherLoader(){
        var scope=new NationOutputReadScope("a");Nation value=mock(Nation.class);
        assertThat(scope.resolve("a",()->value)).isSameAs(value);
        assertThat(scope.resolve("a",()->{throw new AssertionError("must reuse");})).isSameAs(value);
    }
    @Test void anExceptionNeverResolvesTheScope(){
        var scope=new NationOutputReadScope("a");var fault=new IllegalStateException("owned lookup failure");Nation value=mock(Nation.class);
        assertThatThrownBy(()->scope.resolve("a",()->{throw fault;})).isSameAs(fault);
        assertThat(scope.resolve("a",()->value)).isSameAs(value);
    }
    @Test void anotherPlayerCannotReadTheCachedValueOrRunTheirLoader(){
        var scope=new NationOutputReadScope("a");Nation value=mock(Nation.class);scope.resolve("a",()->value);
        assertThatThrownBy(()->scope.resolve("b",()->{throw new AssertionError("wrong player loader ran");}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("国家产出读取范围与玩家不一致");
        assertThat(scope.resolve("a",()->{throw new AssertionError("owner value lost");})).isSameAs(value);
    }
}
