package com.dwinovo.numen.client.spectator;

import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.spectator.SpectatorMenuClient;
import com.dwinovo.numen.network.payload.SpectatorRequestPayload;
import com.dwinovo.numen.network.payload.SpectatorStatePayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Client-only viewing state; the owner's real inventory is never a display mirror. */
public final class SpectatorClient {
    private static final SpectatorClientSession SESSION = new SpectatorClientSession();
    private static final SpectatorCameraRestore CAMERA_RESTORE = new SpectatorCameraRestore();
    private static SpectatorStatePayload state;
    private static AbstractClientPlayer target;
    private static Inventory inventory;
    private static ClientLevel level;
    private static CameraType originalCameraType;
    private static UUID originalCamera;

    private SpectatorClient() {}

    public static boolean active() { return SESSION.active(); }
    public static long sessionId() { return SESSION.generation(); }
    public static AbstractClientPlayer target() { return target; }
    public static Inventory mirrorInventory() { return inventory; }
    public static SpectatorStatePayload state() { return state; }
    public static boolean accepts(long sessionId, UUID target) { return SESSION.accepts(sessionId, target); }

    public static void enter(UUID companion) {
        NumenNetwork.sendToServer(new SpectatorRequestPayload(companion, SpectatorRequestPayload.ENTER));
    }

    public static void exit() {
        if (active()) {
            NumenNetwork.sendToServer(new SpectatorRequestPayload(SESSION.target(), SpectatorRequestPayload.EXIT,
                    SESSION.generation(), 0));
        }
    }

    public static void openInventory() {
        if (active()) {
            NumenNetwork.sendToServer(new SpectatorRequestPayload(SESSION.target(), SpectatorRequestPayload.INVENTORY,
                    SESSION.generation(), 0));
        }
    }

    public static void handle(SpectatorStatePayload payload) {
        long previous = SESSION.generation();
        if (!SESSION.update(payload.sessionId(), payload.target(), payload.active())) return;
        if (!payload.active()) {
            UUID restoredCamera = originalCamera;
            clearDisplay();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && payload.entityId() == mc.player.getId()) {
                CAMERA_RESTORE.clear();
                mc.setCameraEntity(mc.player);
            } else {
                CAMERA_RESTORE.request(restoredCamera, payload.entityId(), payload.dimension().toString());
                restoreCameraIfTracked();
            }
            return;
        }
        // ENTER may have been sent before the preceding EXIT acknowledgement. Only the accepted
        // new view consumes its pending restoration; a rejected ENTER must leave it intact.
        UUID pendingOriginalCamera = CAMERA_RESTORE.beginViewing();
        Minecraft mc = Minecraft.getInstance();
        if (originalCameraType == null) {
            originalCameraType = mc.options.getCameraType();
            Entity camera = mc.getCameraEntity();
            if (originalCamera == null) originalCamera = pendingOriginalCamera != null
                    ? pendingOriginalCamera : camera == null ? null : camera.getUUID();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            if (mc.screen != null) mc.setScreen(null);
        }
        if (previous != payload.sessionId()) {
            clearBinding();
            SpectatorMenuClient.onSessionChanged(payload.sessionId(), payload.target());
        }
        state = payload;
        bindTarget();
        copyInventory();
    }

    public static void tick() {
        if (!active()) {
            restoreCameraIfTracked();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            reset();
            return;
        }
        bindTarget();
        SpectatorMenuClient.tick();
    }

    private static void bindTarget() {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer next = null;
        if (state != null && mc.level != null && mc.level.dimension().location().equals(state.dimension())) {
            Entity entity = mc.level.getEntity(state.entityId());
            if (entity instanceof AbstractClientPlayer player && player.getUUID().equals(state.target()) && !player.isRemoved()) {
                next = player;
            }
        }
        if (next != target || level != mc.level) {
            // An initial entity packet may follow same-tick menu open/snapshot/close packets.
            // Preserve those pending frames until the first entity becomes available.
            boolean lostBinding = target != null;
            clearHands();
            if (lostBinding) SpectatorMenuClient.suspend();
            level = mc.level;
            target = next;
            SpectatorMenuClient.onSessionChanged(SESSION.generation(), SESSION.target());
            if (target != null) {
                inventory = new Inventory(target);
                copyInventory();
            }
        }
        if (target != null && mc.getCameraEntity() != target) {
            mc.setCameraEntity(target);
        }
    }

    private static void copyInventory() {
        if (inventory == null || state == null) return;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            inventory.setItem(i, i < state.inventory().size() ? state.inventory().get(i).copy() : ItemStack.EMPTY);
        }
        inventory.selected = state.selected();
    }

    private static void clearBinding() {
        SpectatorMenuClient.reset();
        clearHands();
    }

    private static void clearHands() {
        target = null;
        inventory = null;
        level = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer != null) {
            ((SpectatorHandRenderer) mc.gameRenderer.itemInHandRenderer).numen$resetHands();
        }
    }

    private static void clearDisplay() {
        clearBinding();
        state = null;
        Minecraft mc = Minecraft.getInstance();
        originalCamera = null;
        if (originalCameraType != null) {
            mc.options.setCameraType(originalCameraType);
            originalCameraType = null;
        }
    }

    /** Disconnect/world lifecycle resets the packet generation as well as every display cache. */
    public static void reset() {
        clearDisplay();
        CAMERA_RESTORE.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.setCameraEntity(mc.player);
        SESSION.reset();
    }

    private static void restoreCameraIfTracked() {
        if (!CAMERA_RESTORE.pending()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity camera = mc.level.getEntity(CAMERA_RESTORE.entityId());
        if (camera != null && !camera.isRemoved() && CAMERA_RESTORE.resolve(camera.getId(), camera.getUUID(),
                mc.level.dimension().location().toString())) {
            mc.setCameraEntity(camera);
        }
    }
}
