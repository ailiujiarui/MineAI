package com.dwinovo.numen.spectator;

import com.dwinovo.numen.client.spectator.SpectatorClient;
import com.dwinovo.numen.client.NumenKeys;
import com.dwinovo.numen.mixin.MenuScreenConstructorInvoker;
import com.dwinovo.numen.mixin.MenuScreensAccessor;
import com.dwinovo.numen.network.payload.SpectatorMenuPayload;
import com.dwinovo.numen.network.payload.SpectatorRequestPayload;
import com.dwinovo.numen.network.NumenNetwork;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/** Native menu renderers consume a detached replica; no LocalPlayer inventory or menu pointer is replaced. */
public final class SpectatorMenuClient {
    private static final MenuDisplayTimeline TIMELINE = new MenuDisplayTimeline();
    private static final MenuActionPlayback<SpectatorMenuFrame> ACTION = new MenuActionPlayback<>();
    private static long sessionId = -1;
    private static UUID targetId;
    private static SpectatorMenuPayload pending;
    private static SpectatorMenuPayload actionPayload;
    private static SpectatorMenuPayload deferred;
    private static SpectatorMenuFrame displayedFrame;
    private static AbstractContainerMenu menu;
    private static ReadOnlyScreen screen;
    private static boolean initializingRenderer;

    private SpectatorMenuClient() {}

    public static void onSessionChanged(long id, UUID target) {
        if (id == sessionId && target.equals(targetId)) return;
        reset();
        sessionId = id;
        targetId = target;
    }

    public static void handle(SpectatorMenuPayload payload) {
        if (payload.sessionId() != sessionId || !payload.target().equals(targetId)) return;
        boolean action = payload.event() == SpectatorMenuPayload.ACTION;
        if (!TIMELINE.accept(payload.menuId(), payload.revision(), payload.event() == SpectatorMenuPayload.OPEN || action,
                payload.event() == SpectatorMenuPayload.CLOSE || payload.event() == SpectatorMenuPayload.DISMISS
                        || (action && payload.closeAfterAction()))) return;
        if (payload.event() == SpectatorMenuPayload.DISMISS) {
            dismiss(false);
            return;
        }
        if (action) {
            clearAction();
            pending = null;
            actionPayload = payload;
            ACTION.replace(payload.actionFrames());
            applyAction(Util.getMillis());
            return;
        }
        if (ACTION.active() && actionPayload.menuId() == payload.menuId()
                && payload.event() != SpectatorMenuPayload.OPEN) {
            deferred = payload;
            return;
        }
        clearAction();
        pending = payload;
        applyPending();
    }

    public static void tick() {
        long now = Util.getMillis();
        applyAction(now);
        if (ACTION.finished(now)) {
            pending = deferred;
            clearAction();
        }
        applyPending();
        if (TIMELINE.expired(Util.getMillis())) {
            long completedMenuId = TIMELINE.menuId();
            dismiss(false);
            if (targetId != null) NumenNetwork.sendToServer(new SpectatorRequestPayload(targetId,
                    SpectatorRequestPayload.RESUME_MENU, sessionId, completedMenuId));
        }
    }

    private static void applyAction(long now) {
        if (!ACTION.active() || SpectatorClient.target() == null) return;
        SpectatorMenuFrame frame = ACTION.frameToApply(now);
        if (frame == null) return;
        if (!applyFrame(actionPayload, frame)) {
            clearAction();
            return;
        }
        long appliedAt = Util.getMillis();
        ACTION.applied(appliedAt);
        if (ACTION.lastFrame() && actionPayload.closeAfterAction()) TIMELINE.applied(appliedAt);
    }

    private static void applyPending() {
        if (pending == null || SpectatorClient.target() == null) return;
        SpectatorMenuPayload payload = pending;
        pending = null;
        if (applyFrame(payload, payload.frame())) TIMELINE.applied(Util.getMillis());
    }

    private static boolean applyFrame(SpectatorMenuPayload payload, SpectatorMenuFrame frame) {
        if (screen == null || screen.menuId != payload.menuId()) {
            if (viewingScreen()) Minecraft.getInstance().setScreen(null);
            screen = null;
            menu = null;
            displayedFrame = null;
            Inventory inventory = new Inventory(SpectatorClient.target());
            Screen renderer;
            if (payload.inventory()) {
                menu = new InventoryMenu(inventory, false, SpectatorClient.target());
                renderer = new WatchedInventoryScreen((InventoryMenu) menu, inventory, payload.title());
            } else {
                MenuType<?> type = BuiltInRegistries.MENU.get(payload.menuType());
                Object constructor = type == null ? null : MenuScreensAccessor.numen$constructors().get(type);
                if (constructor == null || !payload.menuType().getNamespace().equals("minecraft")) {
                    return false;
                }
                menu = type.create(-1, inventory);
                renderer = ((MenuScreenConstructorInvoker) constructor).numen$create(menu, inventory, payload.title());
            }
            if (menu.slots.size() != frame.slots().size()) {
                menu = null;
                return false;
            }
            screen = new ReadOnlyScreen(renderer, payload.menuId());
            Minecraft.getInstance().setScreen(screen);
        }
        if (menu.slots.size() != frame.slots().size()) return false;
        for (int i = 0; i < menu.slots.size(); i++) menu.slots.get(i).set(frame.slots().get(i).copy());
        menu.setCarried(frame.carried().copy());
        for (int i = 0; i < frame.data().size(); i++) menu.setData(i, frame.data().get(i));
        if (screen.renderer instanceof WatchedInventoryScreen inventoryScreen) {
            inventoryScreen.showChanges(displayedFrame, frame);
        }
        displayedFrame = frame;
        return true;
    }

