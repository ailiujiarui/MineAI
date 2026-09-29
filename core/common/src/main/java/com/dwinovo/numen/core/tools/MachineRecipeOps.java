package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.adapter.AdapterManager;
import com.dwinovo.numen.agent.adapter.AdapterSpec;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query tool implementation — the business half of {@code MachineRecipeTool}.
 *
 * <p>它把适配器声明的机器契约({@link AdapterSpec.MachineSpec})接上服务端配方本:按声明的
 * {@code recipeType} 找到产出/消耗目标物品的配方,连同声明的输入/输出槽号一起交回模型。机器吃什么、
 * 产什么全是数据,这里不认识任何具体模组。
 *
 * <p>两处不信任声明:<b>没有 recipeType</b>(学来的、只认方块菜单的)或声明的类型不在注册表里(过时的学习
 * 结果)时,搜遍全部配方类型;<b>产出读不出来</b>的配方(EnderIO 的 {@code getResultItem} 是空实现)反射问
 * 对象自己的产出访问口,多产出逐条列全,实在认不出就把配方标成 {@code output_unknown} 交出去(输入照给),
 * 而不是把它丢掉。
 */
public final class MachineRecipeOps {

    /** 每台机器最多列这么多条配方,够选又不至于把回执撑爆。 */
    private static final int MAX_RECIPES = 4;
    /** 一条配方最多认这么多产出,防止坏数据把列表撑爆。 */
    private static final int MAX_OUTPUTS = 8;

    /** 配方对象自己可能的产出访问口,越直接越先试;绝不碰 {@code assemble}(那是出合同的重活)。 */
    private static final String[] OUTPUT_ACCESSORS = {
            "getOutputs", "getOutput", "outputs", "output",
            "getResults", "getResult", "getResultItem", "getResultStacks", "getResultItems"
    };

    /** 产出包装对象(OutputStack/OutputItem…)身上读回 ItemStack 的口。 */
    private static final String[] STACK_ACCESSORS = {
            "getItemStack", "getStack", "stack", "getItem", "item"
    };

    /** {@code getIngredients} 为空的配方(Mekanism 的 ItemStackIngredient)身上读物品输入的口。 */
    private static final String[] INPUT_ACCESSORS = {
            "getInput", "getInputs", "inputs", "getItemInput", "getItemInputs"
    };

