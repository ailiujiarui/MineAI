package com.dwinovo.numen.adapter;

import com.dwinovo.numen.agent.adapter.AdapterSpec;
import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把数据适配器的装备路由接进现有穿戴系统({@link GearSource})。
 *
 * <p>它自己不认识任何模组:适配文件说"这类物品归 {@code curios} 管",它就把问题转给登记在
 * {@link AdapterHandlers} 里那个名字的 {@link GearSource}。没有对应处理器时,这条规则等于
 * 没生效——本体照常,不影响原版装备。
 *
 * <p>所以它是一条"路由":数据改绑不用重编译,真正的槽位读写仍由处理器(代码)完成。
 */
public final class AdapterGearSource implements GearSource {

    @Override
    public List<GearSlot> slots(NumenPlayer body) {
        List<GearSlot> out = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (AdapterSpec spec : AdapterManager.registry().active()) {
            for (AdapterSpec.EquipRoute route : spec.equipRoutes()) {
                GearSource handler = AdapterHandlers.gear(route.container());
                if (handler != null && visited.add(route.container())) {
                    out.addAll(handler.slots(body));
                }
            }
        }
        return out;
    }

    @Override
    public Set<String> kindsOf(NumenPlayer body, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Set.of();
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Set<String> kinds = new LinkedHashSet<>();
        for (AdapterSpec spec : AdapterManager.registry().active()) {
            for (AdapterSpec.EquipRoute route : spec.equipRoutes()) {
                if (route.item().matches(itemId)) {
                    GearSource handler = AdapterHandlers.gear(route.container());
                    if (handler != null) {
                        kinds.addAll(handler.kindsOf(body, stack));
                    }
                }
            }
        }
        return kinds;
    }
}
