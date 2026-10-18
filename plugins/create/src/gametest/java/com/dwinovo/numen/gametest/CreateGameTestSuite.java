package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuite;
import java.util.List;

/** 机械动力联动的 GameTest 套件:命名空间 {@code numen_create},只在挂着机械动力的那一次跑批里。 */
public final class CreateGameTestSuite implements GameTestSuite {

    @Override
    public List<Class<?>> classes() {
        return List.of(CreateGameTests.class);
    }
}
