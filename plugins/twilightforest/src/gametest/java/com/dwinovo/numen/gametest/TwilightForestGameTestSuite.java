package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** 暮色森林联动的 GameTest 套件:命名空间 {@code numen_twilightforest},只在挂着暮色森林的那一次跑批里。 */
public final class TwilightForestGameTestSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(TwilightForestGameTests.class);
    }
}