    /**
     * @param itemId 目标物品;默认当产出看,{@code role=input} 时当输入看
     * @param count  想要几个(仅按产出算要做几次)
     * @param role   {@code output}(默认)| {@code input}
     */
    public String machineRecipe(String itemId, int count, String role, NumenPlayer self) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("missing required argument: item_id");
        }
        boolean byInput = "input".equalsIgnoreCase(role);
        if (role != null && !role.isBlank() && !byInput && !"output".equalsIgnoreCase(role)) {
            throw new IllegalArgumentException("role must be 'output' or 'input'");
        }
        Item target = ToolArgs.parseItem(itemId);
        if (!(self.level() instanceof ServerLevel level)) {
            return TaskResult.fail("machine recipe lookup needs a server level.").toJson();
        }
        int wanted = Math.max(1, count);

        Collection<RecipeHolder<?>> all = level.getRecipeManager().getRecipes();
        List<RecipeType<?>> everyType = recipeTypes();

        List<Map<String, Object>> machines = new ArrayList<>();
        List<String> where = new ArrayList<>();
        for (AdapterSpec.MachineSpec spec : AdapterManager.registry().machines()) {
            List<Map<String, Object>> recipes = search(level, all, everyType, spec, target, wanted, byInput);
            if (recipes.isEmpty()) {
                continue;   // 这台机器不碰目标物品
            }
            machines.add(machineEntry(spec, recipes));
            where.add(spec.block().isBlank() ? spec.id() : spec.block());
        }

        if (machines.isEmpty()) {
            return TaskResult.fail("no registered machine " + (byInput ? "consumes " : "makes ") + itemId
                    + " — fall back to inv_recipe/jei_recipe").toJson();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", itemId);
        data.put("count", wanted);
        data.put("role", byInput ? "input" : "output");
        data.put("machines", machines);
        String message = itemId + " is " + (byInput ? "consumed by " : "made by ") + machines.size()
                + " machine(s): " + String.join(", ", where)
                + " — see data.machines for the declared slots and the exact inputs.";
        return TaskResult.ok(message, data).toJson();
    }

    /**
     * 一台机器对目标物品的命中。<b>没有 recipeType</b>(学来的、只认方块菜单的)或声明的类型不在注册表里
     * (过时的学习结果)时,搜遍全部配方类型——命中逐条带上真实配方类型,这台机器因此立刻可用。声明了合法类型的
     * 机器只认自己那一类:它没命中就是没命中,不替别的类型代言,否则每台机器都会自称能产/吃目标物品。
     */
    private static List<Map<String, Object>> search(ServerLevel level, Collection<RecipeHolder<?>> all,
                                                    List<RecipeType<?>> everyType, AdapterSpec.MachineSpec spec,
                                                    Item target, int wanted, boolean byInput) {
        List<Map<String, Object>> found = new ArrayList<>();
        RecipeType<?> declared = spec.recipeType().isBlank() ? null : recipeType(spec.recipeType());
        if (declared != null) {
            collect(level, all, spec, declared, target, wanted, byInput, found);
            return found;
        }
        for (RecipeType<?> type : everyType) {
            collect(level, all, spec, type, target, wanted, byInput, found);
            if (found.size() >= MAX_RECIPES) {
                break;
            }
        }
        return found;
    }

    /** 在 {@code type} 下捡目标物品:按输入找消耗它的配方,按产出找产它的配方。 */
    private static void collect(ServerLevel level, Collection<RecipeHolder<?>> all, AdapterSpec.MachineSpec spec,
                                RecipeType<?> type, Item target, int wanted, boolean byInput,
                                List<Map<String, Object>> out) {
        for (RecipeHolder<?> holder : all) {
            if (out.size() >= MAX_RECIPES) {
                return;
            }
            // 每条配方自成一格:整合包里一条坏配方只丢它自己,绝不让它杀掉整个查询。
            try {
                Recipe<?> recipe = holder.value();
                if (recipe.getType() != type || !RecipeProbe.usableIngredients(recipe)) {
                    continue;
                }
                List<ItemStack> outputs = outputsOf(recipe, level.registryAccess());
                if (byInput) {
                    if (!consumes(recipe, target)) {
                        continue;
                    }
                } else if (outputs.isEmpty() || !produces(outputs, target)) {
                    // 认不出产出的配方不能凭猜挂到目标物品名下(否则"谁产 X"会把整类化学品都报出来)
                    continue;
                }
                out.add(recipeEntry(recipe, type, outputs, target, spec, wanted, byInput));
            } catch (RuntimeException broken) {
                com.dwinovo.numen.core.Constants.LOG.debug(
                        "[numen-machine] 配方 {} 坏了,跳过: {}", holder.id(), broken.toString());
            }
        }
    }

    private static Map<String, Object> machineEntry(AdapterSpec.MachineSpec spec,
                                                    List<Map<String, Object>> recipes) {
        Map<String, Object> machine = new LinkedHashMap<>();
        machine.put("id", spec.id());
        machine.put("block", spec.block());
        machine.put("menu", spec.menu());
        machine.put("recipe_type", spec.recipeType());
        machine.put("slots", spec.slots());
        if (!spec.note().isBlank()) {
            machine.put("note", spec.note());
        }
        machine.put("recipes", recipes);
        return machine;
    }

    /** 一条配方:输入按 ingredient 顺序对齐声明的 input 槽,加上产出(认不出就标 output_unknown)。 */
    private static Map<String, Object> recipeEntry(Recipe<?> recipe, RecipeType<?> type, List<ItemStack> outputs,
                                                   Item target, AdapterSpec.MachineSpec spec, int wanted,
                                                   boolean byInput) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("recipe_type", recipeTypeId(type));
        entry.put("inputs", inputsOf(recipe, spec));
        if (outputs.isEmpty()) {
            // EnderIO 这类配方的 getResultItem 是空实现:产出未知也不丢,把输入交出去,
            // 由模型或 jei_recipe 补;标 output_unknown,免得下游把它当成"没有产出"。
            entry.put("output_unknown", true);
            return entry;
        }
        ItemStack primary = byInput ? outputs.get(0) : matched(outputs, target);
        entry.put("output", outputOf(primary));
        List<Map<String, Object>> all = new ArrayList<>();
        for (ItemStack stack : outputs) {
            all.add(outputOf(stack));
        }
        entry.put("outputs", all);
        entry.put("crafts_needed", (wanted + primary.getCount() - 1) / primary.getCount());
        return entry;
    }

    /**
     * 输入按 ingredient 顺序,对齐声明的 input 槽。{@code getIngredients} 没给东西的配方
     * (Mekanism 的物品输入是 {@code ItemStackIngredient},不走 Recipe 的 ingredient 表)反射补上,
     * 好让"吃 X → 产气体 Y"这类配方也说得清自己吃什么。
     */
    private static List<Map<String, Object>> inputsOf(Recipe<?> recipe, AdapterSpec.MachineSpec spec) {
        List<Integer> inputSlots = spec.slots("input");
        List<Map<String, Object>> inputs = new ArrayList<>();
        int at = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            inputs.add(inputEntry(itemIds(ingredient), inputSlots, at));
            at++;
        }
        if (inputs.isEmpty()) {
            for (ItemStack stack : inputItems(recipe)) {
                inputs.add(inputEntry(List.of(itemId(stack.getItem())), inputSlots, at));
                at++;
            }
        }
        return inputs;
    }

    private static Map<String, Object> inputEntry(List<String> items, List<Integer> inputSlots, int at) {
        Map<String, Object> in = new LinkedHashMap<>();
        if (at < inputSlots.size()) {
            in.put("slot", inputSlots.get(at));
        }
        in.put("items", items);
        in.put("count", 1);
        return in;
    }

    /**
     * 配方自己的物品输入表,给 {@code getIngredients} 没覆盖到的配方用:反射读 {@code getInput}/{@code getInputs}
     * 这类口,把它认的物品摊平。纯展示/配对用,认不出给空表。
     */
    public static List<ItemStack> inputItems(Recipe<?> recipe) {
        List<ItemStack> found = new ArrayList<>();
        for (String name : INPUT_ACCESSORS) {
            Method accessor = accessor(recipe.getClass(), name, null);
            if (accessor == null || accessor.getParameterCount() != 0) {
                continue;
            }
            Object value;
            try {
                value = accessor.invoke(recipe);
            } catch (ReflectiveOperationException | RuntimeException broken) {
                continue;
            }
            collectRepresentations(value, found, 0);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return found;
    }

    /**
     * 一条配方的全部展示产出。先走全生态踩实的 {@link RecipeProbe#resultOf};空(或抛异常折成空)时
     * 才反射问配方对象自己的产出访问口,把多产出(磨粉副产、多槽输出)逐条列全。都问不出来就给空表,
     * 由调用方标 {@code output_unknown}。绝不执行 {@code assemble}——那是"出合同"的重活,不是读表。
     */
    public static List<ItemStack> outputsOf(Recipe<?> recipe, HolderLookup.Provider registries) {
        ItemStack primary = RecipeProbe.resultOf(recipe, registries);
        if (!primary.isEmpty()) {
            return List.of(primary);
        }
        List<ItemStack> found = new ArrayList<>();
        for (String name : OUTPUT_ACCESSORS) {
            Method accessor = accessor(recipe.getClass(), name, registries);
            if (accessor == null) {
                continue;
            }
            Object value;
            try {
                value = accessor.getParameterCount() == 0
                        ? accessor.invoke(recipe) : accessor.invoke(recipe, registries);
            } catch (ReflectiveOperationException | RuntimeException broken) {
                continue;
            }
            collectStacks(value, found, 0);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return found;
    }

    /** 找无参的访问口,或唯一那个吃注册表的;没有为 null。 */
    private static Method accessor(Class<?> type, String name, HolderLookup.Provider registries) {
        try {
            Method noArg = type.getMethod(name);
            if (noArg.getParameterCount() == 0 && noArg.getReturnType() != void.class) {
                return noArg;
            }
        } catch (NoSuchMethodException | SecurityException absent) {
            // 换"吃注册表"的那种形态再找
        }
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            if (method.getParameterTypes()[0].isInstance(registries)) {
                return method;
            }
        }
        return null;
    }

    /** 把访问口的返回值(可能是产出对象、列表、数组,或 EnderIO 的 OutputStack 包装)拆成 ItemStack。 */
    private static void collectStacks(Object value, List<ItemStack> out, int depth) {
        if (value == null || depth > 3 || out.size() >= MAX_OUTPUTS) {
            return;
        }
        if (value instanceof ItemStack stack) {
            addStack(out, stack);
            return;
        }
        if (value instanceof Item item && item != Items.AIR) {
            addStack(out, new ItemStack(item));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectStacks(element, out, depth + 1);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectStacks(Array.get(value, i), out, depth + 1);
            }
            return;
        }
        for (String name : STACK_ACCESSORS) {
            try {
                Method method = value.getClass().getMethod(name);
                if (method.getParameterCount() != 0) {
                    continue;
                }
                collectStacks(method.invoke(value), out, depth + 1);
                if (!out.isEmpty()) {
                    return;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 这个包装对象身上没有这个口
            }
        }
    }

    /** 收一个产出,按物品去重(磨粉的同种副产只留一条)。 */
    private static void addStack(List<ItemStack> out, ItemStack stack) {
        if (stack.isEmpty() || out.size() >= MAX_OUTPUTS) {
            return;
        }
        for (ItemStack existing : out) {
            if (existing.getItem() == stack.getItem()) {
                return;
            }
        }
        out.add(stack);
    }

    /**
     * 把 {@code getInput}/{@code getInputs} 的返回值摊成代表物品:可能是单个 ingredient、它们的列表,
     * 也可能是带 {@code getRepresentations()}/{@code getItems()} 的包装(Mekanism 的 ItemStackIngredient)。
     */
    private static void collectRepresentations(Object value, List<ItemStack> out, int depth) {
        if (value == null || depth > 3 || out.size() >= MAX_OUTPUTS) {
            return;
        }
        if (value instanceof ItemStack stack) {
            addStack(out, stack);
            return;
        }
        if (value instanceof Item item && item != Items.AIR) {
            addStack(out, new ItemStack(item));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                collectRepresentations(element, out, depth + 1);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectRepresentations(Array.get(value, i), out, depth + 1);
            }
            return;
        }
        for (String name : new String[]{"getRepresentations", "getItems"}) {
            try {
                Method method = value.getClass().getMethod(name);
                if (method.getParameterCount() != 0) {
                    continue;
                }
                Object represented = method.invoke(value);
                if (represented != null && represented != value) {
                    collectRepresentations(represented, out, depth + 1);
                }
                if (!out.isEmpty()) {
                    return;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 这个包装对象身上没有这个口
            }
        }
    }

    /** 产出表里认目标物品的那条;没有(不该发生)退第一条。 */
    private static ItemStack matched(List<ItemStack> outputs, Item target) {
        for (ItemStack stack : outputs) {
            if (stack.getItem() == target) {
                return stack;
            }
        }
        return outputs.get(0);
    }

    private static boolean produces(List<ItemStack> outputs, Item target) {
        for (ItemStack stack : outputs) {
            if (stack.getItem() == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean consumes(Recipe<?> recipe, Item target) {
        ItemStack probe = new ItemStack(target);
        boolean declared = false;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            declared = true;
            try {
                if (ingredient.test(probe)) {
                    return true;
                }
            } catch (RuntimeException broken) {
                // 坏 ingredient 只当它不认这个物品
            }
        }
        if (declared) {
            return false;   // 声明了输入就用声明的,别再去反射猜
        }
        for (ItemStack stack : inputItems(recipe)) {
            if (stack.getItem() == target) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> outputOf(ItemStack stack) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        output.put("count", stack.getCount());
        return output;
    }

    /** ingredient 认的物品 id(标签就列它覆盖的全部 id,去重)。 */
    private static List<String> itemIds(Ingredient ingredient) {
        List<String> ids = new ArrayList<>();
        for (ItemStack option : ingredient.getItems()) {
            if (option == null || option.isEmpty()) {
                continue;
            }
            String id = itemId(option.getItem());
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** 全部已注册的配方类型,按注册顺序。 */
    private static List<RecipeType<?>> recipeTypes() {
        List<RecipeType<?>> types = new ArrayList<>();
        for (RecipeType<?> type : BuiltInRegistries.RECIPE_TYPE) {
            types.add(type);
        }
        return types;
    }

    private static String recipeTypeId(RecipeType<?> type) {
        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return key == null ? "" : key.toString();
    }

    /** 按 id 找服务端配方类型;空/解析不了/没注册一律 null。 */
    private static RecipeType<?> recipeType(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : BuiltInRegistries.RECIPE_TYPE.getOptional(key).orElse(null);
    }
}