    private static void clearAction() {
        ACTION.clear();
        actionPayload = null;
        deferred = null;
    }

    public static boolean viewingScreen() { return Minecraft.getInstance().screen == screen && screen != null; }
    public static boolean initializingRenderer() { return initializingRenderer; }

    private static void dismiss(boolean requested) {
        if (requested && targetId != null) {
            NumenNetwork.sendToServer(new SpectatorRequestPayload(targetId, SpectatorRequestPayload.CLOSE_INVENTORY,
                    sessionId, TIMELINE.menuId()));
        }
        if (viewingScreen()) Minecraft.getInstance().setScreen(null);
        screen = null;
        menu = null;
        pending = null;
        displayedFrame = null;
        clearAction();
        TIMELINE.dismiss();
    }

    public static void reset() {
        dismiss(false);
        TIMELINE.reset();
        sessionId = -1;
        targetId = null;
    }

    /** A tracking gap clears display state while keeping the current session's ordering tombstone. */
    public static void suspend() { dismiss(false); }

    /** A wrapper also blocks native menu-specific buttons, rename fields, recipe books and drag state. */
    private static final class ReadOnlyScreen extends Screen {
        final Screen renderer;
        final long menuId;

        ReadOnlyScreen(Screen renderer, long menuId) {
            super(renderer.getTitle());
            this.renderer = renderer;
            this.menuId = menuId;
        }

        @Override
        protected void init() {
            initializingRenderer = true;
            try {
                renderer.init(minecraft, width, height);
            } finally {
                initializingRenderer = false;
            }
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderer.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean isPauseScreen() { return false; }

        @Override
        public boolean mouseClicked(double x, double y, int button) { return true; }
        @Override
        public boolean mouseReleased(double x, double y, int button) { return true; }
        @Override
        public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return true; }
        @Override
        public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return true; }
        @Override
        public boolean charTyped(char character, int modifiers) { return true; }
        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (NumenKeys.WATCH_COMPANION.matches(key, scanCode)) {
                SpectatorClient.exit();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE || minecraft.options.keyInventory.matches(key, scanCode)) onClose();
            return true;
        }
        @Override
        public boolean keyReleased(int key, int scanCode, int modifiers) { return true; }
        @Override
        public void onClose() { dismiss(true); }
        @Override
        public void removed() { /* The replica is never removed against the owner's player. */ }
    }

    /** Vanilla survival inventory layout, including armor, offhand, 2x2 and the result slot. */
    private static final class WatchedInventoryScreen extends AbstractContainerScreen<InventoryMenu> {
        private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");
        private boolean[] changedSlots = new boolean[0];
        private int selectedHotbar = -1;
        private long highlightUntil;

        WatchedInventoryScreen(InventoryMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
            titleLabelX = 97;
        }

        void showChanges(SpectatorMenuFrame previous, SpectatorMenuFrame current) {
            selectedHotbar = current.selectedHotbar();
            changedSlots = new boolean[current.slots().size()];
            if (previous == null || previous.slots().size() != current.slots().size()) return;
            boolean changed = false;
            for (int i = 0; i < changedSlots.length; i++) {
                changedSlots[i] = !ItemStack.matches(previous.slots().get(i), current.slots().get(i));
                changed |= changedSlots[i];
            }
            if (changed) highlightUntil = Util.getMillis() + 1000;
        }

        @Override
        protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.drawString(font, Component.translatable("container.crafting"), titleLabelX, titleLabelY, 4210752, false);
        }

        @Override
        protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
            graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
            if (Util.getMillis() < highlightUntil) {
                for (int i = 0; i < changedSlots.length; i++) {
                    if (changedSlots[i]) drawSlotBorder(graphics, menu.slots.get(i), 0xFF42D9D2);
                }
            }
            if (selectedHotbar >= 0 && selectedHotbar < 9) {
                drawSlotBorder(graphics, menu.slots.get(36 + selectedHotbar), 0xFFFFD45A);
            }
            if (SpectatorClient.target() != null) InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics, leftPos + 26, topPos + 8, leftPos + 75, topPos + 78, 30,
                    0.0625f, mouseX, mouseY, SpectatorClient.target());
        }

        private void drawSlotBorder(GuiGraphics graphics, Slot slot, int color) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y, color);
            graphics.fill(x - 1, y + 16, x + 17, y + 17, color);
            graphics.fill(x - 1, y, x, y + 16, color);
            graphics.fill(x + 16, y, x + 17, y + 16, color);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            renderTooltip(graphics, mouseX, mouseY);
        }
    }
}
