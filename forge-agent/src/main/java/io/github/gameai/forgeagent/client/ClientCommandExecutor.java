package io.github.gameai.forgeagent.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.gameai.forgeagent.GameAiForgeAgent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ClientCommandExecutor {
    private ClientCommandExecutor() {
    }

    public static void execute(JsonArray actions, Consumer<ExecutionResult> completion) {
        Minecraft minecraft = Minecraft.getInstance();
        Runnable task = () -> {
            List<String> unsupported = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (JsonElement actionElement : actions) {
                if (!actionElement.isJsonObject()) {
                    unsupported.add("non_object_action");
                    continue;
                }

                JsonObject action = actionElement.getAsJsonObject();
                String kind = action.has("kind") ? action.get("kind").getAsString() : "unknown";
                try {
                    switch (kind) {
                        case "move" -> executeMove(minecraft, action);
                        case "look" -> executeLook(minecraft, action);
                        case "attack" -> executeAttack(minecraft, action);
                        case "equip_hotbar" -> executeEquipHotbar(minecraft, action);
                        case "use_skill" -> executeUseSkill(minecraft, action);
                        case "mine_block" -> executeMineBlock(minecraft, action);
                        case "stop_all" -> executeStopAll(minecraft);
                        default -> {
                            unsupported.add(kind);
                            GameAiForgeAgent.LOGGER.debug("Unhandled bridge action {}", kind);
                        }
                    }
                } catch (UnsupportedOperationException unsupportedAction) {
                    unsupported.add(kind + ": " + unsupportedAction.getMessage());
                    GameAiForgeAgent.LOGGER.debug("Bridge action {} is unsupported: {}", kind, unsupportedAction.getMessage());
                } catch (Exception error) {
                    errors.add(kind + ": " + error);
                    GameAiForgeAgent.LOGGER.warn("Bridge action {} failed: {}", kind, error.toString());
                }
            }
            if (!errors.isEmpty()) {
                completion.accept(new ExecutionResult("error", summarize(errors)));
            } else if (!unsupported.isEmpty()) {
                completion.accept(new ExecutionResult("unsupported", summarize(unsupported)));
            } else if (actions.isEmpty()) {
                completion.accept(new ExecutionResult("unsupported", "no_actions"));
            } else {
                completion.accept(new ExecutionResult("ok", "executed"));
            }
        };
        try {
            minecraft.execute(task);
        } catch (Exception error) {
            completion.accept(new ExecutionResult("error", "client_thread_dispatch: " + error));
        }
    }

    private static String summarize(List<String> values) {
        String detail = String.join(", ", values);
        return detail.length() <= 512 ? detail : detail.substring(0, 512);
    }

    public record ExecutionResult(String status, String detail) {
    }

    private static void executeLook(Minecraft minecraft, JsonObject action) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            throw new IllegalStateException("player_unavailable");
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
            throw new IllegalStateException("player_unavailable");
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
        if (player == null) {
            throw new IllegalStateException("player_unavailable");
        }
        if (!action.has("hotbarIndex")) {
            throw new IllegalArgumentException("hotbarIndex_required");
        }

        int hotbarIndex = Math.max(0, Math.min(8, action.get("hotbarIndex").getAsInt()));
        player.getInventory().selected = hotbarIndex;
    }

    private static void executeUseSkill(Minecraft minecraft, JsonObject action) {
        String skillSlot = action.has("skillSlot") ? action.get("skillSlot").getAsString() : "unknown";
        switch (skillSlot) {
            case "weapon_innate", "guard", "dodge" -> ClientInputPulseExecutor.pulseUse(minecraft);
            default -> throw new UnsupportedOperationException("skill_slot:" + skillSlot);
        }
    }

    private static void executeMineBlock(Minecraft minecraft, JsonObject action) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            throw new IllegalStateException("player_unavailable");
        }
        if (minecraft.gameMode == null) {
            throw new IllegalStateException("game_mode_unavailable");
        }
        if (!action.has("position")) {
            throw new IllegalArgumentException("position_required");
        }
        JsonObject position = action.getAsJsonObject("position");
        if (!position.has("x") || !position.has("y") || !position.has("z")) {
            throw new IllegalArgumentException("position_coordinates_required");
        }
        BlockPos blockPos = new BlockPos(
                position.get("x").getAsInt(),
                position.get("y").getAsInt(),
                position.get("z").getAsInt()
        );
        minecraft.gameMode.startDestroyBlock(blockPos, Direction.UP);
        minecraft.gameMode.continueDestroyBlock(blockPos, Direction.UP);
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
