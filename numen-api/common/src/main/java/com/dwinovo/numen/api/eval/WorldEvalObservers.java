package com.dwinovo.numen.api.eval;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicitly activated world observers. Content owns the facts; the evaluator owns the score. */
public final class WorldEvalObservers {
    public interface Observer {
        JsonObject observe(NumenPlayer body);
        default void started(NumenPlayer body) {}
        default void tick(NumenPlayer body) {}
        default void unavailable(long tick) {}
        default JsonObject unavailableSnapshot() { return null; }
        default void itemUseOnStarted(ServerLevel level, Player actor, BlockPos pos, BlockState previous, ItemStack item) {}
        default void itemUsedOn(ServerLevel level, Player actor, BlockPos pos, BlockState previous, ItemStack item, int countAfter) {}
        default void portalTravelled(NumenPlayer body, ServerLevel source, BlockPos entry, Entity arrived) {}
        default void broken(NumenPlayer body, BlockPos pos, BlockState previous) {}
        default void consumed(NumenPlayer body, ItemStack meal, int foodBefore, float saturationBefore) {}
        default void dimensionChanged(NumenPlayer body) {}
        default void hurt(NumenPlayer body, net.minecraft.world.damagesource.DamageSource source, float amount) {}
        default void died(NumenPlayer body) {}
        default void entityDamaged(Entity victim, net.minecraft.world.damagesource.DamageSource source,
                                   float healthBefore, float healthAfter, boolean accepted,
                                   String attributionOrigin, Entity originatingActor, long eventId) {}
        default void entityDied(net.minecraft.world.entity.LivingEntity victim,
                                net.minecraft.world.damagesource.DamageSource source,
                                net.minecraft.world.entity.LivingEntity credit) {}
        default void killCredit(net.minecraft.server.level.ServerPlayer actor, Entity victim,
                                net.minecraft.world.damagesource.DamageSource source) {}
        default void killStatistic(Player actor, net.minecraft.world.entity.LivingEntity victim, int before, int after) {}
        default void dragonFightEnded(net.minecraft.world.level.dimension.end.EndDragonFight fight,
                                      net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {}
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
        Observer observer = factory.get();
        if (active.putIfAbsent(body.getUUID(), observer) != null) {
            throw new IllegalStateException("World observation already started for this body");
        }
        observer.started(body);
    }

    public static JsonObject observe(NumenPlayer body) {
        Observer observer = active.get(body.getUUID());
        return observer == null ? null : observer.observe(body);
    }

    public static boolean isActive(NumenPlayer body) {
        return active.containsKey(body.getUUID());
    }

    /** Missing server subjects are observation gaps, distinct from an observed death. */
    public static void observationUnavailable(UUID body, long tick) {
        Observer observer = active.get(body);
        if (observer != null) observer.unavailable(tick);
    }

    /** Retained independent facts remain readable after a subject leaves the server player list. */
    public static JsonObject observeUnavailable(UUID body) {
        Observer observer = active.get(body);
        return observer == null ? null : observer.unavailableSnapshot();
    }

    /** Native interaction boundaries also expose other actors to explicitly active observers. */
    public static void itemUseOnStarted(ServerLevel level, Player actor, BlockPos pos, BlockState previous, ItemStack item) {
        for (Observer observer : active.values()) observer.itemUseOnStarted(level, actor, pos, previous, item);
    }

    public static void itemUsedOn(ServerLevel level, Player actor, BlockPos pos, BlockState previous, ItemStack item, int countAfter) {
        for (Observer observer : active.values()) observer.itemUsedOn(level, actor, pos, previous, item, countAfter);
    }

    /** Called only around the native portal processor's committed dimension-travel invocation. */
    public static void portalTravelled(NumenPlayer body, ServerLevel source, BlockPos entry, Entity arrived) {
        Observer observer = active.get(body.getUUID());
        if (observer != null) observer.portalTravelled(body, source, entry, arrived);
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

    /** Committed companion death, before recovery heals or removes its body. */
    public static void died(NumenPlayer body) {
        Observer observer = active.get(body.getUUID());
        if (observer != null) observer.died(body);
    }

    /** Native committed damage exposes actual health loss separately from the reported incoming amount. */
    public static void entityDamaged(Entity victim, net.minecraft.world.damagesource.DamageSource source,
                                     float healthBefore, float healthAfter, boolean accepted,
                                     String attributionOrigin, Entity originatingActor, long eventId) {
        for (Observer observer : active.values()) {
            observer.entityDamaged(victim, source, healthBefore, healthAfter, accepted, attributionOrigin, originatingActor, eventId);
        }
    }

    /** Native death and scoring boundaries retain the victim and credited actor, including other players. */
    public static void entityDied(net.minecraft.world.entity.LivingEntity victim,
                                  net.minecraft.world.damagesource.DamageSource source,
                                  net.minecraft.world.entity.LivingEntity credit) {
        for (Observer observer : active.values()) observer.entityDied(victim, source, credit);
    }

    public static void killCredit(net.minecraft.server.level.ServerPlayer actor, Entity victim,
                                  net.minecraft.world.damagesource.DamageSource source) {
        for (Observer observer : active.values()) observer.killCredit(actor, victim, source);
    }

    public static void killStatistic(Player actor, net.minecraft.world.entity.LivingEntity victim, int before, int after) {
        for (Observer observer : active.values()) observer.killStatistic(actor, victim, before, after);
    }

    /** Committed native fight completion; entity disappearance alone is never a completion event. */
    public static void dragonFightEnded(net.minecraft.world.level.dimension.end.EndDragonFight fight,
                                        net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
        for (Observer observer : active.values()) observer.dragonFightEnded(fight, dragon);
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
