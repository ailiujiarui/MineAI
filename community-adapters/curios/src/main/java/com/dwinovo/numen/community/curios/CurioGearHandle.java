package com.dwinovo.numen.community.curios;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.Optional;

/**
 * 饰品栏里的一格。判据和玩家在饰品界面里拖动是同一条路径:放入问 {@code isItemValid}
 * (槽的标签断言、{@code ICurio.canEquip}、{@code CurioCanEquipEvent}),取出问 {@code extractItem}
 * ({@code CurioCanUnequipEvent}、绑定诅咒、{@code ICurio.canUnequip})。
 */
final class CurioGearHandle implements GearSlot {

    private final NumenPlayer body;
    private final ICuriosItemHandler inv;
    private final ICurioStacksHandler handler;
    private final int index;

    CurioGearHandle(NumenPlayer body, ICuriosItemHandler inv, ICurioStacksHandler handler, int index) {
        this.body = body;
        this.inv = inv;
        this.handler = handler;
        this.index = index;
    }

    @Override
    public String name() {
        return CuriosGearHandler.PREFIX + handler.getIdentifier();
    }

    @Override
    public ItemStack worn() {
        return handler.getStacks().getStackInSlot(index);
    }

    @Override
    public Optional<String> refuseWear(ItemStack one) {
        if (!active()) {
            return Optional.of("the " + name() + " slot is inactive");
        }
        return handler.getStacks().isItemValid(index, one) ? Optional.empty()
                : Optional.of(id(one) + " is not accepted in " + name());
    }

    @Override
    public Optional<String> refuseRemove() {
        ItemStack worn = worn();
        if (worn.isEmpty() || !handler.getStacks().extractItem(index, worn.getCount(), true).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(id(worn) + " can't be taken off " + name()
                + " (curse of binding, or the accessory itself refuses)");
    }

    @Override
    public ItemStack swap(ItemStack in) {
        IDynamicStackHandler stacks = handler.getStacks();
        ItemStack old = stacks.getStackInSlot(index);
        stacks.setStackInSlot(index, in);
        if (!in.isEmpty() && active()) {
            SlotContext context = new SlotContext(handler.getIdentifier(), body, index, false,
                    handler.getRenders().get(index));
            CuriosApi.getCurio(in).ifPresent(curio -> curio.onEquipFromUse(context));
        }
        return old;
    }

    private boolean active() {
        return inv.isSlotActive(handler.getIdentifier(), index);
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
