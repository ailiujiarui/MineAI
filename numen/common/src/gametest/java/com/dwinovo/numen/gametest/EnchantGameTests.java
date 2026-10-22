package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 附魔台:{@code numen.gui.view} 的 state 列出三档报价,{@code numen.gui.enchant} 按下一档——回执必须说真话:附上的正是
 * 报价里列出的那几条、扣的正是那一档要花的等级与青金石;不够时如实说缺什么。
 */
public class EnchantGameTests {

    /** 附魔批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_enchant")
    public static void prepareEnchantBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 模型附魔的整条路:开台子、放剑和青金石、看三档报价、按第一档。物品上附上的正是报价列出的那几条,等级与青金石各扣一,
     * 回执报的新报价和随后 view 读到的一致(点完种子前进,台子重新报价)。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_enchant")
    public static void enchant_the_offer_view_listed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos table = helper.absolutePos(new BlockPos(5, 2, 4));
        level.setBlockAndUpdate(table, Blocks.ENCHANTING_TABLE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_enchanter", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        companion.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 3));
        companion.experienceLevel = 30;
        AtomicReference<ToolRun> step = new AtomicReference<>();
        AtomicReference<List<String>> listed = new AtomicReference<>();
        AtomicReference<List<String>> retold = new AtomicReference<>();

        steps(helper)
                .thenExecute(() -> step.set(lua(companion, "numen.use.block(" + xyz(table) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof EnchantmentMenu,
                        "the enchanting table did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.put(\"minecraft:iron_sword\")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu.slots.get(0).getItem().is(Items.IRON_SWORD),
                        "the sword did not go into the table: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.put(\"minecraft:lapis_lazuli\", 3)")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu.slots.get(1).getItem().getCount() == 3,
                        "the lapis did not go into the table: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(step.get().done() && step.get().succeeded(),
                            "gui view failed at the table: " + step.get().outcome());
                    List<String> state = strings(step.get().field("state"));
                    helper.assertTrue(!state.isEmpty() && state.get(0).equals(
                                    "you have 30 levels; the table's lapis slot holds 3."),
                            "the state does not say her levels and the table's lapis: " + state);
                    helper.assertTrue(offer(1, state).endsWith("ready"),
                            "offer 1 is not ready at 30 levels with 3 lapis: " + state);
                    List<String> enchants = offerEnchantments(1, state);
                    helper.assertTrue(!enchants.isEmpty(),
                            "offer 1 does not name the enchantments it applies: " + state);
                    listed.set(enchants);
                })
                .thenExecute(() -> step.set(lua(companion, "numen.gui.enchant(1)")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(step.get().done() && step.get().succeeded(),
                            "gui enchant failed: " + step.get().outcome());
                    ItemStack enchanted = companion.containerMenu.slots.get(0).getItem();
                    List<String> applied = strings(step.get().field("enchantments"));
                    helper.assertTrue(enchanted.is(Items.IRON_SWORD) && applied.equals(listed.get()),
                            "the offer said " + listed.get() + " but the reply says " + applied);
                    helper.assertTrue(applied.equals(onItem(enchanted)),
                            "the reply does not match the sword's enchantments: " + applied + " vs " + onItem(enchanted));
                    helper.assertTrue(Long.valueOf(1).equals(step.get().field("levels"))
                                    && Long.valueOf(1).equals(step.get().field("lapis"))
                                    && companion.experienceLevel == 29
                                    && companion.containerMenu.slots.get(1).getItem().getCount() == 2,
                            "offer 1 should take 1 level and 1 lapis, she has " + companion.experienceLevel
                                    + " levels and " + companion.containerMenu.slots.get(1).getItem().getCount()
                                    + " lapis");
                    List<String> offers = strings(step.get().field("offers"));
                    helper.assertTrue(!offers.isEmpty() && offers.get(0).equals(
                                    "you have 29 levels; the table's lapis slot holds 2."),
                            "the receipt does not hand back the table's new state: " + offers);
                    retold.set(offers);
                })
                .thenExecute(() -> step.set(lua(companion, "numen.gui.view()")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(step.get().done() && step.get().succeeded(),
                            "the second view failed: " + step.get().outcome());
                    helper.assertTrue(strings(step.get().field("state")).equals(retold.get()),
                            "the receipt's new offers differ from what view reads: "
                                    + step.get().field("state") + " vs " + retold.get());
                })
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /**
     * 按不了的时候如实说:没开台子是 not_found;开了但左格空、没有青金石、等级不够、档位越界各说各的缺口,什么都不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_enchant")
    public static void enchant_refusals_name_the_missing_thing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos table = helper.absolutePos(new BlockPos(5, 2, 4));
        level.setBlockAndUpdate(table, Blocks.ENCHANTING_TABLE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_unready", new BlockPos(3, 2, 4), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        companion.getInventory().add(new ItemStack(Items.LAPIS_LAZULI, 3));
        AtomicReference<ToolRun> step = new AtomicReference<>();

        steps(helper)
                .thenExecute(() -> step.set(lua(companion, "numen.gui.enchant(1)")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && !step.get().succeeded()
                                && "not_found".equals(step.get().kind())
                                && step.get().outcome().contains("no enchanting table"),
                        "without a table it must say not_found: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.use.block(" + xyz(table) + ")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu instanceof EnchantmentMenu,
                        "the table did not open: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.enchant(1)")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && !step.get().succeeded()
                                && step.get().outcome().contains("left slot is empty"),
                        "an empty left slot must say so: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.put(\"minecraft:iron_sword\")")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu.slots.get(0).getItem().is(Items.IRON_SWORD),
                        "the sword did not go in: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.enchant(1)")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && !step.get().succeeded()
                                && "no_material".equals(step.get().kind())
                                && step.get().outcome().contains("lapis"),
                        "no lapis must be no_material about lapis: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.put(\"minecraft:lapis_lazuli\", 3)")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && step.get().succeeded()
                                && companion.containerMenu.slots.get(1).getItem().getCount() == 3,
                        "the lapis did not go in: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.enchant(1)")))
                .thenWaitUntil(() -> helper.assertTrue(step.get().done() && !step.get().succeeded()
                                && "no_material".equals(step.get().kind())
                                && step.get().outcome().contains("levels"),
                        "level 0 must be no_material about levels: " + step.get().outcome()))
                .thenExecute(() -> step.set(lua(companion, "numen.gui.enchant(4)")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(step.get().done() && !step.get().succeeded()
                                    && "bad_argument".equals(step.get().kind()),
                            "offer 4 must be bad_argument: " + step.get().outcome());
                    ItemStack sword = companion.containerMenu.slots.get(0).getItem();
                    helper.assertTrue(EnchantmentHelper.getEnchantmentsForCrafting(sword).isEmpty()
                                    && companion.containerMenu.slots.get(1).getItem().getCount() == 3,
                            "a refused offer moved something: " + sword);
                })
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 报价的那一行:{@code offer 1: ...};没有这一行是空串。 */
    private static String offer(int number, List<String> state) {
        String prefix = "offer " + number + ": ";
        for (String line : state) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return "";
    }

    /** 报价那一行列出的附魔({@code minecraft:sharpness 3}),按 id 排序;破折号那档是空表。 */
    private static List<String> offerEnchantments(int number, List<String> state) {
        String line = offer(number, state);
        int end = line.indexOf(" — requires ");
        if (end < 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : line.substring(("offer " + number + ": ").length(), end).split(", ")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        out.sort(null);
        return out;
    }

    /** 物品身上真实的附魔,读法与 mod 的回执相同。 */
    private static List<String> onItem(ItemStack stack) {
        List<String> out = new ArrayList<>();
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            out.add(entry.getKey().unwrapKey().map(key -> key.location().toString()).orElse("unknown enchantment")
                    + " " + entry.getIntValue());
        }
        out.sort(null);
        return out;
    }

    /** 回执里的字符串表。 */
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
