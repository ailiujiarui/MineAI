package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.ClientCall;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
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
 * {@code jei.recipe}:问 JEI(Just Enough Items)一个物品由什么做出来、又被用在什么配方里,按类别分组,
 * 每组带上该类别的机器/催化剂。
 *
 * <p>覆盖的范围比服务端的原版配方本广:装了模组的话,机器配方/自定义合成类别也在这里。JEI 的配方注册表
 * 只在<b>主人的客户端</b>上,所以这组函数是 {@link ClientCall} 一侧的——服务端上的程序调它,引擎转发到客户端
 * 执行、当场答。JEI 还没就绪(或没装)就是一条 {@code not_found},不抛出给网络层。JEI 不在场时这个类不会被加载。
 */
public final class JeiApi {

    /** 一个方向最多回几条配方:答复要走线上的一个包(32767 字节),常见物品的配方动辄上百条。 */
    private static final int MAX_RECIPES_PER_DIRECTION = 16;

    /** 客户端那一份 JEI 运行期;{@link JeiRuntimeBridge} 在 JEI 就绪时接上。 */
    private static volatile IJeiRuntime runtime;

    private JeiApi() {}

    public static void install(NumenApi numen) {
        numen.api("recipe", "Ask JEI (Just Enough Items) what makes an item and what it is used in, grouped by "
                + "recipe category and naming the machine/catalyst of each.", JeiApi.class);
    }

    /** 由 {@link JeiRuntimeBridge} 在客户端 JEI 就绪时调用。 */
    static void bind(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    /** 查哪个物品。 */
    public record Lookup(@Doc("Namespaced item id, e.g. mekanismgenerators:wind_generator.") String item) {}

    /** 一条 JEI 配方,按类别渲染成 id。 */
    public record Recipe(@Doc("The JEI category title, e.g. \"Crafting\".") String category,
                         @Doc("The machine/catalyst JEI registers for this category, \" + \"-joined; empty when "
                                 + "none.") String machine,
                         @Doc("Inputs, one entry per ingredient, e.g. \"minecraft:iron_ingot x2\".")
                         List<String> inputs,
                         @Doc("Outputs, one entry per ingredient, e.g. \"minecraft:iron_ingot x2\".")
                         List<String> outputs,
                         @Doc("The recipe's own catalyst/station (the block it is made in), when it names one.")
                         String station) {}

    /** 一个物品在 JEI 里的配方,按"它由什么做出来"和"它被用在什么里"分开。 */
    public record Recipes(@Doc("The item id as asked.") String item,
                          @Doc("Recipes that produce it (it is an output).") List<Recipe> madeBy,
                          @Doc("Recipes that consume it (it is an input).") List<Recipe> usedIn,
                          @Doc("A one-line human summary.") String summary) {}

    @Fn("Ask JEI how an item is made and what it is used in, grouped by recipe category, naming the machine or "
            + "catalyst of each. Covers modded and custom categories the server recipe book cannot.")
    @Example("local r = jei.recipe.lookup(\"minecraft:iron_ingot\")\nprint(#r.made_by, #r.used_in, r.summary)")
    @Example("for _, rec in ipairs(jei.recipe.lookup(\"minecraft:diamond\").used_in) do "
            + "print(rec.category, rec.machine) end")
    @Note("Instant and read-only, on the owner's client against JEI's own registry, so JEI must be installed "
            + "there. Each recipe names its category and the machine/catalyst; a recipe whose inputs or outputs "
            + "JEI cannot read is skipped. Use it when the server-side `inv.recipe` cannot resolve the recipe.")
    public static Recipes lookup(ClientCall call, Lookup args) {
        IJeiRuntime jei = runtime;
        if (jei == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "JEI is not ready on this client", null);
        }
        ResourceLocation id = ResourceLocation.tryParse(args.item());
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
        if (item == null || item == Items.AIR) {
            throw new ApiError(ErrorKind.NOT_FOUND, "unknown item: " + args.item(), null);
        }
        IRecipeManager manager = jei.getRecipeManager();
        IFocusFactory focusFactory = jei.getJeiHelpers().getFocusFactory();
        List<Recipe> madeBy = group(manager, focusFactory, item, RecipeIngredientRole.OUTPUT);
        List<Recipe> usedIn = group(manager, focusFactory, item, RecipeIngredientRole.INPUT);
        String summary = "JEI: " + args.item() + " — made by " + madeBy.size() + " recipe(s), used in "
                + usedIn.size() + (madeBy.isEmpty() && usedIn.isEmpty()
                        ? " (no JEI recipe: probably mined, traded or a world drop)" : "");
        return new Recipes(args.item(), madeBy, usedIn, summary);
    }

    /** 一个方向(它由什么做出来 / 它被用在什么里):按类别分组,带机器/催化剂,最多回 {@value
     * #MAX_RECIPES_PER_DIRECTION} 条。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Recipe> group(IRecipeManager manager, IFocusFactory focusFactory, Item item,
                                      RecipeIngredientRole role) {
        IFocus<?> focus = focusFactory.createFocus(role, (IIngredientType) VanillaTypes.ITEM_STACK,
                new ItemStack(item));
        List<IFocus<?>> focuses = List.<IFocus<?>>of(focus);
        List<IRecipeCategory<?>> categories =
                manager.createRecipeCategoryLookup().limitFocus(focuses).get().toList();
        List<Recipe> out = new ArrayList<>();
        for (IRecipeCategory<?> category : categories) {
            if (out.size() >= MAX_RECIPES_PER_DIRECTION) {
                break;
            }
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
                if (out.size() >= MAX_RECIPES_PER_DIRECTION) {
                    break;
                }
                IIngredientSupplier supplier;
                try {
                    supplier = manager.getRecipeIngredients((IRecipeCategory) category, recipe);
                } catch (RuntimeException unreadable) {
                    continue;
                }
                out.add(new Recipe(category.getTitle().getString(), machine,
                        describeEach(supplier.getIngredients(RecipeIngredientRole.INPUT)),
                        describeEach(supplier.getIngredients(RecipeIngredientRole.OUTPUT)),
                        RecipeSummary.primary(describeEach(
                                supplier.getIngredients(RecipeIngredientRole.CATALYST)))));
            }
        }
        return out;
    }

    /** 类别的主要机器/催化剂;JEI 还没注册或查询失败就给空串(整段照报,不因它中断)。 */
    private static String catalysts(IRecipeManager manager, IRecipeCategory<?> category) {
        try {
            List<ItemStack> stacks = manager.createRecipeCatalystLookup(category.getRecipeType())
                    .getItemStack().toList();
            return RecipeSummary.primary(stackIds(stacks));
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    /** 每个原料/产物一个短串;非物品的按 toString 兜底。 */
    private static List<String> describeEach(List<ITypedIngredient<?>> ingredients) {
        List<String> each = new ArrayList<>();
        if (ingredients == null) {
            return each;
        }
        for (ITypedIngredient<?> typed : ingredients) {
            Optional<ItemStack> stack = typed.getItemStack();
            each.add(stack.isPresent() ? stackId(stack.get()) : String.valueOf(typed.getIngredient()));
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
            ids.add(stackId(stack));
        }
        return ids;
    }

    /** 一个 ItemStack 写成 {@code id} 或 {@code id xN}。 */
    private static String stackId(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return stack.getCount() > 1 ? id + " x" + stack.getCount() : id;
    }
}
