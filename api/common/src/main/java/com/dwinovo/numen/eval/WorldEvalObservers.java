package com.dwinovo.numen.eval;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicitly activated world observers. Content owns the facts; the evaluator owns the score. */
public final class WorldEvalObservers {
    public interface Observer {
        JsonObject observe(NumenPlayer body);
        default void broken(NumenPlayer body, BlockPos pos, BlockState previous) {}
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

    /** Only committed body actions reach this entry; inactive bodies incur no observation work. */
    public static void blockBroken(NumenPlayer body, BlockPos pos, BlockState previous) {
        Observer observer = active.get(body.getUUID());
        if (observer != null) observer.broken(body, pos, previous);
    }

    public static void stop(UUID body) {
        active.remove(body);
    }
}
