package com.dwinovo.numen.nav;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.build.Built;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * 走路的规格:模型的 {@code numen.move.to}、{@code away}、{@code follow} 和动手的活替自己走过去都用这一份,只此一处。
 *
 * <p>默认可挖可搭,只看价钱——能走就不挖,绕太远才挖;垫路用出厂的 {@code numen:throwaway} 料({@link ThrowawayBlocks#factory})。
 * 能不能动哪一格不在这里判:权限层说禁止的格规划时是墙,要问的格加价、动手那一刻才问,主人不答应的格这一趟里当墙
 * ({@link Trip})。观察模式下权限层拒一切改动,她自然只走。
 *
 * <p>她盖好的房子不挖:{@link Built} 记着、此刻还立着的格(她照设计放下的),路线只许绕开、从上面走或站在旁边够。房子是要留下来的
 * 东西,不是挡路的地形:路上挖掉一格墙,下一轮又得补上,建造就在原地来回打转。
 */
public final class Walking {

    private Walking() {}

    /** 她此刻走路的规格。在主线程上调。 */
    public static RouteSpec spec(NumenPlayer her) {
        RouteSpec.Builder spec = RouteSpec.defaults().edit().changes(true);
        LongSet built = Built.of(her.getServer()).standingIn(her.serverLevel());
        if (!built.isEmpty()) {
            spec.positions(PositionCosts.builder().forbid(PositionCosts.Use.DIG, built::contains).build());
        }
        return spec.build();
    }
}
