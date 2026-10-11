package com.dwinovo.numen.spectator;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.mixin.MenuDataSlotsAccessor;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.SpectatorMenuPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The real menu stays attached only to the body; viewers receive detached value copies. */
public final class SpectatorMenuBridge {
    private static final ResourceLocation INVENTORY = ResourceLocation.withDefaultNamespace("inventory");
    private static final Map<UUID, View> VIEWS = new HashMap<>();

    static {
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(VIEWS::clear);
    }

    private SpectatorMenuBridge() {}

    private static final class Title {
        int containerId = -1;
        Component value = Component.translatable("container.inventory");
    }

    private static final class View {
        final ServerPlayer owner;
        final NumenPlayer target;
        final long sessionId;
        long menuId;
        long revision;
        AbstractContainerMenu menu;
        AbstractContainerMenu actualMenu;
        Component title;
        SpectatorMenuFrame latest;
        SpectatorMenuFrame recipe;
        boolean manualInventory;
        boolean closed;
        ActionCapture action;

        View(ServerPlayer owner, NumenPlayer target, long sessionId) {
            this.owner = owner;
            this.target = target;
            this.sessionId = sessionId;
            this.actualMenu = target.containerMenu;
        }
    }

    /** Nested body operations share one before frame and checkpoints of completed changes. */
    public static final class InventoryAction implements AutoCloseable {
        private final ActionCapture capture;
        private boolean closed;

        private InventoryAction(ActionCapture capture) {
            this.capture = capture;
            if (capture != null) capture.depth++;
        }

        public void step() {
            if (!closed && capture != null) capture.step();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (capture == null) return;
            capture.step();
            if (--capture.depth == 0) capture.finish();
        }
    }

    private static final class ActionCapture {
        final View view;
        final AbstractContainerMenu actualMenu;
        final AbstractContainerMenu displayedMenu;
        final AbstractContainerMenu menu;
        final long menuId;
        final boolean closed;
        final List<SpectatorMenuFrame> frames = new ArrayList<>();
        int depth;

        ActionCapture(View view, AbstractContainerMenu menu) {
            this.view = view;
            this.actualMenu = view.target.containerMenu;
            this.displayedMenu = view.menu;
            this.menu = menu;
            this.menuId = view.menuId;
            this.closed = view.closed;
            frames.add(SpectatorMenuFrame.capture(menu));
        }

        boolean current() {
            return view(view.target) == view && view.action == this
                    && view.target.containerMenu == actualMenu && view.menu == displayedMenu
                    && view.menuId == menuId && view.closed == closed;
        }

        void step() {
            if (!current()) return;
            SpectatorMenuFrame frame = SpectatorMenuFrame.capture(menu);
            if (frame.sameContents(frames.getLast())) return;
            if (frames.size() < SpectatorMenuPayload.MAX_ACTION_FRAMES) frames.add(frame);
            else frames.set(frames.size() - 1, frame);
        }

        void finish() {
            boolean current = current();
            if (view.action == this) view.action = null;
            if (!current || frames.size() < 2) return;
            boolean manual = menu == view.target.inventoryMenu && view.manualInventory
                    && view.menu == view.target.inventoryMenu && !view.closed;
            boolean automatic = menu == view.target.inventoryMenu && !manual;
            if (automatic || view.menu != menu || view.closed) {
                display(view, menu, menu == view.target.inventoryMenu
                        ? Component.translatable("container.inventory") : title(view.target), manual);
            }
            send(view, SpectatorMenuPayload.ACTION, frames.getLast(), frames, automatic);
            if (automatic) view.closed = true;
        }
    }

    /** Only real slot, cursor, data or selected-hotbar changes produce a viewing display. */
    public static InventoryAction inventoryAction(NumenPlayer target) {
        return action(target, target.inventoryMenu);
    }

    /** Container transfers retain the actual screen, including its shared slots and processing data. */
    public static InventoryAction menuAction(NumenPlayer target) {
        return action(target, target.containerMenu);
    }

    /** A real menu click checkpoints cursor and slot changes only inside the current action. */
    public static void captureActionStep(NumenPlayer target) {
        View view = view(target);
        if (view != null && view.action != null) view.action.step();
    }

    private static InventoryAction action(NumenPlayer target, AbstractContainerMenu menu) {
        View view = view(target);
        if (view == null) return new InventoryAction(null);
        if (view.action != null && view.action.current() && view.action.menu == menu) {
            return new InventoryAction(view.action);
        }
        if (!supported(view, menu)) return new InventoryAction(null);
        view.action = new ActionCapture(view, menu);
        return new InventoryAction(view.action);
    }

