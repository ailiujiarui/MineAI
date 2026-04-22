package io.github.gameai.forgeagent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.gameai.forgeagent.GameAiForgeAgent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public final class ClientCommandExecutor {
    private ClientCommandExecutor() {
    }

    public static void execute(JsonArray actions) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            for (JsonElement actionElement : actions) {
                if (!actionElement.isJsonObject()) {
                    continue;
                }

                JsonObject action = actionElement.getAsJsonObject();
                String kind = action.has("kind") ? action.get("kind").getAsString() : "unknown";
                switch (kind) {
                    case "move" -> executeMove(minecraft, action);
                    case "look" -> executeLook(minecraft, action);
                    case "attack" -> executeAttack(minecraft, action);
                    case "equip_hotbar" -> executeEquipHotbar(minecraft, action);
                    case "use_skill" -> executeUseSkill(minecraft, action);
                    case "stop_all" -> executeStopAll(minecraft);
                    default -> GameAiForgeAgent.LOGGER.debug("Unhandled bridge action {}", kind);
                }
            }
        });
    }

    private static void executeLook(Minecraft minecraft, JsonObject action) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }

        float yaw = action.has("yaw") ? action.get("yaw").getAsFloat() : player.getYRot();
        float pitch = action.has("pitch") ? action.get("pitch").getAsFloat() : player.getXRot();

        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
    }

    private static void executeAttack(Minecraft minecraft, JsonObject action) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        ClientInputPulseExecutor.pulseAttack(minecraft);
    }

    private static void executeMove(Minecraft minecraft, JsonObject action) {
        float forward = action.has("forward") ? action.get("forward").getAsFloat() : 0.0F;
        float strafe = action.has("strafe") ? action.get("strafe").getAsFloat() : 0.0F;
        boolean jump = action.has("jump") && action.get("jump").getAsBoolean();
        boolean sprint = action.has("sprint") && action.get("sprint").getAsBoolean();

        minecraft.options.keyUp.setDown(forward > 0.1F);
        minecraft.options.keyDown.setDown(forward < -0.1F);
        minecraft.options.keyLeft.setDown(strafe > 0.1F);
        minecraft.options.keyRight.setDown(strafe < -0.1F);
        minecraft.options.keyJump.setDown(jump);
        minecraft.options.keySprint.setDown(sprint);
    }

    private static void executeEquipHotbar(Minecraft minecraft, JsonObject action) {
        LocalPlayer player = minecraft.player;
        if (player == null || !action.has("hotbarIndex")) {
            return;
        }

        int hotbarIndex = Math.max(0, Math.min(8, action.get("hotbarIndex").getAsInt()));
        player.getInventory().selected = hotbarIndex;
    }

    private static void executeUseSkill(Minecraft minecraft, JsonObject action) {
        String skillSlot = action.has("skillSlot") ? action.get("skillSlot").getAsString() : "unknown";
        switch (skillSlot) {
            case "weapon_innate", "guard", "dodge" -> ClientInputPulseExecutor.pulseUse(minecraft);
            default -> GameAiForgeAgent.LOGGER.debug("Unhandled skill slot {}", skillSlot);
        }
    }

    private static void executeStopAll(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.options.keyShift.setDown(false);
        minecraft.options.keyAttack.setDown(false);
        minecraft.options.keyUse.setDown(false);
    }
}
