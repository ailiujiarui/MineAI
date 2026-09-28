package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.block.MachineConfigTool;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * {@code machine_config}:在服务端读/改机器的配置(模式、面的输入输出、访问限制……),不点 GUI。
 *
 * <p>假玩家没有客户端屏幕,机器画在 GUI 上的控件它看不见。这里钉三件事:没有服务端配置的方块干净失败;
 * AE2 机器(用压印器)的配置读得出来,且写下去后重新读得到;未知设置与非法值都给出带"可用值"的干净回执。
 * 没装 AE2 时相关用例就地成功跳过(不硬依赖 AE2)。
 *
 * <p>本工具还没进 {@code NumenCore} 的注册表(约束不许改那个文件),所以批次开场自己登记一次,已登记就跳过。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackMachineConfigGameTests {

    @BeforeBatch(batch = "numen_pack_config")
    public static void preparePackConfigBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
        if (ToolRegistry.get("machine_config") == null) {
            ToolRegistry.register(new MachineConfigTool());
        }
    }

    /** 石头没有方块实体:干净失败,绝不抛。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_config")
    public static void machine_config_on_a_plain_block_fails_cleanly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stone = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_cfg_stone", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_config",
                args("x", stone.getX(), "y", stone.getY(), "z", stone.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "machine_config has not replied");
            helper.assertTrue(!run.succeeded(), "a plain stone exposed config: " + run.reply());
            helper.assertTrue(run.reply().contains("is not a block entity")
                            || run.reply().contains("no readable server-side config"),
                    "the failure is not the clean no-config message: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 箱子有方块实体,但没有任何服务端配置:同样干净失败,不报成机器。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_config")
    public static void machine_config_on_a_chest_has_nothing_to_read(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chest = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_cfg_chest", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_config",
                args("x", chest.getX(), "y", chest.getY(), "z", chest.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "machine_config has not replied");
            helper.assertTrue(!run.succeeded(), "a chest exposed config: " + run.reply());
            helper.assertTrue(run.reply().contains("no readable server-side config"),
                    "the failure is not the clean no-config message: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** AE2 压印器:读得出服务端的配置项,每项带当前值与允许值。没装 AE2 跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_config")
    public static void machine_config_reads_ae2_machine_settings(GameTestHelper helper) {
        BlockPos at = placeInscriberOrSkip(helper);
        if (at == null) {
            return;
        }
        NumenPlayer companion = spawnAt(helper, "gametest_cfg_read", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_config",
                args("x", at.getX(), "y", at.getY(), "z", at.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "machine_config has not replied");
            helper.assertTrue(run.succeeded(), "machine_config refused the inscriber: " + run.reply());
            JsonArray settings = dataOf(run).getAsJsonArray("settings");
            helper.assertTrue(settings != null && settings.size() > 0,
                    "the inscriber showed no settings: " + run.reply());
            JsonObject separate = settingNamed(settings, "INSCRIBER_SEPARATE_SIDES");
            helper.assertTrue(separate != null, "INSCRIBER_SEPARATE_SIDES is missing: " + run.reply());
            helper.assertTrue(separate.has("allowed") && separate.getAsJsonArray("allowed").size() > 0,
                    "the setting carries no allowed values: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** AE2 压印器:写一档配置,回执报新旧值,重新读到的也是新值。没装 AE2 跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_config")
    public static void machine_config_writes_a_setting_and_reports_the_new_value(GameTestHelper helper) {
        BlockPos at = placeInscriberOrSkip(helper);
        if (at == null) {
            return;
        }
        NumenPlayer companion = spawnAt(helper, "gametest_cfg_write", new BlockPos(3, 2, 3), false);
        ToolRun read = call(companion, "machine_config",
                args("x", at.getX(), "y", at.getY(), "z", at.getZ()));
        if (!read.succeeded()) {
            helper.fail("machine_config refused the inscriber read: " + read.reply());
            return;
        }
        JsonObject target = settingNamed(dataOf(read).getAsJsonArray("settings"), "INSCRIBER_SEPARATE_SIDES");
        if (target == null) {
            helper.fail("INSCRIBER_SEPARATE_SIDES is missing: " + read.reply());
            return;
        }
        String current = target.get("value").getAsString();
        String next = null;
        for (JsonElement allowed : target.getAsJsonArray("allowed")) {
            if (!allowed.getAsString().equalsIgnoreCase(current)) {
                next = allowed.getAsString();
                break;
            }
        }
        if (next == null) {
            helper.fail("the setting has no alternative value to write: " + read.reply());
            return;
        }
        String chosen = next;

        ToolRun write = call(companion, "machine_config",
                args("x", at.getX(), "y", at.getY(), "z", at.getZ(),
                        "setting", "INSCRIBER_SEPARATE_SIDES", "value", chosen));
        ToolRun reread = call(companion, "machine_config",
                args("x", at.getX(), "y", at.getY(), "z", at.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(write.done(), "machine_config write has not replied");
            helper.assertTrue(write.succeeded(), "machine_config refused the write: " + write.reply());
            JsonObject data = dataOf(write);
            helper.assertTrue(data.get("old_value").getAsString().equalsIgnoreCase(current),
                    "the report dropped the old value: " + write.reply());
            helper.assertTrue(data.get("new_value").getAsString().equalsIgnoreCase(chosen),
                    "the write did not take: " + write.reply());
            JsonObject after = settingNamed(dataOf(reread).getAsJsonArray("settings"),
                    "INSCRIBER_SEPARATE_SIDES");
            helper.assertTrue(after != null && after.get("value").getAsString().equalsIgnoreCase(chosen),
                    "re-reading did not show the new value: " + reread.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 未知设置:干净失败,并列出这台机器真正有哪些设置。没装 AE2 跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_config")
    public static void machine_config_unknown_setting_fails_cleanly(GameTestHelper helper) {
        BlockPos at = placeInscriberOrSkip(helper);
        if (at == null) {
            return;
        }
        NumenPlayer companion = spawnAt(helper, "gametest_cfg_unknown", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_config",
                args("x", at.getX(), "y", at.getY(), "z", at.getZ(),
                        "setting", "NOT_A_REAL_SETTING", "value", "YES"));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "machine_config has not replied");
            helper.assertTrue(!run.succeeded(), "an unknown setting was accepted: " + run.reply());
            helper.assertTrue(run.reply().contains("no setting named")
                            && run.reply().toLowerCase(java.util.Locale.ROOT)
                                    .contains("inscriber_separate_sides"),
                    "the failure does not list the available settings: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 非法值:干净失败,并列出这一档允许的值。没装 AE2 跳过。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_pack_config")
    public static void machine_config_bad_value_fails_cleanly(GameTestHelper helper) {
        BlockPos at = placeInscriberOrSkip(helper);
        if (at == null) {
            return;
        }
        NumenPlayer companion = spawnAt(helper, "gametest_cfg_bad", new BlockPos(3, 2, 3), false);
        ToolRun run = call(companion, "machine_config",
                args("x", at.getX(), "y", at.getY(), "z", at.getZ(),
                        "setting", "INSCRIBER_SEPARATE_SIDES", "value", "definitely_not_a_value"));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "machine_config has not replied");
            helper.assertTrue(!run.succeeded(), "a bogus value was accepted: " + run.reply());
            helper.assertTrue(run.reply().contains("not a valid value") && run.reply().contains("allowed"),
                    "the failure does not list the allowed values: " + run.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 在场地里放一台 AE2 压印器当被测机器;没装 AE2 就地成功跳过并返回 null。 */
    private static BlockPos placeInscriberOrSkip(GameTestHelper helper) {
        Block inscriber = BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.parse("ae2:inscriber")).orElse(null);
        if (inscriber == null) {
            helper.succeed();
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        helper.getLevel().setBlockAndUpdate(at, inscriber.defaultBlockState());
        return at;
    }

    private static JsonObject dataOf(ToolRun run) {
        return JsonParser.parseString(run.reply()).getAsJsonObject().getAsJsonObject("data");
    }

    /** settings 数组里 name 等于 {@code name} 的那一项(忽略大小写);没有为 null。 */
    private static JsonObject settingNamed(JsonArray settings, String name) {
        for (JsonElement element : settings) {
            JsonObject setting = element.getAsJsonObject();
            if (setting.get("name").getAsString().equalsIgnoreCase(name)) {
                return setting;
            }
        }
        return null;
    }
}
