package com.dwinovo.numen.plugins.ae2;

import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.FakeSlot;
import appeng.parts.AEBasePart;
import appeng.parts.encoding.EncodingMode;
import appeng.parts.encoding.PatternEncodingLogic;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Positional;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
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
import java.util.Optional;

/**
 * {@code ae2.pattern}:在打开的模式编码终端里,把配方编成 AE2 的样板。
 *
 * <p>编码格是鬼影格(FakeSlot):只收"设置",不收真实物品,所以别的动作放不进去。这里走服务端同一条路——
 * 代她填进编码格、按下终端自己的 {@code encode()}。默认处理模式(给 inputs/outputs);合成模式按匹配到的
 * 原版 3x3 配方摆格子。编好的样板落进她的背包,或在终端输出槽等她 {@code use shift} 拿。
 *
 * <p>配置格与鬼影格是私有字段:优先用 AE2 公开 getter,拿不到再反射,全程包 {@code Throwable}。
 * AE2 不在场时这个类不被加载。
 */
public final class Ae2PatternApi {

    private static final String BLANK_PATTERN_ID = "ae2:blank_pattern";

    private Ae2PatternApi() {}

    public static void install(NumenApi numen) {
        numen.api("pattern", "Encode an AE2 pattern in the currently open ME Pattern ENCODING terminal: give the "
                + "inputs and outputs (processing), or a vanilla 3x3 recipe (crafting).", Ae2PatternApi.class);
    }

    /** 一条 {@code {item, count}};count 省略按 1。 */
    public record ItemAmount(@Doc("Namespaced item id, e.g. minecraft:oak_log.") String item,
                             @Doc("How many; default 1.") @Omitted("1") @Positional Optional<Integer> count) {}

    public record EncodeArgs(@Doc("What the pattern consumes / the crafting grid needs.")
                             List<ItemAmount> inputs,
                             @Doc("What the pattern produces. Processing: one or more. Crafting: exactly one "
                                     + "(the recipe result).")
                             List<ItemAmount> outputs,
                             @Doc("processing (default) = a machine pattern; crafting = a vanilla 3x3 recipe.")
                             @Omitted("processing") Optional<String> mode,
                             @Doc("Crafting patterns only: allow AE2 item substitution.")
                             @Omitted("false") Optional<Boolean> substitute) {}

    public record Encoded(@Doc("One line describing what was encoded.") String summary,
                          @Doc("processing or crafting.") String mode,
                          @Doc("Whether the finished pattern is now in your inventory.") boolean inInventory,
                          @Doc("Where the pattern is, if not in your inventory.") String where) {}

    @Fn("Encode an AE2 pattern in the open ME Pattern Encoding terminal (encoding grid is made of ghost slots, so "
            + "other actions cannot fill it). Default mode processing; mode crafting fills the 3x3 grid from a "
            + "matching vanilla recipe.")
    @Example("ae2.pattern.encode({inputs = {{item=\"minecraft:oak_log\"}}, outputs = "
            + "{{item=\"minecraft:oak_planks\", count=4}}, mode=\"crafting\"})")
    @Example("ae2.pattern.encode({inputs = {{item=\"minecraft:iron_ingot\", count=3}}, outputs = "
            + "{{item=\"minecraft:iron_block\"}}})")
    public static Pending<Encoded> encode(ServerCall call, EncodeArgs args) {
        NumenPlayer self = call.her();
        if (!(self.containerMenu instanceof PatternEncodingTermMenu menu)) {
            throw new ApiError(ErrorKind.NOT_FOUND, notEncodingTerminal(self), null);
        }
        String rawMode = args.mode().orElse("processing").trim().toLowerCase(Locale.ROOT);
        boolean crafting = switch (rawMode) {
            case "", "processing" -> false;
            case "crafting" -> true;
            default -> throw bad("unknown mode '" + args.mode() + "': use processing or crafting.");
        };

        List<ItemStack> inputs = resolve(args.inputs(), "inputs");
        List<ItemStack> outputs = resolve(args.outputs(), "outputs");
        if (inputs.isEmpty()) {
            throw bad("a pattern needs at least one input.");
        }
        if (crafting) {
            if (outputs.size() != 1) {
                throw bad("crafting mode needs exactly one output (the recipe result), got " + outputs.size() + ".");
            }
        } else if (outputs.isEmpty()) {
            throw bad("a processing pattern needs at least one output.");
        }

        Slot blankSlot = privateSlot(menu, "blankPatternSlot", 0);
        Slot encodedSlot = privateSlot(menu, "encodedPatternSlot", 1);
        if (blankSlot == null || encodedSlot == null) {
            throw new ApiError(ErrorKind.FAILED,
                    "this AE2 version doesn't expose the terminal's pattern slots — cannot encode.", null);
        }
        if (!menu.getCarried().isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "something is on the cursor (" + name(menu.getCarried())
                    + ") — put it down first and call again.", null);
        }

