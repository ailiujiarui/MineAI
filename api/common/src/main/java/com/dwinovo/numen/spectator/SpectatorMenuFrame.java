package com.dwinovo.numen.spectator;

import com.dwinovo.numen.mixin.MenuDataSlotsAccessor;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** A value copy, taken before the body can consume or return any menu items. */
public record SpectatorMenuFrame(List<ItemStack> slots, ItemStack carried, List<Integer> data) {
    public SpectatorMenuFrame {
        slots = slots.stream().map(ItemStack::copy).toList();
        carried = carried.copy();
        data = List.copyOf(data);
    }

    public static SpectatorMenuFrame capture(AbstractContainerMenu menu) {
        return new SpectatorMenuFrame(menu.slots.stream().map(slot -> slot.getItem()).toList(),
                menu.getCarried(), ((MenuDataSlotsAccessor) menu).numen$dataSlots().stream()
                .map(slot -> slot.get()).toList());
    }

    public boolean sameContents(SpectatorMenuFrame other) {
        if (other == null || slots.size() != other.slots.size() || !data.equals(other.data)
                || !ItemStack.matches(carried, other.carried)) return false;
        for (int i = 0; i < slots.size(); i++) {
            if (!ItemStack.matches(slots.get(i), other.slots.get(i))) return false;
        }
        return true;
    }
}
