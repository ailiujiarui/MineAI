package com.dwinovo.numen.core.route;

import java.util.HashMap;
import java.util.Map;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.BadValue;
import com.dwinovo.numen.sdk.Codec;

/**
 * 她手里的计划:只在算出它们的那一段程序里有效。一段程序({@code lua} 工具的一次调用)里的每次 API 调用带着同一个前缀的调用 id
 * ({@code <程序>#<第几次>}),计划按前缀记;换了一段程序再规划,上一段的计划一并作废——世界不会等她,隔了一轮的路不该照走。
 * 不落盘:重启之后没有计划,要走就再规划。一具身体一份。
 */
public final class Plans {

    private String program;
    private final Map<String, Plan> plans = new HashMap<>();
    private final Map<String, String> realms = new HashMap<>();
    private int next;

    Plans() {}

    public static Plans of(NumenPlayer her) {
        return her.state(Plans.class, Plans::new);
    }

    /**
     * 点名一份计划:{@code numen.move.go} 收 {@code numen.route.plan} 交回的那张表,认的只是它的 {@code id}。
     *
     * @param id 这一段程序里计划的编号
     */
    public record Ref(String id, java.util.Optional<Object> commit) {

        public Ref(String id) {
            this(id, java.util.Optional.empty());
        }

        /** 只认 id;附加数据原样运输,内容仅在服务端可信重放调用中解码。 */
        public static final Codec<Ref> CODEC = new Codec<>() {
            @Override
            public ScriptType type() {
                return new ScriptType.Named("Plan");
            }

            @Override
            public Ref decode(Object value) {
                if (value instanceof Map<?, ?> t && t.get("id") instanceof String id) {
                    return new Ref(id, java.util.Optional.ofNullable(t.get("commit")));
                }
                throw new BadValue("expected the Plan numen.route.plan returned (it has an id); got "
                        + BadValue.given(value));
            }

            @Override
            public Object encode(Ref value) {
                Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("id", value.id);
                value.commit.ifPresent(c -> out.put("commit", c));
                return out;
            }
        };
    }

    /** 一次调用属于哪一段程序:调用 id 里 {@code #} 前面那一截;不是程序里的调用(一行命令)就是它自己。 */
    public static String program(String callId) {
        int hash = callId.indexOf('#');
        return hash < 0 ? callId : callId.substring(0, hash);
    }

    /** 这一段程序里下一份计划的编号;换了一段程序,上一段的计划作废。 */
    public String nextId(String program) {
        if (!program.equals(this.program)) {
            this.program = program;
            plans.clear();
            realms.clear();
        }
        return "p" + (++next);
    }

    /** 记下 {@code plan}(它的编号由 {@link #nextId} 给)。 */
    public void put(String program, Plan plan) {
        if (program.equals(this.program)) {
            plans.put(plan.id(), plan);
        }
    }

    public void put(String program, Plan plan, String realm) {
        put(program, plan);
        if (program.equals(this.program)) {
            realms.put(plan.id(), realm);
        }
    }

    public boolean inRealm(String id, NumenPlayer her) {
        return realm(her).equals(realms.get(id));
    }

    public static String realm(NumenPlayer her) {
        return com.dwinovo.numen.entity.CompanionRegistry.get(her.getServer()).worldId() + "/"
                + her.serverLevel().dimension().location();
    }

    /** 这一段程序里编号为 {@code id} 的计划;别的程序的、作废了的、没有的为 null。 */
    public Plan get(String program, String id) {
        return program.equals(this.program) ? plans.get(id) : null;
    }
}
