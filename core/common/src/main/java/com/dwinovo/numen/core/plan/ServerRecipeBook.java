package com.dwinovo.numen.core.plan;

import com.dwinovo.numen.agent.plan.Counts;
import com.dwinovo.numen.agent.plan.Recipe;
import com.dwinovo.numen.agent.plan.RecipeBook;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.RecipeProbe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端配方表:{@link RecipeBook} 的活配方实现,从活的 {@code RecipeManager} 读出这具身体
 * 真能经现有工具走通的几条产线——合成(有序/无序)、熔炼/高炉/烟熏、切石、锻造。纯 JVM 规划层
 * 只查这张表,读法对齐 {@code QueryExtraOps.lookupRecipe} 与 {@code CraftOps.candidatesFor}:
 * 坏一条丢一条,绝不让一条坏配方杀掉整个规划。
 *
 * <p>一条配方的原料可能是标签(如 {@code #minecraft:logs});规划层只认单个物品 id,所以这里
 * 挑一个代表:{@link Counts} 里已经有的优先——手里攥着橡木原木,就把"原木"落成橡木原木,
 * 而不是标签里排第一的深色橡木。多条配方共用一个栏目时,代表一旦选定就按物品归并计数
 * (三格木板 + 两格木棍 → 木板 x3、木棍 x2)。
 *
 * <p>锻造在 1.21.1 的 {@link SmithingTransformRecipe} 上只有 is*Ingredient 三个判定、没有
 * ingredient getter,枚举不出原料;这里按字段名取 template/base/addition,取不到就不报这张配方,
 * 不拿半张配方冒充能做。
 */
public final class ServerRecipeBook implements RecipeBook {

    private final Map<String, List<Raw>> byOutput;
    private final Counts have;
    /** 同一物品可能被展开多次,选代表 + 归并只做一遍。 */
    private final Map<String, List<Recipe>> converted = new HashMap<>();

    private ServerRecipeBook(Map<String, List<Raw>> byOutput, Counts have) {
        this.byOutput = byOutput;
        this.have = have;
    }

    /** 按活配方表建一本书;{@code have} 用来在标签里挑手里已有的代表物品。 */
    public static ServerRecipeBook forLevel(ServerLevel level, Counts have) {
        Map<String, List<Raw>> byOutput = new HashMap<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            try {
                Raw raw = rawOf(holder.value(), level);
                if (raw != null) {
                    byOutput.computeIfAbsent(raw.output(), k -> new ArrayList<>()).add(raw);
                }
            } catch (RuntimeException broken) {
                Constants.LOG.debug("[numen-plan] 配方 {} 坏了,跳过: {}", holder.id(), broken.toString());
            }
        }
        byOutput.replaceAll((k, v) -> List.copyOf(v));
        return new ServerRecipeBook(Map.copyOf(byOutput), have);
    }

    @Override
    public List<Recipe> recipesFor(String item) {
        return converted.computeIfAbsent(item, key -> {
            List<Raw> raws = byOutput.getOrDefault(key, List.of());
            if (raws.isEmpty()) {
                return List.of();
            }
            List<Recipe> out = new ArrayList<>(raws.size());
            for (Raw raw : canonical(raws, have)) {
                Recipe recipe = raw.build(have);
                if (recipe != null) {
                    out.add(recipe);
                }
            }
            return List.copyOf(out);
        });
    }

    /**
     * 同一产物有多条做法时,只留最该用的那几条:手里的原料能直接喂的优先,再比单次产出
     * (原版木棍有木板和竹子两条,选能接着用的那条)。不分上下的并列原样留着,让规划层照旧把
     * "多条做法分不清"如实回报,而不是替模型硬选。
     */
    private static List<Raw> canonical(List<Raw> raws, Counts have) {
        if (raws.size() <= 1) {
            return raws;
        }
        int bestHeld = -1;
        int bestOut = -1;
        for (Raw r : raws) {
            int held = r.heldIngredients(have);
            int out = r.outputCount();
            if (held > bestHeld || (held == bestHeld && out > bestOut)) {
                bestHeld = held;
                bestOut = out;
            }
        }
        List<Raw> kept = new ArrayList<>();
        for (Raw r : raws) {
            if (r.heldIngredients(have) == bestHeld && r.outputCount() == bestOut) {
                kept.add(r);
            }
        }
        return List.copyOf(kept);
    }

    // ---- 配方表 -> 原始描述 ----

    private static Raw rawOf(net.minecraft.world.item.crafting.Recipe<?> r, ServerLevel level) {
        if (!RecipeProbe.usableIngredients(r)) {
            return null;   // 输入表为空/坏了的,规划层摆不进格子
        }
        if (r instanceof CraftingRecipe cr) {
            return crafting(cr, level);
        }
        if (r instanceof AbstractCookingRecipe cook) {
            return cooking(cook, level);
        }
        if (r instanceof StonecutterRecipe sc) {
            return single(sc.getIngredients(), "minecraft:stonecutter", sc, level);
        }
        if (r instanceof SmithingTransformRecipe sm) {
            return smithing(sm, level);
        }
        return null;
    }

    private static Raw crafting(CraftingRecipe cr, ServerLevel level) {
        // 产出依赖输入的配方(烟花、镶零件的装备)静态描述答不了,合成书同样不列。
        if (cr.isSpecial()) {
            return null;
        }
        ItemStack result = RecipeProbe.resultOf(cr, level.registryAccess());
        if (result.isEmpty()) {
            return null;
        }
        if (!fits(cr, 3, 3)) {
            return null;   // craft 工具最大摆 3x3,更大的模组台子走不了
        }
        String station = fits(cr, 2, 2) ? null : "minecraft:crafting_table";
        List<Ingredient> ings = cr.getIngredients();
        if (ings == null || ings.isEmpty()) {
            return null;
        }
        return new Raw(itemId(result.getItem()), result.getCount(), ings, station);
    }

    private static Raw cooking(AbstractCookingRecipe cook, ServerLevel level) {
        RecipeType<?> type = cook.getType();
        String station = type == RecipeType.SMELTING ? "minecraft:furnace"
                : type == RecipeType.BLASTING ? "minecraft:blast_furnace"
                : type == RecipeType.SMOKING ? "minecraft:smoker"
                : null;
        if (station == null) {
            return null;   // 篝火等不在这条产线上
        }
        ItemStack result = RecipeProbe.resultOf(cook, level.registryAccess());
        if (result.isEmpty()) {
            return null;
        }
        List<Ingredient> ings = cook.getIngredients();
        if (ings == null || ings.isEmpty()) {
            return null;
        }
        return new Raw(itemId(result.getItem()), result.getCount(), ings, station);
    }

    private static Raw single(List<Ingredient> ings, String station,
                              net.minecraft.world.item.crafting.Recipe<?> r, ServerLevel level) {
        ItemStack result = RecipeProbe.resultOf(r, level.registryAccess());
        if (result.isEmpty() || ings == null || ings.isEmpty()) {
            return null;
        }
        return new Raw(itemId(result.getItem()), result.getCount(), ings, station);
    }

    private static Raw smithing(SmithingTransformRecipe sm, ServerLevel level) {
        ItemStack result = RecipeProbe.resultOf(sm, level.registryAccess());
        if (result.isEmpty()) {
            return null;
        }
        Ingredient template = field(sm, "template");
        Ingredient base = field(sm, "base");
        Ingredient addition = field(sm, "addition");
        if (template == null || base == null || addition == null
                || template.isEmpty() || base.isEmpty() || addition.isEmpty()) {
            return null;
        }
        return new Raw(itemId(result.getItem()), result.getCount(),
                List.of(template, base, addition), "minecraft:smithing_table");
    }

    private static Ingredient field(SmithingTransformRecipe sm, String name) {
        try {
            Field f = SmithingTransformRecipe.class.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(sm);
            return v instanceof Ingredient ing ? ing : null;
        } catch (ReflectiveOperationException | RuntimeException missing) {
            return null;
        }
    }

    // ---- 挑代表物品 / 归并 ----

    /** 一条配方的原料表 + 产出;选代表、归并留到 {@link Raw#build}。 */
    private record Raw(String output, int outputCount, List<Ingredient> ingredients, String station) {

        Recipe build(Counts have) {
            Map<Item, Integer> tally = new LinkedHashMap<>();
            for (Ingredient ing : ingredients) {
                Item pick = pick(ing, have);
                if (pick != null) {
                    tally.merge(pick, 1, Integer::sum);
                }
            }
            if (tally.isEmpty()) {
                return null;
            }
            List<com.dwinovo.numen.agent.plan.ItemStack> items = new ArrayList<>(tally.size());
            for (Map.Entry<Item, Integer> e : tally.entrySet()) {
                items.add(com.dwinovo.numen.agent.plan.ItemStack.of(itemId(e.getKey()), e.getValue()));
            }
            return new Recipe(output, outputCount, items, station);
        }

        /** 这条配方的原料里,有几样是手里已经有的(用于多条做法之间选最顺的那条)。 */
        int heldIngredients(Counts have) {
            int held = 0;
            for (Ingredient ing : ingredients) {
                for (ItemStack s : ing.getItems()) {
                    if (s != null && have.count(itemId(s.getItem())) > 0) {
                        held++;
                        break;
                    }
                }
            }
            return held;
        }
    }

    /** 标签里挑一个代表:手里最多的优先,都不要就取第一个。 */
    private static Item pick(Ingredient ing, Counts have) {
        ItemStack[] options = ing.getItems();
        if (options == null || options.length == 0) {
            return null;
        }
        Item best = null;
        int bestCount = -1;
        for (ItemStack s : options) {
            if (s == null) {
                continue;
            }
            Item item = s.getItem();
            int n = have.count(itemId(item));
            if (best == null || n > bestCount) {
                best = item;
                bestCount = n;
            }
        }
        return best;
    }

    /** craft 工具的自己人判据:有序看宽高,无序看格子数,装得进 w x h。 */
    private static boolean fits(CraftingRecipe recipe, int w, int h) {
        if (recipe instanceof ShapedRecipe s) {
            return s.getWidth() <= w && s.getHeight() <= h;
        }
        int n = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            if (!ing.isEmpty()) {
                n++;
            }
        }
        return n <= w * h;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
