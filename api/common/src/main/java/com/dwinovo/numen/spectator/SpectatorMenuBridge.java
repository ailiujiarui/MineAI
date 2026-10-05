package com.dwinovo.numen.spectator;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.mixin.MenuDataSlotsAccessor;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.payload.SpectatorMenuPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.HashMap;
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

        View(ServerPlayer owner, NumenPlayer target, long sessionId) {
            this.owner = owner;
            this.target = target;
            this.sessionId = sessionId;
            this.actualMenu = target.containerMenu;
        }
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
        if (view == null || view.menu == null || view.closed) return;
        send(view, SpectatorMenuPayload.DISMISS, SpectatorMenuFrame.capture(view.menu));
        view.closed = true;
        view.manualInventory = false;
    }

    public static void closeInventory(ServerPlayer owner, long expectedMenuId) {
        View view = VIEWS.get(owner.getUUID());
        if (view != null && view.menuId == expectedMenuId) closeInventory(owner);
    }

    /** InventoryMenu never receives an open-screen packet, so content reports its short display lifetime explicitly. */
    public static void beginInventory(NumenPlayer target) {
        View view = view(target);
        if (view != null) begin(view, target.inventoryMenu, Component.translatable("container.crafting"), false);
    }

    public static void endInventory(NumenPlayer target) {
        View view = view(target);
        if (view != null && view.menu == target.inventoryMenu && !view.manualInventory) close(view);
    }

    /** Called after recipe recomputation and non-empty output validation, before QUICK_MOVE. */
    public static void captureRecipe(NumenPlayer target, AbstractContainerMenu menu) {
        View view = view(target);
        if (view == null) return;
        if (view.menu != menu || view.closed) begin(view, menu, title(target), false);
        if (view.menu != menu || view.closed) return;
        view.recipe = SpectatorMenuFrame.capture(menu);
        send(view, SpectatorMenuPayload.UPDATE, view.recipe);
    }

    /** Called before removed() returns ingredients and discards the real container. */
    public static void closing(NumenPlayer target) {
        View view = view(target);
        if (view == null || target.containerMenu == target.inventoryMenu || view.manualInventory) return;
        if (view.menu != target.containerMenu) begin(view, target.containerMenu, title(target), false);
        close(view);
    }

    private static void begin(View view, AbstractContainerMenu menu, Component title, boolean manualInventory) {
        if (menu != view.target.inventoryMenu) {
            var menuType = ((MenuDataSlotsAccessor) menu).numen$menuType();
            ResourceLocation type = menuType == null ? null : BuiltInRegistries.MENU.getKey(menuType);
            if (type == null || !type.getNamespace().equals("minecraft")) {
                if (view.menu != null) {
                    view.menuId++;
                    send(view, SpectatorMenuPayload.DISMISS, SpectatorMenuFrame.capture(view.menu));
                    view.closed = true;
                }
                return;
            }
        }
        view.menu = menu;
        view.title = title.copy();
        view.menuId++;
        view.revision = 0;
        view.recipe = null;
        view.closed = false;
        view.manualInventory = manualInventory;
        send(view, SpectatorMenuPayload.OPEN, SpectatorMenuFrame.capture(menu));
    }

    private static void close(View view) {
        if (view.menu == null || view.closed) return;
        send(view, SpectatorMenuPayload.CLOSE,
                view.recipe != null ? view.recipe : SpectatorMenuFrame.capture(view.menu));
        view.closed = true;
        view.manualInventory = false;
    }

    private static void send(View view, int event, SpectatorMenuFrame frame) {
        view.latest = frame;
        NumenNetwork.sendToPlayer(view.owner, new SpectatorMenuPayload(view.sessionId,
                view.target.getUUID(), view.menuId, ++view.revision, event,
                view.menu == view.target.inventoryMenu ? INVENTORY : BuiltInRegistries.MENU.getKey(view.menu.getType()),
                view.title, view.menu == view.target.inventoryMenu, frame));
    }
}
