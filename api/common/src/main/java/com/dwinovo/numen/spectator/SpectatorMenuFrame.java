package com.dwinovo.numen.spectator;

import com.dwinovo.numen.mixin.MenuDataSlotsAccessor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** A value copy, taken before the body can consume or return any menu items. */
public record SpectatorMenuFrame(List<ItemStack> slots, ItemStack carried, List<Integer> data, int selectedHotbar) {
    public SpectatorMenuFrame {
        slots = slots.stream().map(ItemStack::copy).toList();
        carried = carried.copy();
        data = List.copyOf(data);
    }

    public SpectatorMenuFrame(List<ItemStack> slots, ItemStack carried, List<Integer> data) {
        this(slots, carried, data, -1);
    }

    public static SpectatorMenuFrame capture(AbstractContainerMenu menu) {
        int selected = menu.slots.stream().map(slot -> slot.container)
                .filter(Inventory.class::isInstance).map(Inventory.class::cast)
                .mapToInt(inventory -> inventory.selected).findFirst().orElse(-1);
        return new SpectatorMenuFrame(menu.slots.stream().map(slot -> slot.getItem()).toList(),
                menu.getCarried(), ((MenuDataSlotsAccessor) menu).numen$dataSlots().stream()
                .map(slot -> slot.get()).toList(), selected);
    }

    public boolean sameContents(SpectatorMenuFrame other) {
        if (other == null || slots.size() != other.slots.size() || selectedHotbar != other.selectedHotbar
                || !data.equals(other.data)
                || !ItemStack.matches(carried, other.carried)) return false;
        for (int i = 0; i < slots.size(); i++) {
            if (!ItemStack.matches(slots.get(i), other.slots.get(i))) return false;
        }
        return true;
    }
}