    public static void start(ServerPlayer owner, NumenPlayer target, long sessionId) {
        if (!target.isOwnedByPlayer(owner.getUUID())) return;
        View view = new View(owner, target, sessionId);
        VIEWS.put(owner.getUUID(), view);
        if (target.containerMenu != target.inventoryMenu) begin(view, target.containerMenu, title(target), false);
    }

    public static void stop(ServerPlayer owner) { VIEWS.remove(owner.getUUID()); }

    private static View view(NumenPlayer target) {
        if (target.getOwnerUuid() == null) return null;
        View view = VIEWS.get(target.getOwnerUuid());
        return view != null && view.target == target && target.isOwnedByPlayer(view.owner.getUUID()) ? view : null;
    }

    /** The packet carries the real provider's title even when it opens and closes within one tick. */
    public static void onOutbound(NumenPlayer target, Packet<?> packet) {
        if (packet instanceof ClientboundOpenScreenPacket open) {
            Title title = target.state(Title.class, Title::new);
            title.containerId = open.getContainerId();
            title.value = open.getTitle().copy();
        }
    }

    private static Component title(NumenPlayer target) {
        Title title = target.state(Title.class, Title::new);
        return title.containerId == target.containerMenu.containerId ? title.value
                : Component.translatable("container.inventory");
    }

    public static void opened(NumenPlayer target) {
        View view = view(target);
        if (view != null && target.containerMenu != target.inventoryMenu) {
            view.actualMenu = target.containerMenu;
            begin(view, target.containerMenu, title(target), false);
        }
    }

    public static void tick(NumenPlayer target) {
        View view = view(target);
        if (view == null) return;
        if (view.actualMenu != target.containerMenu) {
            view.actualMenu = target.containerMenu;
            if (target.containerMenu != target.inventoryMenu) begin(view, target.containerMenu, title(target), false);
        }
        if (view.menu == null || view.closed) return;
        SpectatorMenuFrame frame = SpectatorMenuFrame.capture(view.menu);
        if (!frame.sameContents(view.latest)) send(view, SpectatorMenuPayload.UPDATE, frame);
    }

    public static void openInventory(ServerPlayer owner) {
        View view = VIEWS.get(owner.getUUID());
        if (view != null) begin(view, view.target.inventoryMenu, Component.translatable("container.inventory"), true);
    }

    public static void closeInventory(ServerPlayer owner) {
        View view = VIEWS.get(owner.getUUID());
        if (view == null) return;
        view.action = null;
        if (view.menu == null || view.closed) return;
        send(view, SpectatorMenuPayload.DISMISS, SpectatorMenuFrame.capture(view.menu));
        view.closed = true;
        view.manualInventory = false;
    }

    public static void closeInventory(ServerPlayer owner, long expectedMenuId) {
        View view = VIEWS.get(owner.getUUID());
        if (view != null && view.menuId == expectedMenuId) closeInventory(owner);
    }

    /** After the viewer's hold ends, restore a still-open container from its current values. */
    public static void resumeMenu(ServerPlayer owner, long expectedMenuId) {
        View view = VIEWS.get(owner.getUUID());
        if (view == null || view(view.target) != view || view.menuId != expectedMenuId || !view.closed
                || view.menu != view.target.inventoryMenu || view.target.containerMenu == view.target.inventoryMenu) return;
        view.actualMenu = view.target.containerMenu;
        begin(view, view.actualMenu, title(view.target), false);
    }

    /** InventoryMenu never receives an open-screen packet, so content reports its short display lifetime explicitly. */
    public static void beginInventory(NumenPlayer target) {
        View view = view(target);
        if (view != null && view.menu == target.inventoryMenu && view.manualInventory && !view.closed) {
            view.action = null;
            return;
        }
        if (view != null) begin(view, target.inventoryMenu, Component.translatable("container.crafting"), false);
    }

    public static void endInventory(NumenPlayer target) {
        View view = view(target);
        if (view == null || view.menu != target.inventoryMenu) return;
        if (view.manualInventory && !view.closed) {
            view.recipe = null;
            SpectatorMenuFrame frame = SpectatorMenuFrame.capture(view.menu);
            if (!frame.sameContents(view.latest)) send(view, SpectatorMenuPayload.UPDATE, frame);
        } else {
            close(view);
        }
    }

