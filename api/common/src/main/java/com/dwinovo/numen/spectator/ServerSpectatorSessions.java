package com.dwinovo.numen.spectator;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.SpectatorRequestPayload;
import com.dwinovo.numen.network.payload.SpectatorStatePayload;
import com.dwinovo.numen.platform.ServerLifecycle;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

/** 观看订阅独立于身体和 AI 循环,每位主人只订阅自己的一具身体。 */
public final class ServerSpectatorSessions {
    // 包处理器的输入隔离可在网络线程读;所有会话变更仍只在服务器主线程执行。
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static long nextId;

    static {
        ServerLifecycle.onStopped(SESSIONS::clear);
    }

    private ServerSpectatorSessions() {}

    public static final class Session {
        private ServerPlayer owner;
        private final OwnerLocation original;
        private final GameType gameMode;
        private final UUID camera;
        private final float yaw;
        private final float pitch;
        private final boolean flying;
        private final Vec3 velocity;
        private final float fallDistance;
        private UUID target;
        private NumenPlayer body;
        private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> boundDimension;
        private Vec3 lastPosition;
        private long id;
        private SpectatorStatePayload lastState;
        private final Map<MapId, HeldMap> maps = new HashMap<>();

        private Session(ServerPlayer owner) {
            this.owner = owner;
            original = OwnerLocation.capture(owner);
            gameMode = owner.gameMode.getGameModeForPlayer();
            camera = owner.getCamera().getUUID();
            yaw = owner.getYRot();
            pitch = owner.getXRot();
            flying = owner.getAbilities().flying;
            velocity = owner.getDeltaMovement();
            fallDistance = owner.fallDistance;
        }

        public ServerPlayer owner() { return owner; }
        public long id() { return id; }
        public UUID target() { return target; }
    }

    private record HeldMap(byte scale, boolean locked, byte[] pixels, List<MapDecoration> decorations) {}

    public static boolean isViewing(ServerPlayer owner) {
        return SESSIONS.containsKey(owner.getUUID());
    }

    public static Session sessionFor(NumenPlayer target) {
        Session session = target.getOwnerUuid() == null ? null : SESSIONS.get(target.getOwnerUuid());
        return session != null && session.body == target ? session : null;
    }

    static OwnerLocation savedLocation(ServerPlayer owner) {
        Session session = SESSIONS.get(owner.getUUID());
        return session == null ? null : session.original;
    }

    public static void request(ServerPlayer owner, UUID target, int action) {
        Session session = SESSIONS.get(owner.getUUID());
        request(owner, new SpectatorRequestPayload(target, action, session == null ? 0 : session.id, 0));
    }

    public static void request(ServerPlayer owner, SpectatorRequestPayload request) {
        UUID target = request.target();
        int action = request.action();
        Session current = SESSIONS.get(owner.getUUID());
        if (action != SpectatorRequestPayload.ENTER
                && (current == null || current.id != request.sessionId())) return;
        switch (action) {
            case SpectatorRequestPayload.ENTER -> enter(owner, target);
            case SpectatorRequestPayload.EXIT -> {
                Session session = SESSIONS.get(owner.getUUID());
                if (session != null && session.target.equals(target)) exit(owner);
            }
            case SpectatorRequestPayload.INVENTORY -> {
                Session session = SESSIONS.get(owner.getUUID());
                if (session != null && session.target.equals(target) && session.body != null
                        && session.body.isOwnedByPlayer(owner.getUUID())) {
                    SpectatorMenuBridge.openInventory(owner);
                }
            }
            case SpectatorRequestPayload.CLOSE_INVENTORY -> {
                Session session = SESSIONS.get(owner.getUUID());
                if (session != null && session.target.equals(target)) {
                    SpectatorMenuBridge.closeInventory(owner, request.menuId());
                }
            }
            case SpectatorRequestPayload.RESUME_MENU -> {
                if (current.target.equals(target) && current.body != null
                        && current.body.isOwnedByPlayer(owner.getUUID())) {
                    SpectatorMenuBridge.resumeMenu(owner, request.menuId());
                }
            }
            default -> { }
        }
    }

    public static void enter(ServerPlayer owner, UUID target) {
        MinecraftServer server = owner.getServer();
        NumenPlayer body = target == null ? null : NumenPlayer.findByUuid(server, target);
        if (body == null || !body.isAlive() || !body.isOwnedByPlayer(owner.getUUID())) return;
        Session session = SESSIONS.get(owner.getUUID());
        if (session == null) {
            // 先记原站位,再收主人自己的界面、改变模式和摄像机。切目标只更换订阅。
            session = new Session(owner);
            SESSIONS.put(owner.getUUID(), session);
            owner.closeContainer();
        } else if (session.body == body && owner.getCamera() == body) {
            return;
        }
        SpectatorMenuBridge.stop(owner);
        session.target = target;
        bind(session, body);
    }

