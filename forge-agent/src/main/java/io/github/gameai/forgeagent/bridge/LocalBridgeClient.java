package io.github.gameai.forgeagent.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.gameai.forgeagent.GameAiForgeAgent;
import io.github.gameai.forgeagent.client.ClientCommandExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LocalBridgeClient {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "gameai-bridge-client");
        thread.setDaemon(true);
        return thread;
    });

    private final String clientId = "forge-agent-" + UUID.randomUUID();
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean helloSent;
    private volatile boolean started;
    private long snapshotCounter;

    public void start() {
        if (this.started) {
            return;
        }

        this.started = true;
        this.scheduler.scheduleAtFixedRate(this::tickBridge, 1000L, BridgeConfig.SNAPSHOT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void tickBridge() {
        try {
            ensureConnected();
            if (this.socket == null || this.writer == null) {
                return;
            }

            if (!this.helloSent) {
                sendHello();
                this.helloSent = true;
                startReaderLoop();
            }

            sendSnapshot();
        } catch (Exception error) {
            GameAiForgeAgent.LOGGER.warn("Bridge tick failed: {}", error.toString());
            disconnect();
        }
    }

    private void ensureConnected() {
        if (this.socket != null && this.socket.isConnected() && !this.socket.isClosed()) {
            return;
        }

        try {
            this.socket = new Socket(BridgeConfig.HOST, BridgeConfig.PORT);
            this.writer = new PrintWriter(new OutputStreamWriter(this.socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.reader = new BufferedReader(new InputStreamReader(this.socket.getInputStream(), StandardCharsets.UTF_8));
            this.helloSent = false;
            this.snapshotCounter = 0L;
            GameAiForgeAgent.LOGGER.info("Connected to local bridge at {}:{}", BridgeConfig.HOST, BridgeConfig.PORT);
        } catch (IOException error) {
            disconnect();
            GameAiForgeAgent.LOGGER.debug("Local bridge unavailable at {}:{} ({})", BridgeConfig.HOST, BridgeConfig.PORT, error.toString());
            sleepQuietly(BridgeConfig.RECONNECT_INTERVAL_MS);
        }
    }

    private void sendHello() {
        JsonObject payload = new JsonObject();
        payload.addProperty("clientId", this.clientId);
        payload.addProperty("minecraftVersion", "1.20.1");
        payload.addProperty("loader", "forge");

        JsonObject modCapabilities = new JsonObject();
        modCapabilities.addProperty("epicFight", ModList.get().isLoaded("epicfight"));
        modCapabilities.addProperty("slashBlade", ModList.get().isLoaded("slashblade"));
        payload.add("modCapabilities", modCapabilities);

        writeEnvelope("hello", payload);
        GameAiForgeAgent.LOGGER.info("Sent bridge hello for clientId={} epicFight={} slashBlade={}",
                this.clientId,
                modCapabilities.get("epicFight").getAsBoolean(),
                modCapabilities.get("slashBlade").getAsBoolean());
    }

    private void sendSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("tick", minecraft.level != null ? minecraft.level.getGameTime() : 0L);

        JsonObject playerObject = new JsonObject();
        playerObject.addProperty("name", player.getGameProfile().getName());
        playerObject.addProperty("health", player.getHealth());
        playerObject.addProperty("food", player.getFoodData().getFoodLevel());

        JsonObject position = new JsonObject();
        position.addProperty("x", player.getX());
        position.addProperty("y", player.getY());
        position.addProperty("z", player.getZ());
        playerObject.add("position", position);
        playerObject.addProperty("yaw", player.getYRot());
        playerObject.addProperty("pitch", player.getXRot());
        playerObject.addProperty("combatMode", ModList.get().isLoaded("epicfight") ? "epicfight" : "vanilla");

        payload.add("player", playerObject);
        payload.add("nearbyEntities", collectNearbyEntities(player));
        writeEnvelope("snapshot", payload);
        this.snapshotCounter++;
        if (this.snapshotCounter % 20L == 0L) {
            GameAiForgeAgent.LOGGER.info("Bridge snapshot heartbeat tick={} entities={}",
                    payload.get("tick").getAsLong(),
                    payload.getAsJsonArray("nearbyEntities").size());
        }
    }

    private JsonArray collectNearbyEntities(LocalPlayer player) {
        JsonArray entities = new JsonArray();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return entities;
        }

        AABB box = player.getBoundingBox().inflate(12.0D);
        for (Entity entity : minecraft.level.getEntities(player, box)) {
            JsonObject object = new JsonObject();
            object.addProperty("entityId", entity.getId());
            object.addProperty("name", entity.getType().toShortString());
            object.addProperty("type", classifyEntity(entity));
            object.addProperty("distance", player.distanceTo(entity));

            JsonObject position = new JsonObject();
            position.addProperty("x", entity.getX());
            position.addProperty("y", entity.getY());
            position.addProperty("z", entity.getZ());
            object.add("position", position);

            entities.add(object);
        }

        return entities;
    }

    private String classifyEntity(Entity entity) {
        if (entity instanceof Monster) {
            return "hostile";
        }
        if (entity.getType().toShortString().equals("player")) {
            return "player";
        }
        return "neutral";
    }

    private void writeEnvelope(String type, JsonObject payload) {
        if (this.writer == null) {
            return;
        }

        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type);
        envelope.addProperty("protocolVersion", BridgeProtocolVersion.CURRENT);
        envelope.add("payload", payload);
        this.writer.println(envelope);
    }

    private void startReaderLoop() {
        Thread thread = new Thread(() -> {
            try {
                String line;
                while (this.reader != null && (line = this.reader.readLine()) != null) {
                    handleInboundLine(line);
                }
            } catch (IOException error) {
                GameAiForgeAgent.LOGGER.debug("Bridge reader stopped: {}", error.toString());
            } finally {
                disconnect();
            }
        }, "gameai-bridge-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleInboundLine(String line) {
        JsonObject message = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
        String type = message.has("type") ? message.get("type").getAsString() : "unknown";
        if (!"command".equals(type)) {
            GameAiForgeAgent.LOGGER.debug("Ignoring inbound bridge message type {}", type);
            return;
        }

        JsonObject payload = message.getAsJsonObject("payload");
        String commandId = payload.has("id") ? payload.get("id").getAsString() : "unknown";
        JsonArray actions = payload.has("actions") ? payload.getAsJsonArray("actions") : new JsonArray();
        GameAiForgeAgent.LOGGER.info("Received bridge command {} with {} actions", commandId, actions.size());
        try {
            ClientCommandExecutor.execute(actions);
            sendAck(commandId, "ok", "accepted");
        } catch (Exception error) {
            sendAck(commandId, "error", error.toString());
            throw error;
        }
    }

    private void sendAck(String commandId, String status, String detail) {
        JsonObject payload = new JsonObject();
        payload.addProperty("commandId", commandId);
        payload.addProperty("status", status);
        payload.addProperty("detail", detail);
        writeEnvelope("ack", payload);
        GameAiForgeAgent.LOGGER.info("Sent bridge ack commandId={} status={} detail={}", commandId, status, detail);
    }

    private void disconnect() {
        try {
            if (this.reader != null) {
                this.reader.close();
            }
        } catch (IOException ignored) {
        }
        if (this.writer != null) {
            this.writer.close();
        }
        try {
            if (this.socket != null) {
                this.socket.close();
            }
        } catch (IOException ignored) {
        }

        this.reader = null;
        this.writer = null;
        this.socket = null;
        this.helloSent = false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