    /** Called after recipe recomputation and non-empty output validation, before QUICK_MOVE. */
    public static void captureRecipe(NumenPlayer target, AbstractContainerMenu menu) {
        View view = view(target);
        if (view == null) return;
        if (view.menu != menu || view.closed) begin(view, menu, title(target), false);
        if (view.menu != menu || view.closed) return;
        view.action = null;
        view.recipe = SpectatorMenuFrame.capture(menu);
        send(view, SpectatorMenuPayload.UPDATE, view.recipe);
    }

    /** Called before removed() returns ingredients and discards the real container. */
    public static void closing(NumenPlayer target) {
        View view = view(target);
        if (view == null) return;
        view.action = null;
        if (target.containerMenu == target.inventoryMenu || view.manualInventory) return;
        if (view.menu != target.containerMenu) begin(view, target.containerMenu, title(target), false);
        close(view);
    }

    private static void begin(View view, AbstractContainerMenu menu, Component title, boolean manualInventory) {
        view.action = null;
        if (!supported(view, menu)) {
            if (view.menu != null) {
                view.menuId++;
                send(view, SpectatorMenuPayload.DISMISS, SpectatorMenuFrame.capture(view.menu));
                view.closed = true;
            }
            return;
        }
        display(view, menu, title, manualInventory);
        send(view, SpectatorMenuPayload.OPEN, SpectatorMenuFrame.capture(menu));
    }

    private static boolean supported(View view, AbstractContainerMenu menu) {
        if (menu == view.target.inventoryMenu) return true;
        var menuType = ((MenuDataSlotsAccessor) menu).numen$menuType();
        ResourceLocation type = menuType == null ? null : BuiltInRegistries.MENU.getKey(menuType);
        return type != null && type.getNamespace().equals("minecraft");
    }

    private static void display(View view, AbstractContainerMenu menu, Component title, boolean manualInventory) {
        view.action = null;
        view.menu = menu;
        view.title = title.copy();
        view.menuId++;
        view.revision = 0;
        view.recipe = null;
        view.closed = false;
        view.manualInventory = manualInventory;
    }

    private static void close(View view) {
        view.action = null;
        if (view.menu == null || view.closed) return;
        send(view, SpectatorMenuPayload.CLOSE,
                view.recipe != null ? view.recipe : SpectatorMenuFrame.capture(view.menu));
        view.closed = true;
        view.manualInventory = false;
    }

    private static void send(View view, int event, SpectatorMenuFrame frame) {
        send(view, event, frame, List.of(), false);
    }

    private static void send(View view, int event, SpectatorMenuFrame frame,
            List<SpectatorMenuFrame> actionFrames, boolean closeAfterAction) {
        view.latest = frame;
        SpectatorMenuPayload payload = new SpectatorMenuPayload(view.sessionId,
                view.target.getUUID(), view.menuId, ++view.revision, event,
                view.menu == view.target.inventoryMenu ? INVENTORY : BuiltInRegistries.MENU.getKey(view.menu.getType()),
                view.title, view.menu == view.target.inventoryMenu, frame, actionFrames, closeAfterAction);
        int bytes = Wire.size(SpectatorMenuPayload.STREAM_CODEC, payload,
                () -> new RegistryFriendlyByteBuf(Unpooled.buffer(), view.owner.registryAccess()));
        if (!Wire.TO_CLIENT.carries(bytes)) {
            Constants.LOG.warn("[numen-spectator] skipped menu display for {} (session {}, menu {}, event {}): "
                            + "{} bytes exceeds the {} byte message limit; body operation is unchanged",
                    view.target.getUUID(), view.sessionId, view.menuId, event, bytes, Wire.MESSAGE_BYTES);
            // DISMISS consumes only identity and event; its empty frame never becomes displayed inventory.
            view.action = null;
            view.closed = true;
            view.manualInventory = false;
            NumenNetwork.sendToPlayer(view.owner, new SpectatorMenuPayload(view.sessionId,
                    view.target.getUUID(), view.menuId, ++view.revision, SpectatorMenuPayload.DISMISS,
                    INVENTORY, Component.empty(), true,
                    new SpectatorMenuFrame(List.of(), ItemStack.EMPTY, List.of())));
            return;
        }
        NumenNetwork.sendToPlayer(view.owner, payload);
    }
}
