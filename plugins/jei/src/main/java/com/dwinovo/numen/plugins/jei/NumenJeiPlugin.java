package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.task.TaskResult;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientSupplier;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEI 插件本体:JEI 在客户端就绪后回调 {@link #onRuntimeAvailable(IJeiRuntime)},这里抓住运行期、
 * 把查询实现接给 {@link NumenJei}。它<b>只由 JEI 加载</b>(装了 JEI 的客户端),core 不引用它。
 */
@JeiPlugin
public final class NumenJeiPlugin implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("numen", "jei");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        NumenJei.bridge(new Query(runtime));
    }

    /** 一个物品:JEI 的合成表里它由什么做出来、它又被用在什么配方里,按类别分组,每组带上机器/催化剂。 */
    private static final class Query implements NumenJei.Bridge {

        private final IJeiRuntime runtime;

        Query(IJeiRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public String recipesByCategory(String itemId) {
            ResourceLocation id = ResourceLocation.tryParse(itemId);
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
            if (item == null || item == Items.AIR) {
                return TaskResult.fail("unknown item: " + itemId).toJson();
            }
            IRecipeManager manager = runtime.getRecipeManager();
            IFocusFactory focusFactory = runtime.getJeiHelpers().getFocusFactory();
            StringBuilder sb = new StringBuilder("JEI recipes for ").append(itemId).append(":\n");
            int found = 0;
            found += group(manager, focusFactory, sb, item, RecipeIngredientRole.OUTPUT, "made by (how to get it)");
            found += group(manager, focusFactory, sb, item, RecipeIngredientRole.INPUT, "used in (what it makes)");
            if (found == 0) {
                sb.append("(JEI has no recipe for it — it is probably mined, traded, or a world drop)\n");
            }
            return TaskResult.ok(sb.toString()).toJson();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static int group(IRecipeManager manager, IFocusFactory focusFactory, StringBuilder sb, Item item,
                                 RecipeIngredientRole role, String label) {
            IFocus<?> focus = focusFactory.createFocus(role, (IIngredientType) VanillaTypes.ITEM_STACK, new ItemStack(item));
            List<IFocus<?>> focuses = List.<IFocus<?>>of(focus);
            List<IRecipeCategory<?>> categories =
                    manager.createRecipeCategoryLookup().limitFocus(focuses).get().toList();
            sb.append(label).append(":\n");
            int count = 0;
            for (IRecipeCategory<?> category : categories) {
                List<?> recipes;
                try {
                    recipes = manager.createRecipeLookup((RecipeType) category.getRecipeType())
                            .limitFocus(focuses).get().toList();
                } catch (RuntimeException notQueryable) {
                    continue;
                }
                if (recipes.isEmpty()) {
                    continue;
                }
                // 类别的机器/催化剂:JEI 把"这台机器能做这类配方"注册在这里,比配方里的催化剂槽更可靠
                String machine = catalysts(manager, category);
                for (Object recipe : recipes) {
                    IIngredientSupplier supplier;
                    try {
                        supplier = manager.getRecipeIngredients((IRecipeCategory) category, recipe);
                    } catch (RuntimeException unreadable) {
                        continue;
                    }
                    sb.append("  [").append(category.getTitle().getString()).append("]");
                    if (!machine.isEmpty()) {
                        sb.append(" machine: ").append(machine);
                    }
                    sb.append("  ");
                    sb.append(describe(supplier.getIngredients(RecipeIngredientRole.INPUT))).append(" -> ")
                            .append(describe(supplier.getIngredients(RecipeIngredientRole.OUTPUT)));
                    String station = RecipeSummary.primary(describeEach(
                            supplier.getIngredients(RecipeIngredientRole.CATALYST)));
                    if (!station.isEmpty()) {
                        sb.append("   station: ").append(station);
                    }
                    sb.append("\n");
                    count++;
                }
            }
            if (count == 0) {
                sb.append("  (none)\n");
            }
            return count;
        }

        /**
         * 类别的主要机器/催化剂:JEI 把"这台机器能做这类配方"注册在这里。一个类别可能挂着一长串
         * (原版工作台加各模组终端),这里只留主要的一到三个,见 {@link RecipeSummary}。
         * JEI 还没注册或查询失败就给空串(整段照报,不因它中断)。
         */
        private static String catalysts(IRecipeManager manager, IRecipeCategory<?> category) {
            try {
                List<ItemStack> stacks = manager.createRecipeCatalystLookup(category.getRecipeType())
                        .getItemStack().toList();
                return RecipeSummary.primary(stackIds(stacks));
            } catch (RuntimeException unavailable) {
                return "";
            }
        }

        /** 把一组原料/产物渲染成 "id xN + id2 xM";非物品的按 toString 兜底。 */
        private static String describe(List<ITypedIngredient<?>> ingredients) {
            List<String> each = describeEach(ingredients);
            return each.isEmpty() ? "-" : String.join(" + ", each);
        }

        /** 每个原料/产物一个短串;非物品的按 toString 兜底。 */
        private static List<String> describeEach(List<ITypedIngredient<?>> ingredients) {
            List<String> each = new ArrayList<>();
            if (ingredients == null) {
                return each;
            }
            for (ITypedIngredient<?> typed : ingredients) {
                Optional<ItemStack> stack = typed.getItemStack();
                if (stack.isPresent()) {
                    ItemStack st = stack.get();
                    String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                    if (st.getCount() > 1) {
                        id += " x" + st.getCount();
                    }
                    each.add(id);
                } else {
                    each.add(String.valueOf(typed.getIngredient()));
                }
            }
            return each;
        }

        /** 催化剂 ItemStack 列表 → id 列表;空/空气丢弃。 */
        private static List<String> stackIds(List<ItemStack> stacks) {
            List<String> ids = new ArrayList<>();
            for (ItemStack stack : stacks) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (stack.getCount() > 1) {
                    id += " x" + stack.getCount();
                }
                ids.add(id);
            }
            return ids;
        }
    }
}
