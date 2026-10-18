package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** 森罗物语联动的 GameTest 套件:命名空间 {@code numen_kaleidoscope},只在挂着森罗的那一次跑批里。 */
public final class KaleidoscopeGameTestSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(KaleidoscopeHandGameTests.class);
    }
}
