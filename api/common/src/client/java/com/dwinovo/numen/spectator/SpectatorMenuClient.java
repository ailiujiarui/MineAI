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
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/** Native menu renderers consume a detached replica; no LocalPlayer inventory or menu pointer is replaced. */
public final class SpectatorMenuClient {
    private static final MenuDisplayTimeline TIMELINE = new MenuDisplayTimeline();
    private static long sessionId = -1;
    private static UUID targetId;
    private static SpectatorMenuPayload pending;
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
        if (!TIMELINE.accept(payload.menuId(), payload.revision(), payload.event() == SpectatorMenuPayload.OPEN,
                payload.event() == SpectatorMenuPayload.CLOSE || payload.event() == SpectatorMenuPayload.DISMISS)) return;
        if (payload.event() == SpectatorMenuPayload.DISMISS) {
            dismiss(false);
            return;
        }
        pending = payload;
        applyPending();
    }

    public static void tick() {
        applyPending();
        if (TIMELINE.expired(Util.getMillis())) dismiss(false);
    }

    private static void applyPending() {
        if (pending == null || SpectatorClient.target() == null) return;
        SpectatorMenuPayload payload = pending;
        if (screen == null || screen.menuId != payload.menuId()) {
            if (viewingScreen()) Minecraft.getInstance().setScreen(null);
            screen = null;
            menu = null;
            Inventory inventory = new Inventory(SpectatorClient.target());
            Screen renderer;
            if (payload.inventory()) {
                menu = new InventoryMenu(inventory, false, SpectatorClient.target());
                renderer = new WatchedInventoryScreen((InventoryMenu) menu, inventory, payload.title());
            } else {
                MenuType<?> type = BuiltInRegistries.MENU.get(payload.menuType());
                Object constructor = type == null ? null : MenuScreensAccessor.numen$constructors().get(type);
                if (constructor == null || !payload.menuType().getNamespace().equals("minecraft")) {
                    pending = null;
                    return;
                }
                menu = type.create(-1, inventory);
                renderer = ((MenuScreenConstructorInvoker) constructor).numen$create(menu, inventory, payload.title());
            }
            if (menu.slots.size() != payload.frame().slots().size()) {
                menu = null;
                pending = null;
                return;
            }
            screen = new ReadOnlyScreen(renderer, payload.menuId());
            Minecraft.getInstance().setScreen(screen);
        }
        for (int i = 0; i < menu.slots.size(); i++) menu.slots.get(i).set(payload.frame().slots().get(i).copy());
        menu.setCarried(payload.frame().carried().copy());
        for (int i = 0; i < payload.frame().data().size(); i++) menu.setData(i, payload.frame().data().get(i));
        pending = null;
        TIMELINE.applied(Util.getMillis());
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

        WatchedInventoryScreen(InventoryMenu menu, Inventory inventory, Component title) {
            super(menu, inventory, title);
            titleLabelX = 97;
        }

        @Override
        protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.drawString(font, Component.translatable("container.crafting"), titleLabelX, titleLabelY, 4210752, false);
        }

        @Override
        protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
            graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
            if (SpectatorClient.target() != null) InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics, leftPos + 26, topPos + 8, leftPos + 75, topPos + 78, 30,
                    0.0625f, mouseX, mouseY, SpectatorClient.target());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            renderTooltip(graphics, mouseX, mouseY);
        }
    }
}