        var level = self.serverLevel();
        var target = menu.getTarget();
        var be = target instanceof AEBasePart part ? part.getBlockEntity() : menu.getBlockEntity();
        BlockPos pos = be == null ? null : be.getBlockPos();
        return call.authorize(encodingActions(menu, self, pos, crafting))
                .then(authorization -> {
                    try (authorization) {
                    if (self.serverLevel() != level || self.containerMenu != menu || !menu.stillValid(self)
                            || (be != null && (!level.hasChunkAt(pos) || level.getBlockEntity(pos) != be))
                            || (target instanceof AEBasePart part
                            && part.getHost().getPart(part.getSide()) != part)) {
                        throw new ApiError(ErrorKind.NOT_FOUND, "encoding terminal changed or closed before encoding", null);
                    }
                    if (!menu.getCarried().isEmpty()) {
                        throw new ApiError(ErrorKind.FAILED, "put down the cursor item before encoding", null);
                    }
                    authorization.verify(encodingActions(menu, self, pos, crafting));
                    return encodeAuthorized(menu, self, args, crafting, inputs, outputs, blankSlot, encodedSlot);
                    }
                });
    }

    private static List<Action> encodingActions(PatternEncodingTermMenu menu, NumenPlayer self, BlockPos pos,
                                                 boolean crafting) {
        var state = pos == null ? null : self.level().getBlockState(pos);
        List<Action> actions = new ArrayList<>();
        actions.add(Action.useBlock(pos, state));
        Item blank = BuiltInRegistries.ITEM.get(ResourceLocation.parse(BLANK_PATTERN_ID));
        actions.add(Action.take(pos, state, blank));
        Slot encoded = privateSlot(menu, "encodedPatternSlot", 1);
        if (encoded != null && !encoded.getItem().isEmpty()) {
            actions.add(Action.take(pos, state, encoded.getItem().getItem()));
        }
        Item result = BuiltInRegistries.ITEM.get(ResourceLocation.parse(
                crafting ? "ae2:crafting_pattern" : "ae2:processing_pattern"));
        actions.add(Action.take(pos, state, result));
        return actions;
    }

    private static Encoded encodeAuthorized(PatternEncodingTermMenu menu, NumenPlayer self, EncodeArgs args,
                                            boolean crafting, List<ItemStack> inputs, List<ItemStack> outputs,
                                            Slot blankSlot, Slot encodedSlot) {

        if (!encodedSlot.getItem().isEmpty()) {
            menu.clicked(encodedSlot.index, 0, ClickType.QUICK_MOVE, self);
        }
        if (!isBlankPattern(blankSlot.getItem()) && !supplyBlank(menu, self, blankSlot)) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "no blank pattern — I looked in my inventory (main hand "
                    + "included) and the terminal's network slots; get an ae2:blank_pattern and call again.", null);
        }

        setMode(menu, crafting ? EncodingMode.CRAFTING : EncodingMode.PROCESSING, args.substitute().orElse(null));
        menu.clear();

        String fillError = crafting ? fillCrafting(menu, self, inputs, outputs.get(0))
                : fillProcessing(menu, inputs, outputs);
        if (fillError != null) {
            throw new ApiError(ErrorKind.FAILED, fillError, null);
        }

        menu.encode();
        ItemStack encoded = encodedSlot.getItem();
        if (encoded.isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "AE2 produced no pattern — the "
                    + (crafting ? "crafting grid" : "inputs/outputs") + " didn't form a valid recipe. " + (crafting
                    ? "Check the recipe and pass its exact ingredients."
                    : "A processing pattern needs at least one input and one output."), null);
        }

        String summary = "encoded " + (crafting ? "crafting" : "processing") + " pattern: "
                + describe(inputs) + " -> " + describe(outputs);
        menu.clicked(encodedSlot.index, 0, ClickType.QUICK_MOVE, self);
        if (inInventory(self, encoded)) {
            return new Encoded(summary + "; the pattern is in my inventory.", crafting ? "crafting" : "processing",
                    true, "");
        }
        if (!encodedSlot.getItem().isEmpty()) {
            return new Encoded(summary + "; it is in the terminal's output slot.", crafting ? "crafting" : "processing",
                    false, "use shift " + encodedSlot.index + " to take it");
        }
        return new Encoded(summary + "; it left the output slot but I couldn't confirm where it landed — check my "
                + "inventory.", crafting ? "crafting" : "processing", false, "unknown");
    }

    // ---- fill ----

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

    private static String fillCrafting(PatternEncodingTermMenu menu, NumenPlayer self, List<ItemStack> inputs,
                                       ItemStack output) {
        FakeSlot[] grid = slotArray(menu, "getCraftingGridSlots", "craftingGridSlots");
        if (grid == null || grid.length < 9) {
            return "this AE2 version's pattern terminal doesn't expose its crafting grid — cannot encode.";
        }
        ItemStack[] layout = findLayout(self, inputs, output);
        if (layout == null) {
            return "no vanilla crafting recipe makes " + name(output) + " from exactly those inputs — check it, or "
                    + "use a processing pattern if a machine makes it.";
        }
        for (int i = 0; i < grid.length && i < layout.length; i++) {
            if (layout[i] != null && !layout[i].isEmpty()) {
                menu.setItem(grid[i].index, menu.getStateId(), layout[i]);
            }
        }
        return null;
    }

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

    /** 空白样板:先从背包(含主手)直接取一张写进空白槽,再从终端里属于网络的空白样板 shift 一张进背包。 */
    private static boolean supplyBlank(PatternEncodingTermMenu menu, NumenPlayer self, Slot blankSlot) {
        if (placeOneBlank(menu, self, blankSlot, takeBlankFromInventory(self))) {
            return true;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot src = menu.slots.get(i);
            if (src.container == self.getInventory() || !isBlankPattern(src.getItem())) {
                continue;
            }
            try {
                menu.clicked(i, 0, ClickType.QUICK_MOVE, self);
            } catch (Throwable ignored) {
                // 这一格挪不动就试下一格
            }
            if (placeOneBlank(menu, self, blankSlot, takeBlankFromInventory(self))) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack takeBlankFromInventory(NumenPlayer self) {
        var inv = self.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (isBlankPattern(stack)) {
                ItemStack one = stack.copyWithCount(1);
                stack.shrink(1);
                inv.setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean placeOneBlank(PatternEncodingTermMenu menu, NumenPlayer self, Slot blankSlot,
                                         ItemStack one) {
        if (one == null || one.isEmpty()) {
            return false;
        }
        try {
            menu.setItem(blankSlot.index, menu.getStateId(), one);
            if (isBlankPattern(blankSlot.getItem())) {
                return true;
            }
        } catch (Throwable ignored) {
            // 落到还料
        }
        self.getInventory().add(one);
        return false;
    }

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
                throw bad(field + "[" + i + "] needs an item id.");
            }
            ResourceLocation id = ResourceLocation.tryParse(raw.trim());
            if (id == null) {
                throw bad(field + "[" + i + "]: bad item id '" + raw + "'.");
            }
            Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null || item == Items.AIR) {
                throw bad(field + "[" + i + "]: unknown item '" + id + "'.");
            }
            int count = amount.count().orElse(1);
            if (count < 1) {
                throw bad(field + "[" + i + "]: count must be at least 1.");
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
                + "Open the ME Pattern Encoding Terminal first (use block right on the encoding terminal part), "
                + "then call ae2.pattern.encode.";
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

    private static ApiError bad(String message) {
        return new ApiError(ErrorKind.BAD_ARGUMENT, message, null);
    }
}
