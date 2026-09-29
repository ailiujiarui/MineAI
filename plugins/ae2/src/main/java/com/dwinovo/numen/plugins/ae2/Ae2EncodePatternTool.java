package com.dwinovo.numen.plugins.ae2;

import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.FakeSlot;
import appeng.parts.encoding.EncodingMode;
import appeng.parts.encoding.PatternEncodingLogic;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code encode_pattern}(裸 {@link NumenTool},服务端):把配方编码成 AE2 的**模式(patterns)**。
 *
 * <p>同伴没有客户端屏幕,而模式编码终端的编码格是 {@link FakeSlot}(鬼影格):它只收"设置",不收真实物品,
 * 所以 {@code use transfer} 往那些格子放东西会被拒。GUI 上的"编码"按钮走的也是同一条服务端路子
 * ——{@link PatternEncodingTermMenu#encode()};本工具直接在服务端替它把料填进编码格、按下"编码"。
 *
 * <p>流程:当前打开的菜单必须是 {@link PatternEncodingTermMenu}(模式**访问**终端编码不了);先备一张空白模式
 * ({@code blankPatternSlot}),按模式把输入/输出写进鬼影格(处理模式)或 3x3 合成格(合成模式,配方形状从服务端
 * 配方管理器取),再 {@link PatternEncodingTermMenu#encode()};编好的模式落在 {@code encodedPatternSlot},
 * 顺手挪进她背包。
 *
 * <p>编码格数组与 ConfigInventory 都是私有字段;这里优先用 AE2 公开的 getter
 * ({@code getProcessingInputSlots()} 等),拿不到再退回反射读私有字段,全程包 {@code Throwable}——
 * AE2 改了内部结构也只能干净地回一句"编不了",不能把异常炸进游戏。
 */
public final class Ae2EncodePatternTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final String BLANK_PATTERN_ID = "ae2:blank_pattern";

    /** 一条 {@code {item, count}};count 省略按 1。 */
    private record ItemAmount(String item, Integer count) {}

    private record Args(List<ItemAmount> outputs, List<ItemAmount> inputs, String mode, Boolean substitute) {}

    @Override
    public String name() {
        return "encode_pattern";
    }

    @Override
    public String description() {
        return "Encode an AE2 pattern server-side in the currently open ME Pattern ENCODING Terminal — "
                + "the same action its GUI button performs. The encoding grid is made of ghost (fake) slots, so "
                + "`use transfer` cannot place items there; this tool writes the recipe into them directly. "
                + "Before calling: open the terminal with `use block right <x> <y> <z>` on the pattern ENCODING "
                + "terminal part (a pattern ACCESS terminal cannot encode) and hold at least one ae2:blank_pattern "
                + "(the tool moves one in itself). Default mode is processing: give `inputs` (what the machine "
                + "consumes) and `outputs` (what it produces), each a list of {item, count}. mode:\"crafting\" "
                + "fills the 3x3 grid from a matching vanilla recipe instead — the recipe result is the single "
                + "output entry. After encoding, the finished pattern is moved to my inventory (or left in the "
                + "terminal's output slot if my inventory is full).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .objectArray("outputs", "What the pattern produces: list of {item, count}. Processing mode needs "
                        + "at least one; crafting mode needs exactly one (the recipe result).", b -> b
                        .string("item", "Namespaced item id, e.g. ae2:logic_processor.")
                        .optionalInteger("count", "How many; default 1."))
                .objectArray("inputs", "What the pattern consumes: list of {item, count}. Processing mode needs at "
                        + "least one; crafting mode's entries must satisfy the target recipe (counts expand to "
                        + "individual grid items).", b -> b
                        .string("item", "Namespaced item id, e.g. minecraft:redstone.")
                        .optionalInteger("count", "How many; default 1."))
                .optionalEnum("mode", "processing (default) = a machine/production pattern; crafting = a vanilla "
                        + "3x3 crafting recipe encoded into a crafting pattern.", "processing", "crafting")
                .optionalBool("substitute", "Crafting patterns only: allow AE2 item substitution when crafting.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        reply.accept(encode(args, self));
    }

    private String encode(JsonObject rawArgs, NumenPlayer self) {
        Args a;
        try {
            a = GSON.fromJson(rawArgs, Args.class);
        } catch (Throwable bad) {
            return TaskResult.fail("invalid arguments: " + message(bad)).toJson();
        }
        if (a == null) {
            return TaskResult.fail("invalid arguments: empty call.").toJson();
        }
        try {
            if (!(self.containerMenu instanceof PatternEncodingTermMenu menu)) {
                return TaskResult.fail(notEncodingTerminal(self)).toJson();
            }
            String mode = a.mode() == null ? "processing" : a.mode().trim().toLowerCase(Locale.ROOT);
            boolean crafting = switch (mode) {
                case "", "processing" -> false;
                case "crafting" -> true;
                default -> throw new IllegalArgumentException(
                        "unknown mode '" + a.mode() + "': use processing or crafting.");
            };

            // 先把料解析干净:未知物品在动菜单之前就拒绝,不会编到一半留下半套鬼影。
            List<ItemStack> inputs = resolve(a.inputs(), "inputs");
            List<ItemStack> outputs = resolve(a.outputs(), "outputs");
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("a pattern needs at least one input {item, count}.");
            }
            if (crafting) {
                if (outputs.size() != 1) {
                    throw new IllegalArgumentException(
                            "crafting mode needs exactly one output (the recipe result), got " + outputs.size() + ".");
                }
            } else if (outputs.isEmpty()) {
                throw new IllegalArgumentException("a processing pattern needs at least one output {item, count}.");
            }

            Slot blankSlot = privateSlot(menu, "blankPatternSlot", 0);
            Slot encodedSlot = privateSlot(menu, "encodedPatternSlot", 1);
            if (blankSlot == null || encodedSlot == null) {
                return TaskResult.fail("this AE2 version doesn't expose the terminal's pattern slots — cannot encode.")
                        .toJson();
            }
            if (!menu.getCarried().isEmpty()) {
                return TaskResult.fail("something is on the cursor (" + name(menu.getCarried())
                        + ") — put it down first (use transfer / use shift) and call again.").toJson();
            }

            // 输出槽里若躺着上一次编好的模式,先挪进背包,保证这次编码会正常消耗一张空白模式。
            if (!encodedSlot.getItem().isEmpty()) {
                menu.clicked(encodedSlot.index, 0, ClickType.QUICK_MOVE, self);
            }
            if (!isBlankPattern(blankSlot.getItem()) && !moveOneBlank(menu, self, blankSlot)) {
                return TaskResult.fail("no blank pattern — put down / get an ae2:blank_pattern first "
                        + "(craft it, or take one from the ME network), then call again.").toJson();
            }

            setMode(menu, crafting ? EncodingMode.CRAFTING : EncodingMode.PROCESSING, a.substitute());
            // 清空鬼影格(合成的 3x3 与处理的输入共用同一份 encodedInputsInv),避免上次的残留混进新模式。
            menu.clear();

            String fillError = crafting
                    ? fillCrafting(menu, self, inputs, outputs.get(0))
                    : fillProcessing(menu, inputs, outputs);
            if (fillError != null) {
                return TaskResult.fail(fillError).toJson();
            }

            menu.encode();

            ItemStack encoded = encodedSlot.getItem();
            if (encoded.isEmpty()) {
                return TaskResult.fail("AE2 produced no pattern — the " + (crafting ? "crafting grid" : "inputs/outputs")
                        + " didn't form a valid recipe. " + (crafting
                        ? "Check the recipe with inv_recipe and pass its exact ingredients."
                        : "A processing pattern needs at least one input and one output.")).toJson();
            }

            String summary = "encoded " + (crafting ? "crafting" : "processing") + " pattern: "
                    + describe(inputs) + " -> " + describe(outputs);
            // 把编好的模式挪进她的背包;挪不动就留在输出槽,让模型自己 use shift。
            ItemStack produced = encoded.copy();
            menu.clicked(encodedSlot.index, 0, ClickType.QUICK_MOVE, self);
            if (inInventory(self, produced)) {
                return TaskResult.ok(summary + "; the pattern is in my inventory.").toJson();
            }
            if (!encodedSlot.getItem().isEmpty()) {
                return TaskResult.ok(summary + "; it is in the terminal's output slot — use shift " + encodedSlot.index
                        + " to take it.").toJson();
            }
            return TaskResult.ok(summary + "; it left the output slot but I couldn't confirm where it landed — "
                    + "check my inventory with use gui.").toJson();
        } catch (IllegalArgumentException bad) {
            return TaskResult.fail(bad.getMessage()).toJson();
        } catch (Throwable broken) {
            return TaskResult.fail("encode_pattern failed: " + message(broken)).toJson();
        }
    }

    /** 处理模式:输入/输出各写进对应的一串鬼影格。 */
    private static String fillProcessing(PatternEncodingTermMenu menu, List<ItemStack> inputs,
                                         List<ItemStack> outputs) {
        FakeSlot[] inSlots = slotArray(menu, "getProcessingInputSlots", "processingInputSlots");
        FakeSlot[] outSlots = slotArray(menu, "getProcessingOutputSlots", "processingOutputSlots");
        if (inSlots == null || outSlots == null) {
            return "this AE2 version's pattern terminal doesn't expose its processing slots — cannot encode.";
        }
        if (inputs.size() > inSlots.length) {
            return "too many inputs: " + inputs.size() + " (max " + inSlots.length + ").";
        }
        if (outputs.size() > outSlots.length) {
            return "too many outputs: " + outputs.size() + " (max " + outSlots.length + ").";
        }
        for (int i = 0; i < inputs.size(); i++) {
            menu.setItem(inSlots[i].index, menu.getStateId(), inputs.get(i));
        }
        for (int i = 0; i < outputs.size(); i++) {
            menu.setItem(outSlots[i].index, menu.getStateId(), outputs.get(i));
        }
        return null;
    }

    /** 合成模式:按匹配到的原版配方把 3x3 摆出来,AE2 自己会据此认出配方并编成合成模式。 */
    private static String fillCrafting(PatternEncodingTermMenu menu, NumenPlayer self, List<ItemStack> inputs,
                                       ItemStack output) {
        FakeSlot[] grid = slotArray(menu, "getCraftingGridSlots", "craftingGridSlots");
        if (grid == null || grid.length < 9) {
            return "this AE2 version's pattern terminal doesn't expose its crafting grid — cannot encode.";
        }
        ItemStack[] layout = findLayout(self, inputs, output);
        if (layout == null) {
            return "no vanilla crafting recipe makes " + name(output) + " from exactly those inputs — check it with "
                    + "inv_recipe, or use a processing pattern if a machine makes it.";
        }
        for (int i = 0; i < grid.length && i < layout.length; i++) {
            if (layout[i] != null && !layout[i].isEmpty()) {
                menu.setItem(grid[i].index, menu.getStateId(), layout[i]);
            }
        }
        return null;
    }

    /**
     * 在服务端配方管理器里找一个能消化这些输入的合成配方,并把它摆成 3x3。输出是哪一件从配方自身的结果来
     * (调用方校验过唯一输出)。形状配方按宽高逐格摆;无序配方按槽顺序摆。摆放用"逐件库存 + 贪心择料",
     * 常见配方够用,配料有歧义时可能选中另一种子集,但仍是能编出模式的有效配方。
     */
    private static ItemStack[] findLayout(NumenPlayer self, List<ItemStack> inputs, ItemStack output) {
        Level level = self.level();
        RecipeManager recipes = level.getRecipeManager();
        if (recipes == null) {
            return null;
        }
        Item desired = output.getItem();
        List<ItemStack> pool = new ArrayList<>();
        for (ItemStack stack : inputs) {
            for (int i = 0; i < stack.getCount(); i++) {
                pool.add(stack.copyWithCount(1));
            }
        }
        for (RecipeHolder<CraftingRecipe> holder : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (recipe == null || recipe.isSpecial()) {
                continue;
            }
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty() || result.getItem() != desired) {
                continue;
            }
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.isEmpty()) {
                continue;
            }
            ItemStack[] layout = place(recipe, ingredients, pool);
            if (layout != null) {
                return layout;
            }
        }
        return null;
    }

    private static ItemStack[] place(CraftingRecipe recipe, NonNullList<Ingredient> ingredients,
                                     List<ItemStack> pool) {
        boolean[] used = new boolean[pool.size()];
        ItemStack[] grid = new ItemStack[9];
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            if (w < 1 || h < 1 || w > 3 || h > 3 || ingredients.size() < w * h) {
                return null;
            }
            for (int i = 0; i < w * h; i++) {
                Ingredient ing = ingredients.get(i);
                if (ing == null || ing.isEmpty()) {
                    continue;
                }
                int pick = pick(ing, pool, used);
                if (pick < 0) {
                    return null;
                }
                used[pick] = true;
                grid[(i / w) * 3 + (i % w)] = pool.get(pick);
            }
            return grid;
        }
        int cell = 0;
        for (Ingredient ing : ingredients) {
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            int pick = pick(ing, pool, used);
            if (pick < 0 || cell >= grid.length) {
                return null;
            }
            used[pick] = true;
            grid[cell++] = pool.get(pick);
        }
        return grid;
    }

    private static int pick(Ingredient ingredient, List<ItemStack> pool, boolean[] used) {
        for (int i = 0; i < pool.size(); i++) {
            if (!used[i] && ingredient.test(pool.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 把背包里的一张空白模式移进终端的空白模式槽(真实槽,走正常菜单点击)。 */
    private static boolean moveOneBlank(PatternEncodingTermMenu menu, NumenPlayer self, Slot dest) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot src = menu.slots.get(i);
            if (src == dest || src.container != self.getInventory() || !isBlankPattern(src.getItem())) {
                continue;
            }
            menu.clicked(i, 0, ClickType.PICKUP, self);            // 抓起整叠
            if (!isBlankPattern(menu.getCarried())) {              // 没抓起来就放回去
                if (!menu.getCarried().isEmpty()) {
                    menu.clicked(i, 0, ClickType.PICKUP, self);
                }
                continue;
            }
            menu.clicked(dest.index, 1, ClickType.PICKUP, self);   // 右键放一张
            if (!menu.getCarried().isEmpty()) {
                menu.clicked(i, 0, ClickType.PICKUP, self);        // 余数放回原格
            }
            if (isBlankPattern(dest.getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 设置编码模式——真相在终端部件的 {@link PatternEncodingLogic} 上,菜单的 {@code mode} 字段只是它的镜像:
     * {@code broadcastChanges()}({@code clear()} 会触发)会把菜单字段按 logic 拉回去。所以先改 logic,再把菜单
     * 字段同步过来;拿不到 logic 时退回只改菜单字段。
     */
    private static void setMode(PatternEncodingTermMenu menu, EncodingMode mode, Boolean substitute) {
        PatternEncodingLogic logic = encodingLogic(menu);
        if (logic != null) {
            logic.setMode(mode);
            if (substitute != null) {
                logic.setSubstitution(substitute);
            }
            menu.setMode(logic.getMode());
            menu.setSubstitute(logic.isSubstitution());
            return;
        }
        menu.setMode(mode);
        if (substitute != null) {
            menu.setSubstitute(substitute);
        }
    }

    private static PatternEncodingLogic encodingLogic(PatternEncodingTermMenu menu) {
        try {
            Field f = PatternEncodingTermMenu.class.getDeclaredField("encodingLogic");
            f.setAccessible(true);
            return f.get(menu) instanceof PatternEncodingLogic logic ? logic : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 拿一个编码鬼影格数组:先试 AE2 公开的 getter,拿不到再反射读私有字段(旧版/改动过时兜底)。
     */
    private static FakeSlot[] slotArray(PatternEncodingTermMenu menu, String getter, String field) {
        try {
            Object value = PatternEncodingTermMenu.class.getMethod(getter).invoke(menu);
            if (value instanceof FakeSlot[] slots) {
                return slots;
            }
        } catch (Throwable ignored) {
            // 落到反射
        }
        try {
            Field f = PatternEncodingTermMenu.class.getDeclaredField(field);
            f.setAccessible(true);
            Object value = f.get(menu);
            return value instanceof FakeSlot[] slots ? slots : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 拿一个私有槽字段;拿不到就退回按 addSlot 顺序扫 {@code RestrictedInputSlot}:空白模式槽在先、编码输出槽在后。
     */
    private static Slot privateSlot(PatternEncodingTermMenu menu, String field, int ordinal) {
        try {
            Field f = PatternEncodingTermMenu.class.getDeclaredField(field);
            f.setAccessible(true);
            if (f.get(menu) instanceof Slot slot) {
                return slot;
            }
        } catch (Throwable ignored) {
            // 落到扫描
        }
        List<Slot> restricted = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.getClass().getSimpleName().contains("RestrictedInput")) {
                restricted.add(slot);
            }
        }
        return ordinal < restricted.size() ? restricted.get(ordinal) : null;
    }

    /** 背包里有没有和这件一模一样的东西。 */
    private static boolean inInventory(NumenPlayer self, ItemStack wanted) {
        for (int i = 0; i < self.getInventory().getContainerSize(); i++) {
            if (ItemStack.isSameItemSameComponents(self.getInventory().getItem(i), wanted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlankPattern(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return BLANK_PATTERN_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<ItemStack> resolve(List<ItemAmount> list, String field) {
        List<ItemStack> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            ItemAmount amount = list.get(i);
            String raw = amount == null ? null : amount.item();
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(field + "[" + i + "] needs an item id.");
            }
            ResourceLocation id = ResourceLocation.tryParse(raw.trim());
            if (id == null) {
                throw new IllegalArgumentException(field + "[" + i + "]: bad item id '" + raw + "'.");
            }
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null || item == Items.AIR) {
                throw new IllegalArgumentException(field + "[" + i + "]: unknown item '" + id + "'.");
            }
            int count = amount.count() == null ? 1 : amount.count();
            if (count < 1) {
                throw new IllegalArgumentException(field + "[" + i + "]: count must be at least 1.");
            }
            out.add(new ItemStack(item, count));
        }
        return out;
    }

    private static String notEncodingTerminal(NumenPlayer self) {
        String menu = self.containerMenu == null ? "none" : self.containerMenu.getClass().getSimpleName();
        String hint = menu.contains("PatternAccess")
                ? "that is a pattern ACCESS terminal — it can only insert patterns, not encode them. "
                : "";
        return "no pattern encoding terminal is open (current menu: " + menu + "). " + hint
                + "Open the ME Pattern Encoding Terminal first: `use block right <x> <y> <z>` on the encoding "
                + "terminal part, then call encode_pattern.";
    }

    private static String describe(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(" x").append(stack.getCount());
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()
                + " x" + stack.getCount();
    }

    private static String message(Throwable broken) {
        String text = broken.getMessage();
        return broken.getClass().getSimpleName() + (text == null ? "" : " " + text);
    }
}
