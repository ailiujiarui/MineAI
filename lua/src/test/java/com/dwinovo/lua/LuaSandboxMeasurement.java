package com.dwinovo.lua;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 复用 LuaSandboxTest 的场景与公开 API;每条记录落盘后才核验,异常就停止本轮。 */
public final class LuaSandboxMeasurement {
    private static final int REPEATS = 10;
    private static final long WATCHDOG_SECONDS = 5;
    private static final LuaSandbox.Limits LIMITS = new LuaSandbox.Limits(
            1_000_000, 10_000_000, 1 << 20, Duration.ofSeconds(2));

    private record Scenario(String id, String code, LuaSandbox.Ending expected, String boundary) {}
    private record Sample(boolean passed, double elapsed, Double interruptElapsed) {}

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("normal_control", "local n = 0 for i = 1, 100 do n = n + i end "
                    + "assert(n == 5050 and string.rep('x', 2) == 'xx') return 42",
                    LuaSandbox.Ending.FINISHED, "none"),
            new Scenario("lua_error_control", "error('ordinary Lua error')", LuaSandbox.Ending.ERROR, "none"),
            new Scenario("infinite_loop", "while true do end", LuaSandbox.Ending.SLICE, "vm_budget"),
            new Scenario("pcall_infinite_loop", "while true do pcall(function() while true do end end) end",
                    LuaSandbox.Ending.SLICE, "vm_budget"),
            new Scenario("string_doubling", "local s = 'x' for i = 1, 40 do s = s .. s end return #s",
                    LuaSandbox.Ending.STRINGS, "vm_budget"),
            new Scenario("string_rep_huge", "return string.rep('x', 2000000000)",
                    LuaSandbox.Ending.STRINGS, "vm_budget"),
            new Scenario("host_function_overwrite", "function host.work() return 'replaced' end",
                    LuaSandbox.Ending.ERROR, "fixed_host_binding"),
            unavailable("os_access", "os", "os.execute('sandbox_probe')"),
            unavailable("io_access", "io", "io.open('sandbox_probe', 'w')"),
            unavailable("luajava_access", "luajava", "luajava.bindClass('java.lang.System')"),
            new Scenario("interrupt_busy", "started() while true do pcall(function() while true do end end) end",
                    LuaSandbox.Ending.INTERRUPTED, "external_interrupt"),
            new Scenario("interrupt_host_wait", "while true do pcall(waiting) end",
                    LuaSandbox.Ending.INTERRUPTED, "external_interrupt"),
            new Scenario("wall_clock", "nap() return 'never'", LuaSandbox.Ending.WALL_CLOCK, "vm_budget"),
            new Scenario("total_instructions", "while true do for i = 1, 500 do end tick() end",
                    LuaSandbox.Ending.INSTRUCTIONS, "vm_budget"));

    private static Scenario unavailable(String id, String name, String call) {
        return new Scenario(id, "local ok = pcall(function() " + call + " end) "
                + "return _G['" + name + "'] == nil and not ok", LuaSandbox.Ending.FINISHED,
                "capability_unavailable");
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        Path raw = directory.resolve("sandbox-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".jsonl");
        System.out.println("Raw data: " + raw.toAbsolutePath());
        Map<String, List<Sample>> samples = new LinkedHashMap<>();
        try (BufferedWriter writer = Files.newBufferedWriter(raw)) {
            Map<String, Object> environment = new LinkedHashMap<>();
            environment.put("record_type", "environment");
            environment.put("recorded_at", Instant.now().toString());
            environment.put("os", System.getProperty("os.name"));
            environment.put("os_version", System.getProperty("os.version"));
            environment.put("architecture", System.getProperty("os.arch"));
            environment.put("processors", Runtime.getRuntime().availableProcessors());
            environment.put("jvm", System.getProperty("java.vm.name"));
            environment.put("java_version", System.getProperty("java.version"));
            environment.put("java_vendor", System.getProperty("java.vendor"));
            environment.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
            environment.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
            environment.put("repeats", REPEATS);
            environment.put("watchdog_seconds", WATCHDOG_SECONDS);
            environment.put("elapsed_start_definition", "System.nanoTime immediately before LuaSandbox.start; "
                    + "end is entry to completion callback; includes startup, compilation and scheduling");
            environment.put("interrupt_elapsed_start_definition", "System.nanoTime immediately before "
                    + "Running.interrupt, after started latch; same completion callback end");
            write(writer, environment);
            System.out.println(json(environment));
            write(writer, Map.of("record_type", "applicability", "scenario_id", "unauthorized_world_call",
                    "ending", "NOT_APPLICABLE", "expected_ending", "NOT_APPLICABLE", "blocked", false,
                    "reason", "Pure JVM: no Minecraft world or permission layer; no authorization claim"));
            for (Scenario scenario : SCENARIOS) {
                List<Sample> measured = new ArrayList<>();
                samples.put(scenario.id(), measured);
                for (int repetition = 1; repetition <= REPEATS; repetition++) {
                    Sample sample = measure(writer, scenario, repetition);
                    measured.add(sample);
                    if (!sample.passed()) {
                        throw new IllegalStateException("Measurement failed: " + scenario.id() + " #" + repetition
                                + "; raw data retained at " + raw);
                    }
                }
            }
        } finally {
            samples.forEach((id, values) -> {
                System.out.printf(Locale.ROOT, "%s: %d/%d; start-to-completion %s%n", id,
                        values.stream().filter(Sample::passed).count(), values.size(),
                        stats(values.stream().map(Sample::elapsed).toList()));
                List<Double> interrupts = values.stream().map(Sample::interruptElapsed)
                        .filter(v -> v != null).toList();
                if (!interrupts.isEmpty()) {
                    System.out.println("  interrupt-to-completion " + stats(interrupts));
                }
            });
        }
    }

    private static Sample measure(BufferedWriter writer, Scenario scenario, int repetition) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<LuaSandbox.Outcome> outcome = new AtomicReference<>();
        AtomicLong endedAt = new AtomicLong();
        AtomicBoolean refused = new AtomicBoolean();
        boolean interrupt = scenario.boundary().equals("external_interrupt");
        LuaSandbox.Limits limits = switch (scenario.id()) {
            case "interrupt_busy", "interrupt_host_wait" -> new LuaSandbox.Limits(
                    Long.MAX_VALUE, Long.MAX_VALUE, 1 << 20, Duration.ofSeconds(2));
            case "wall_clock" -> new LuaSandbox.Limits(1_000_000, 10_000_000, 1 << 20, Duration.ofMillis(50));
            case "total_instructions" -> new LuaSandbox.Limits(5_000, 40_000, 1 << 20, Duration.ofSeconds(2));
            default -> LIMITS;
        };
        LuaSandbox sandbox = LuaSandbox.builder(limits)
                .function("host", "work", a -> "original")
                .redefined((table, key) -> {
                    refused.set("host".equals(table) && "work".equals(key));
                    return new LuaSandbox.ScriptError("fixed host binding: " + table + "." + key);
                })
                .function("started", a -> { started.countDown(); return null; })
                .function("waiting", a -> { started.countDown(); Thread.sleep(60_000); return null; })
                .function("nap", a -> { Thread.sleep(100); return null; })
                .function("tick", a -> null).build();
        Long interruptedAt = null;
        String failure = null;
        long start = System.nanoTime();
        LuaSandbox.Running running = sandbox.start(scenario.id(), scenario.code(), List.of(), result -> {
            endedAt.set(System.nanoTime());
            outcome.set(result);
            done.countDown();
        });
        if (interrupt) {
            if (!started.await(WATCHDOG_SECONDS, TimeUnit.SECONDS)) {
                failure = "START_LATCH_TIMEOUT";
            } else {
                interruptedAt = System.nanoTime();
                running.interrupt();
            }
        }
        if (failure == null && !done.await(WATCHDOG_SECONDS, TimeUnit.SECONDS)) {
            failure = "COMPLETION_WATCHDOG_TIMEOUT";
        }
        long observedAt = System.nanoTime();
        if (failure != null) {
            running.interrupt();
            done.await(1, TimeUnit.SECONDS);
        }
        LuaSandbox.Outcome result = outcome.get();
        long end = endedAt.get() == 0 ? observedAt : endedAt.get();
        double elapsed = (end - start) / 1_000_000.0;
        Double interruptElapsed = interruptedAt == null || endedAt.get() == 0 ? null
                : (end - interruptedAt) / 1_000_000.0;
        boolean vmBlocked = result != null && switch (result.ending()) {
            case SLICE, INSTRUCTIONS, STRINGS, WALL_CLOCK, STACK -> true;
            default -> false;
        };
        boolean blocked = switch (scenario.boundary()) {
            case "vm_budget" -> vmBlocked;
            case "fixed_host_binding" -> refused.get();
            case "capability_unavailable" -> result != null && result.finished() && Boolean.TRUE.equals(result.value());
            case "external_interrupt" -> result != null && result.ending() == LuaSandbox.Ending.INTERRUPTED;
            default -> false;
        };
        boolean passed = failure == null && result != null && result.ending() == scenario.expected()
                && (scenario.boundary().equals("none") || blocked)
                && (!scenario.id().equals("normal_control") || Long.valueOf(42).equals(result.value()))
                && (!interrupt || interruptElapsed != null && interruptElapsed >= 0 && interruptElapsed < 1000);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("record_type", "sample");
        record.put("scenario_id", scenario.id());
        record.put("repetition", repetition);
        record.put("code", scenario.code());
        record.put("ending", failure == null && result != null ? result.ending().name() : "HARNESS_FAILURE");
        record.put("expected_ending", scenario.expected().name());
        record.put("boundary", scenario.boundary());
        record.put("blocked", blocked);
        record.put("vm_sandbox_blocked", vmBlocked);
        record.put("passed", passed);
        record.put("elapsed_ms", elapsed);
        record.put("elapsed_start_definition", "before_LuaSandbox.start_to_completion_callback");
        record.put("interrupt_elapsed_ms", interruptElapsed);
        record.put("interrupt_start_definition", interrupt ? "after_started_latch_before_Running.interrupt" : null);
        record.put("failure", failure);
        record.put("observed_outcome", result == null ? null : result.ending().name());
        record.put("line", result == null ? null : result.line());
        record.put("message", result == null ? null : result.message());
        record.put("value", result == null ? null : result.value());
        record.put("instructions_per_slice", limits.instructionsPerSlice());
        record.put("instructions", limits.instructions());
        record.put("string_bytes", limits.stringBytes());
        record.put("wall_clock_ms", limits.wallClock().toMillis());
        write(writer, record);
        return new Sample(passed, elapsed, interruptElapsed);
    }

    private static String stats(List<Double> values) {
        if (values.isEmpty()) return "no samples";
        List<Double> sorted = values.stream().sorted().toList();
        return String.format(Locale.ROOT, "mean=%.3f ms p95=%.3f ms max=%.3f ms (nearest-rank p95)",
                values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                sorted.get((int) Math.ceil(sorted.size() * .95) - 1), sorted.get(sorted.size() - 1));
    }

    private static void write(BufferedWriter writer, Map<String, ?> record) throws IOException {
        writer.write(json(record));
        writer.newLine();
        writer.flush();
    }

    // 只序列化本入口生成的标量、列表与字段表,不引入额外 JSON 依赖。
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            return "{" + String.join(",", map.entrySet().stream()
                    .map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (value instanceof List<?> list) {
            return "[" + String.join(",", list.stream().map(LuaSandboxMeasurement::json).toList()) + "]";
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 32) quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else quoted.append(c);
                }
            }
        }
        return quoted.append('"').toString();
    }
}
