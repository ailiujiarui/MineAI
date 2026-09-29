package com.dwinovo.numen.core.adapter;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.adapter.AdapterManager;
import com.dwinovo.numen.agent.adapter.AdapterSpec;
import com.dwinovo.numen.agent.adapter.Side;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习型适配:同伴"开一次机器"就把这个菜单连同槽位角色记下来,写进
 * {@code config/numen/adapters/auto-learned.json},再热重载适配器。下次
 * {@code machine_recipe} / {@code use gui} 就认得这台机器,不用谁先写一份适配文件。
 *
 * <p>角色由 {@link SlotRoles} 从槽类本身问出来,不依赖任何具体模组;玩家自己的槽位排除在外。
 * 和声明式适配同一份目录、同一份 schema,所以学到的机器与手写的机器一视同仁。
 *
 * <p><b>幂等</b>:菜单或方块已经有了 {@link AdapterSpec.MachineSpec}(声明的、或更早学到的),
 * 直接返回 false,不重写文件。任何一步出错只记一条日志、返回 false,绝不把异常丢回游戏。
 */
public final class LearnedAdapters {

    /** 学到的机器都并进这一个文件;不再给每台机器各开一个文件。 */
    private static final String FILE_NAME = "auto-learned.json";
    /** 文件级适配器 id;与示例文件、插件自带的都不重名。 */
    private static final String ADAPTER_ID = "auto-learned";
    /**
     * 学来的机器排在所有声明式适配之后:手写的契约(带 recipeType)优先于学来的(recipeType 留空),
     * 于是 {@code machine_recipe} 先报声明的机器,学来的只作补充。
     */
    private static final int LEARNED_PRIORITY = -1000;
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private LearnedAdapters() {}

    /**
     * 把 {@code body} 此刻开着的界面记成一台机器。界面没开、是自己的背包界面、没有非玩家槽位、
     * 或这台机器已经认得了,都返回 false(什么都不写)。
     *
     * @param level       方块所在的维度;{@code openedBlock} 为空时也用不上
     * @param body        开界面的同伴
     * @param openedBlock 右键打开它的方块;右键实体/来历不明传 null,方块 id 就留空
     * @return 真的往磁盘写了一条新机器
     */
    public static boolean learn(ServerLevel level, NumenPlayer body, BlockPos openedBlock) {
        if (body == null) {
            return false;
        }
        try {
            AbstractContainerMenu menu = body.containerMenu;
            if (menu == null || menu == body.inventoryMenu) {
                return false;
            }
            var menuKey = BuiltInRegistries.MENU.getKey(menu.getType());
            if (menuKey == null) {
                return false;
            }
            String menuId = menuKey.toString();

            Inventory playerInv = body.getInventory();
            Map<String, List<Integer>> slots = new LinkedHashMap<>();
            int nonPlayer = 0;
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (playerInv != null && slot.container == playerInv) {
                    continue;
                }
                nonPlayer++;
                String role = SlotRoles.roleOf(slot, playerInv);
                if (role != null && !SlotRoles.PLAYER.equals(role)) {
                    slots.computeIfAbsent(role, key -> new ArrayList<>()).add(i);
                }
            }
            if (nonPlayer == 0) {
                return false;
            }

            String blockId = "";
            if (openedBlock != null && level != null) {
                blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(openedBlock).getBlock()).toString();
            }

            // 已经认得的机器(声明的或更早学到的)不再记一遍。
            if (AdapterManager.registry().machineForMenu(menuId).isPresent()) {
                return false;
            }
            if (!blockId.isBlank() && AdapterManager.registry().machineForBlock(blockId).isPresent()) {
                return false;
            }

            Path file = AdapterManager.dir().resolve(FILE_NAME);
            List<AdapterSpec.MachineSpec> existing = readExisting(file);
            if (existing == null) {
                return false;   // 文件读不动,别覆盖它
            }
            for (AdapterSpec.MachineSpec machine : existing) {
                if (machine.menu().equals(menuId)
                        || (!blockId.isBlank() && machine.block().equals(blockId))) {
                    return false;
                }
            }

            String machineId = "auto-" + menuId.replace(':', '_').replace('/', '_');
            String note = "Auto-learned by opening " + menuId
                    + (blockId.isBlank() ? "" : " (" + blockId + ")") + ".";
            AdapterSpec.MachineSpec learned = new AdapterSpec.MachineSpec(
                    machineId, blockId, menuId, "", slots, note);

            List<AdapterSpec.MachineSpec> merged = new ArrayList<>(existing);
            merged.add(learned);
            AdapterSpec spec = new AdapterSpec(ADAPTER_ID, "", Side.SERVER, AdapterSpec.CURRENT_SCHEMA,
                    true, LEARNED_PRIORITY, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), merged);

            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(spec.toJson()), StandardCharsets.UTF_8);
            AdapterManager.reload();
            Constants.LOG.info("[numen-adapter] learned machine '{}' (block={}) slots={}",
                    menuId, blockId.isBlank() ? "-" : blockId, slots);
            return true;
        } catch (Throwable failure) {
            Constants.LOG.warn("[numen-adapter] auto-learn failed, leaving adapters untouched: {}",
                    failure.toString());
            return false;
        }
    }

    /** 文件里已有的机器;文件不存在给空表,读不动给 {@code null}(调用方据此放弃本轮)。 */
    private static List<AdapterSpec.MachineSpec> readExisting(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return AdapterSpec.fromJson(root).machines();
        } catch (Exception broken) {
            Constants.LOG.warn("[numen-adapter] can't read {} (leaving it as-is): {}",
                    file, broken.getMessage());
            return null;
        }
    }
}
