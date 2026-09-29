package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.adapter.SlotRoles;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI tool implementations — the business half of {@code use gui} and
 * {@code use close} ({@code UseCommands}): read the open container menu and close it.
 */
public final class GuiOps {

    /**
     * 打开的界面:抬头是界面名与合成格的图,每个槽一条(界面这一侧的全列,空的也列;她自己那一侧只列有东西的),结尾是光标、
     * 机器的数值与提示。槽多的模组界面按输出预算分页({@link Listing})。
     *
     * @param again 这一行本身(不带 {@code --page}):翻页时写它
     */
    public String inspectGui(NumenPlayer self, CommandArgs args, String again) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null) {
            return TaskResult.fail("no GUI open.").toJson();
        }
        // 数据适配器:专用菜单的数据不在原版槽位数组里(BD 的服务端槽位是 -1、数据在客户端渲染)。
        // 按菜单 id 匹配适配文件里的 gui 规则,有处理器就交给它读,别走会读空的通用转储。
        String adapterMenu = menuId(menu);
        if (adapterMenu != null) {
            var adapterRoute = com.dwinovo.numen.adapter.AdapterManager.registry().gui(adapterMenu);
            if (adapterRoute.isPresent()) {
                var handler = com.dwinovo.numen.api.adapter.AdapterHandlers.gui(adapterRoute.get().source());
                if (handler != null) {
                    String read = handler.read(self, menu, adapterRoute.get().source());
                    if (read != null) {
                        return read;   // 处理器拥有整份回执(完整工具结果 JSON)
                    }
                }
            }
        }
        // With no block menu open, containerMenu IS your own InventoryMenu — which carries the 2x2
        // crafting grid. Surface it so the model can craft small recipes without a table.
        boolean ownInventory = menu == self.inventoryMenu;
        List<String> container = new ArrayList<>();
        List<String> mine = new ArrayList<>();
        // Crafting grid (if any). Detect generically: a slot backed by a CraftingContainer IS a grid
        // cell (vanilla 2x2/3x3 AND modded NxM), the ResultSlot IS the output. We lay the cells out in
        // 2D with their click-able slot numbers so the model can drop the recipe ascii straight onto it
        // — no "row-major + stride + gaps" arithmetic, which is exactly where it kept misplacing.
        int gridW = 0, gridH = 0, resultIndex = -1;
        Slot[] gridCells = null;   // indexed by position-in-container (row-major)
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean playerSide = slot.container == self.getInventory();
            ItemStack it = slot.getItem();
            if (slot instanceof ResultSlot) {
                resultIndex = slot.index;
                continue;   // shown as part of the crafting-grid section, not the generic dump
            }
            if (slot.container instanceof CraftingContainer cc) {
                if (gridCells == null) {
                    gridW = cc.getWidth();
                    gridH = cc.getHeight();
                    gridCells = new Slot[gridW * gridH];
                }
                int pos = slot.getContainerSlot();
                if (pos >= 0 && pos < gridCells.length) {
                    gridCells[pos] = slot;
                }
                continue;
            }
            // Output-only = a non-empty machine slot that won't take its own item back (result slot).
            boolean output = !playerSide && !it.isEmpty() && !slot.mayPlace(it);
            // 机器槽的角色由通用分类器问出来(原版 ResultSlot/FurnaceFuelSlot、Mekanism 的 getSlotType()、
            // 其余按类名),这样任何模组的机器都带上 [input]/[output]/[energy]/… —— 没有专门 GUI 处理器的
            // 菜单也读得懂。玩家自己那一段全是普通 Slot,不打这个标。
            String role = playerSide ? "" : roleTag(slot, self);
            // 分类器没认出 output、但槽的表现就是个产出槽(非空且放不回自己的物品)时,补一个 [output]。
            String outputTag = output && !"[output]".equals(role) ? " [output]" : "";
            String line = "  " + i + ": " + describe(it) + role + outputTag + "\n";
            if (playerSide) {
                if (!it.isEmpty()) {
                    mine.add(line);   // only your filled slots — the items you can move in
                }
            } else {
                container.add(line);  // all container slots, empty included (placement targets)
            }
        }
        // Data slots = the menu's OTHER synced channel, parallel to the item slots: the ints a real
        // screen reads to draw progress / fuel / energy bars. Read them generically (no per-menu
        // special-casing) — meaning is GUI-specific, the model/skill interprets (e.g. a furnace's are
        // [litTime, litDuration, cookProgress, cookTotal], so cook% = cookProgress/cookTotal).
        String dataLine = "";
        List<DataSlot> data = ((com.dwinovo.numen.mixin.MenuDataSlotsAccessor) (Object) menu).numen$dataSlots();
        if (!data.isEmpty()) {
            StringBuilder d = new StringBuilder("data values (machine state — progress/fuel/energy/…, "
                    + "meaning is GUI-specific): [");
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) d.append(", ");
                d.append(data.get(i).get());
            }
            dataLine = d.append("]\n").toString();
        }

        // Render the crafting grid as a 2D map of click-able slot numbers, so the recipe ascii from
        // inv recipe overlays cell-for-cell (a smaller recipe goes in the TOP-LEFT — same as here).
        String gridSection = "";
        if (gridCells != null) {
            StringBuilder g = new StringBuilder("crafting grid " + gridW + "x" + gridH
                    + " — put each recipe ingredient into the slot at the SAME position (a recipe "
                    + "smaller than the grid goes in the top-left); take the result from slot "
                    + resultIndex + ":\n");
            for (int r = 0; r < gridH; r++) {
                g.append("  ");
                for (int c = 0; c < gridW; c++) {
                    Slot cell = gridCells[r * gridW + c];
                    ItemStack it = cell == null ? ItemStack.EMPTY : cell.getItem();
                    int idx = cell == null ? -1 : cell.index;
                    g.append("slot ").append(idx).append("=").append(describe(it));
                    if (c < gridW - 1) {
                        g.append("  |  ");
                    }
                }
                g.append("\n");
            }
            gridSection = g.toString();
        }

        String header = ownInventory
                ? "GUI: InventoryMenu (YOUR own inventory — includes the 2x2 crafting grid below)\n"
                : "GUI: " + menu.getClass().getSimpleName() + "\n";
        List<String> slots = new ArrayList<>(container.isEmpty() ? List.of("  (none)") : container);
        slots.add("your inventory (non-empty):");
        slots.addAll(mine.isEmpty() ? List.of("  (empty)") : mine);
        return new Listing(header + gridSection + "container slots:", slots,
                "cursor: " + describe(menu.getCarried()) + "\n"
                        + dataLine
                        + "tip: `use shift <slot>` sends a whole stack to the other section; `use transfer <from> <to>`"
                        + " (with --count N for part of it) puts it into a specific slot.", again).result(args).toJson();
    }

    private static String menuId(AbstractContainerMenu menu) {
        var type = menu.getType();
        var key = BuiltInRegistries.MENU.getKey(type);
        return key == null ? null : key.toString();
    }

    /** 通用槽位角色标注;玩家自己的槽或认不出的槽给空串。 */
    private static String roleTag(Slot slot, NumenPlayer self) {
        String role = SlotRoles.roleOf(slot, self.getInventory());
        return role == null || SlotRoles.PLAYER.equals(role) ? "" : " [" + role + "]";
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }

    public String closeGui(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null || menu == self.inventoryMenu) {
            // The InventoryMenu (your own 2x2 grid + inventory) is always open — nothing to close.
            // If you left items in the 2x2 crafting grid, shift them back out.
            return TaskResult.ok("no block GUI was open (your own inventory menu is always available).").toJson();
        }
        self.closeContainer();
        return TaskResult.ok("closed the GUI.").toJson();
    }
}