    private static void bind(Session session, NumenPlayer body) {
        session.id = ++nextId;
        session.body = body;
        session.boundDimension = body.serverLevel().dimension();
        session.lastPosition = body.position();
        session.lastState = null;
        session.maps.clear();
        ServerPlayer owner = session.owner;
        // 首包先于相机变化,客户端可以保留进入前的摄像机类型与数据来源。
        if (!sendState(session)) {
            exit(owner);
            return;
        }
        owner.setGameMode(GameType.SPECTATOR);
        owner.setCamera(owner);
        owner.teleportTo(body.serverLevel(), body.getX(), body.getY(), body.getZ(), Set.of(),
                body.getYRot(), body.getXRot());
        owner.setCamera(body);
        sendHeldMaps(session, body);
        // setCamera 包可能早于目标实体/区块加载;客户端用此 UUID 和实体号等追踪完成再绑定。
        SpectatorMenuBridge.start(owner, body, session.id);
    }

    /** 服务端每刻补实际状态,涵盖 NPC 直接换槽和物品组件变化。 */
    public static void tick(MinecraftServer server) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            ServerPlayer owner = server.getPlayerList().getPlayer(session.owner.getUUID());
            if (owner == null) {
                exit(session.owner);
                continue;
            }
            boolean ownerReplaced = owner != session.owner;
            session.owner = owner;
            NumenPlayer body = NumenPlayer.findByUuid(server, session.target);
            CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(session.target);
            if (body == null || !body.isAlive() || body.isRemoved() || entry != null && entry.diedAt() > 0L) {
                // 调度先于实体 tick:血量已归零时,死亡登记可能还没落下。仍在场的自有尸体
                // 属于同一恢复过渡;已登记死亡的身体即使暂被回满血等待移除,也不能重新绑定。
                boolean dying = body != null && !body.isRemoved() && !body.isAlive()
                        && body.isOwnedByPlayer(owner.getUUID());
                if (entry == null || !owner.getUUID().equals(entry.owner()) || entry.diedAt() == 0L && !dying) {
                    exit(owner);
                } else if (session.body != null || ownerReplaced) {
                    // 死亡恢复期间保留观看意图与原站位,旧身体、HUD 和菜单全部作废。
                    SpectatorMenuBridge.stop(owner);
                    session.body = null;
                    session.id = ++nextId;
                    session.lastState = null;
                    session.maps.clear();
                    owner.setGameMode(GameType.SPECTATOR);
                    owner.setCamera(owner);
                    sendState(session);
                }
                continue;
            }
            if (!body.isOwnedByPlayer(owner.getUUID())) {
                exit(owner);
                continue;
            }
            if (ownerReplaced || session.body != body || session.boundDimension != body.serverLevel().dimension()
                    || session.lastPosition.distanceToSqr(body.position()) > 64 * 64
                    || owner.serverLevel() != body.serverLevel() || owner.getCamera() != body
                    || owner.position().distanceToSqr(body.position()) > 64 * 64
                    || owner.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                SpectatorMenuBridge.stop(owner);
                bind(session, body);
            } else {
                // 真实主人坐标负责区块流与实体追踪;不修改同伴的位置。
                owner.serverLevel().getChunkSource().move(owner);
                if (!sendState(session)) {
                    exit(owner);
                    continue;
                }
                sendHeldMaps(session, body);
            }
            session.lastPosition = body.position();
        }
    }

    /** 地图画面直接复制真实已保存数据,不把观看者登记成持图者或改变地图图标。 */
    private static void sendHeldMaps(Session session, NumenPlayer body) {
        Set<MapId> held = new HashSet<>();
        for (ItemStack item : List.of(body.getMainHandItem(), body.getOffhandItem())) {
            if (!item.is(Items.FILLED_MAP)) continue;
            MapId id = item.get(DataComponents.MAP_ID);
            if (id == null || !held.add(id)) continue;
            MapItemSavedData data = MapItem.getSavedData(item, body.level());
            if (data == null) continue;
            List<MapDecoration> decorations = new ArrayList<>();
            for (MapDecoration decoration : data.getDecorations()) {
                decorations.add(new MapDecoration(decoration.type(), decoration.x(), decoration.y(), decoration.rot(),
                        decoration.name().map(net.minecraft.network.chat.Component::copy)));
            }
            HeldMap previous = session.maps.get(id);
            if (previous != null && previous.scale() == data.scale && previous.locked() == data.locked
                    && Arrays.equals(previous.pixels(), data.colors) && previous.decorations().equals(decorations)) continue;
            byte[] pixels = data.colors.clone();
            session.maps.put(id, new HeldMap(data.scale, data.locked, pixels, List.copyOf(decorations)));
            session.owner.connection.send(new ClientboundMapItemDataPacket(id, data.scale, data.locked, decorations,
                    new MapItemSavedData.MapPatch(0, 0, 128, 128, pixels)));
        }
        session.maps.keySet().retainAll(held);
    }

    private static boolean sendState(Session session) {
        NumenPlayer body = session.body;
        SpectatorStatePayload state;
        if (body == null) {
            state = new SpectatorStatePayload(session.id, session.target, -1,
                    session.owner.serverLevel().dimension().location(), true, List.of(), 0,
                    0, 0, 0, 0, 0, 0, 0, 0);
        } else {
            List<ItemStack> inventory = new ArrayList<>(body.getInventory().getContainerSize());
            for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
                inventory.add(body.getInventory().getItem(i).copy());
            }
            state = new SpectatorStatePayload(session.id, body.getUUID(), body.getId(),
                    body.serverLevel().dimension().location(), true, List.copyOf(inventory),
                    body.getInventory().selected, body.getHealth(), body.getMaxHealth(),
                    body.getFoodData().getFoodLevel(), body.getFoodData().getSaturationLevel(),
                    body.experienceProgress, body.experienceLevel, body.totalExperience,
                    body.getAttackStrengthScale(0.0F));
        }
        if (!sameState(state, session.lastState)) {
            if (!sendState(session.owner, state)) return false;
            session.lastState = state;
        }
        return true;
    }

    private static boolean sendState(ServerPlayer owner, SpectatorStatePayload state) {
        int bytes = Wire.size(SpectatorStatePayload.STREAM_CODEC, state,
                () -> new RegistryFriendlyByteBuf(Unpooled.buffer(), owner.registryAccess()));
        if (!Wire.TO_CLIENT.carries(bytes)) {
            // 缺少首包会让客户端无法退出,更新丢失则会留下旧 HUD;调用方结束观看并恢复主人。
            Constants.LOG.warn("[numen-spectator] ended viewing for {} (session {}): {} bytes exceeds "
                            + "the {} byte message limit; body operation is unchanged",
                    state.target(), state.sessionId(), bytes, Wire.MESSAGE_BYTES);
            return false;
        }
        NumenNetwork.sendToPlayer(owner, state);
        return true;
    }

    private static boolean sameState(SpectatorStatePayload a, SpectatorStatePayload b) {
        if (b == null || a.sessionId() != b.sessionId() || a.entityId() != b.entityId()
                || a.selected() != b.selected() || a.health() != b.health() || a.maxHealth() != b.maxHealth()
                || a.food() != b.food() || a.saturation() != b.saturation()
                || a.experienceProgress() != b.experienceProgress() || a.experienceLevel() != b.experienceLevel()
                || a.totalExperience() != b.totalExperience() || a.attackStrength() != b.attackStrength()
                || a.inventory().size() != b.inventory().size()) return false;
        for (int i = 0; i < a.inventory().size(); i++) {
            if (!ItemStack.matches(a.inventory().get(i), b.inventory().get(i))) return false;
        }
        return true;
    }

    /** 在 PlayerList 保存登出数据前调用,防止把旁观坐标/模式写成下一次登录位置。 */
    public static void exit(ServerPlayer owner) {
        Session session = SESSIONS.remove(owner.getUUID());
        if (session == null) return;
        SpectatorMenuBridge.stop(owner);
        owner.setCamera(owner);
        owner.setGameMode(session.gameMode);
        OwnerLocation original = session.original;
        owner.teleportTo(original.level(), original.position().x, original.position().y, original.position().z,
                Set.of(), session.yaw, session.pitch);
        var originalCamera = session.camera.equals(owner.getUUID()) ? owner : original.level().getEntity(session.camera);
        if (originalCamera != null && originalCamera != owner && !originalCamera.isRemoved()) {
            // 跨维度传送会重置 camera;先回原维度再绑定,随后校正 setCamera 自带的传送。
            owner.setCamera(originalCamera);
            owner.teleportTo(original.level(), original.position().x, original.position().y, original.position().z,
                    Set.of(), session.yaw, session.pitch);
        }
        owner.getAbilities().flying = session.flying;
        owner.onUpdateAbilities();
        owner.setDeltaMovement(session.velocity);
        owner.fallDistance = session.fallDistance;
        owner.setOnGround(original.onGround());
        owner.inventoryMenu.sendAllDataToRemote();
        sendState(owner, new SpectatorStatePayload(++nextId, session.target, owner.getCamera().getId(),
                original.level().dimension().location(), false, List.of(), 0,
                0, 0, 0, 0, 0, 0, 0, 0));
    }

    /** 关服保存玩家前恢复,ServerLifecycle 只负责随后清除进程内记录。 */
    public static void restoreAll(MinecraftServer server) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            if (session.owner.getServer() == server) exit(session.owner);
        }
    }
}
