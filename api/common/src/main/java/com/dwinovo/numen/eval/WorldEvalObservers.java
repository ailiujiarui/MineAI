package com.dwinovo.numen.eval;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicitly activated world observers. Content owns the facts; the evaluator owns the score. */
public final class WorldEvalObservers {
    public interface Observer {
        JsonObject observe(NumenPlayer body);
        default void tick(NumenPlayer body) {}
        default void broken(NumenPlayer body, BlockPos pos, BlockState previous) {}
        default void consumed(NumenPlayer body, ItemStack meal, int foodBefore, float saturationBefore) {}
        default void dimensionChanged(NumenPlayer body) {}
        default void hurt(NumenPlayer body, net.minecraft.world.damagesource.DamageSource source, float amount) {}
    }

    private static final Map<String, Supplier<Observer>> factories = new HashMap<>();
    private static final Map<UUID, Observer> active = new HashMap<>();

    private WorldEvalObservers() {}

    public static void register(String id, Supplier<Observer> factory) {
        if (id == null || id.isBlank() || factory == null) throw new IllegalArgumentException("Observer requires id and factory");
        if (factories.putIfAbsent(id, factory) != null) throw new IllegalArgumentException("Duplicate world observer: " + id);
    }

    /** Server-thread entry, before the objective is delivered in the isolated evaluation world. */
    public static void start(String id, NumenPlayer body) {
        Supplier<Observer> factory = factories.get(id);
        if (factory == null) throw new IllegalArgumentException("Unknown world observer: " + id);
        if (active.putIfAbsent(body.getUUID(), factory.get()) != null) {
            throw new IllegalStateException("World observation already started for this body");
        }
    }

    public static JsonObject observe(NumenPlayer body) {
        Observer observer = active.get(body.getUUID());
        return observer == null ? null : observer.observe(body);
    }

    public static boolean isActive(NumenPlayer body) {
        return active.containsKey(body.getUUID());
    }

    /** Server-thread observation after the body scheduler; inactive bodies perform no content scan. */
    public static void tick(NumenPlayer body) {
        Observer observer = active.get(body.getUUID());
        if (observer != null) observer.tick(body);
    }

    /** Committed loader dimension-change event; observers must not infer a portal route. */
    public static void dimensionChanged(NumenPlayer body) {
        Observer observer = active.get(body.getUUID());
        if (observer != null) observer.dimensionChanged(body);
    }

    /** Native damage accepted; amount is the reported value before mitigation. */
    public static void hurt(NumenPlayer body, net.minecraft.world.damagesource.DamageSource source, float amount) {
        Observer observer = active.get(body.getUUID());
        if (observer != null) observer.hurt(body, source, amount);
    }

    /** Native consumption committed; the meal retains its components from before the body ate it. */
    public static void foodConsumed(NumenPlayer body, ItemStack meal, int foodBefore, float saturationBefore) {
        Observer observer = active.get(body.getUUID());
        if (observer != null) observer.consumed(body, meal, foodBefore, saturationBefore);
    }

    /** Only committed body actions reach this entry; inactive bodies incur no observation work. */
    public static void blockBroken(NumenPlayer body, BlockPos pos, BlockState previous) {
        Observer observer = active.get(body.getUUID());
        if (observer != null) observer.broken(body, pos, previous);
    }

    public static void stop(UUID body) {
        active.remove(body);
    }
}
