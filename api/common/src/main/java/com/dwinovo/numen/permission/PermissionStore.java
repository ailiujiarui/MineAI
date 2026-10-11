package com.dwinovo.numen.permission;

import com.dwinovo.numen.Constants;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 每主人一份:他手下每只同伴的{@link Mode 模式},和他自己写的那一层规则(deny、ask、allow 三张表)。
 * 存在主世界的存档数据里,文件名带主人 UUID。命令、"允许并记住"与以后的面板都只经这里的公开方法改它。
 *
 * <p>规则层是不可变的 {@link RuleSet},每次改动换一份新的:{@link Permission#gateFor} 在主线程取走引用,
 * 搜索线程拿着读,不会读到改了一半的表。每一行都经 {@link Rule#parse} 解析,存档里写错的行读档时记一条
 * 错误日志、不进表。
 */
public final class PermissionStore extends SavedData {

    private static final Codec<List<Rule>> TABLE = Rule.CODEC.listOf();

    /** 审计日志留多少行:只留最近这些,更早的丢。日志是给排障看的,不是账本。 */
    private static final int AUDIT_CAP = 256;

    private static final Codec<PermissionStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING)
                    .fieldOf("modes").forGetter(PermissionStore::modeNames),
            TABLE.optionalFieldOf("deny", List.of()).forGetter(s -> s.rules.deny()),
            TABLE.optionalFieldOf("ask", List.of()).forGetter(s -> s.rules.ask()),
            TABLE.optionalFieldOf("allow", List.of()).forGetter(s -> s.rules.allow()),
            Trusted.CODEC.listOf().optionalFieldOf("trusted", List.of()).forGetter(PermissionStore::trusted),
            Codec.STRING.listOf().optionalFieldOf("trusted_audit", List.of()).forGetter(PermissionStore::audit)
    ).apply(i, PermissionStore::new));

    private static final SavedData.Factory<PermissionStore> FACTORY = new SavedData.Factory<>(
            PermissionStore::new, PermissionStore::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, Mode> modes = new HashMap<>();
    private RuleSet rules = RuleSet.EMPTY;
    /** 跨会话的信任规则,连同谁在什么时候为准许的;不可变,改动换一份新的。 */
    private List<Trusted> trusted = List.of();
    /** 信任的加与撤,只追加、封顶,给人排障看。 */
    private final List<String> audit = new ArrayList<>();

    PermissionStore() {
    }

    private PermissionStore(Map<UUID, String> modeNames, List<Rule> deny, List<Rule> ask, List<Rule> allow,
                            List<Trusted> trusted, List<String> audit) {
        modeNames.forEach((uuid, name) -> modes.put(uuid, Mode.byName(name)));
        rules = new RuleSet(deny, ask, allow);
        this.trusted = List.copyOf(trusted);
        this.audit.addAll(audit);
    }

    public static PermissionStore of(MinecraftServer server, UUID owner) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_permissions_" + owner);
    }

    static PermissionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(error -> Constants.LOG.error("[numen-permission] 权限存档有读不懂的内容: {}", error))
                .orElseGet(PermissionStore::new);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    /** 这只同伴的模式;没设过是 {@link Mode#ASK}。 */
    public Mode modeOf(UUID companion) {
        return modes.getOrDefault(companion, Mode.ASK);
    }

    public void setMode(UUID companion, Mode mode) {
        modes.put(companion, mode);
        setDirty();
    }

    /** 主人这一层此刻的规则(不可变快照)。 */
    public RuleSet rules() {
        return rules;
    }

    /**
     * 在一张表末尾加一行。
     *
     * @return 加上了;这张表里已经有一模一样的一行时不重复加,返回 false
     */
    public boolean add(Verdict.Kind table, Rule rule) {
        List<Rule> rows = rules.table(table);
        if (rows.contains(rule)) {
            return false;
        }
        List<Rule> next = new ArrayList<>(rows);
        next.add(rule);
        rules = rules.withTable(table, next);
        setDirty();
        return true;
    }

    /**
     * 删掉一张表里的第 {@code index} 行(从 0 数)。
     *
     * @throws IndexOutOfBoundsException 这张表没有这一行
     */
    public Rule remove(Verdict.Kind table, int index) {
        List<Rule> next = new ArrayList<>(rules.table(table));
        Rule removed = next.remove(index);
        rules = rules.withTable(table, next);
        setDirty();
        return removed;
    }

    /** 清空主人这一层的三张表;出厂层与各同伴的模式不动。 */
    public void reset() {
        rules = RuleSet.EMPTY;
        setDirty();
    }

    /** "允许并记住":每一行进 allow 表,已经有的不重复。 */
    public void remember(List<Rule> allow) {
        for (Rule rule : allow) {
            add(Verdict.Kind.ALLOW, rule);
        }
    }

    /** 跨会话的信任规则(不可变快照):{@link Gate} 在两层 allow 之后、出厂 ask 之前查它。 */
    public List<Trusted> trusted() {
        return trusted;
    }

    /** 信任的加与撤的流水,最近 {@value #AUDIT_CAP} 行。 */
    public List<String> audit() {
        return List.copyOf(audit);
    }

    /**
     * 记一条跨会话信任:主人这一次点了"允许并记住"。同一条规则已在册就不重复记。
     *
     * @param rule       要记的那一行(推法见 {@link ConsentItem#remembered})
     * @param grantedBy  谁允许的(主人 UUID)
     * @param companion  哪只同伴问的,给人看的
     * @return 记上了;同一条规则已经在册返回 false
     */
    public boolean rememberTrusted(Rule rule, UUID grantedBy, String companion) {
        for (Trusted entry : trusted) {
            if (entry.rule().equals(rule)) {
                return false;
            }
        }
        Trusted entry = new Trusted(rule, grantedBy, companion == null ? "" : companion, System.currentTimeMillis());
        List<Trusted> next = new ArrayList<>(trusted);
        next.add(entry);
        trusted = List.copyOf(next);
        note("trusted " + rule + " by " + grantedBy + " for " + entry.companionName());
        setDirty();
        return true;
    }

    /**
     * 撤销一条信任。主人收回"允许并记住"或者规则不再可靠时用。
     *
     * @return 撤掉了;这一行本来不在册返回 false
     */
    public boolean revokeTrusted(Rule rule) {
        List<Trusted> next = new ArrayList<>(trusted);
        boolean removed = next.removeIf(entry -> entry.rule().equals(rule));
        if (!removed) {
            return false;
        }
        trusted = List.copyOf(next);
        note("revoked " + rule);
        setDirty();
        return true;
    }

    /** 记一行审计:留在表里,也进日志。 */
    private void note(String line) {
        Constants.LOG.info("[numen-trust] {}", line);
        audit.add(line);
        while (audit.size() > AUDIT_CAP) {
            audit.remove(0);
        }
    }

    /**
     * 一条跨会话的信任规则:哪一行、谁在哪时为准许的、哪只同伴问的。{@link Rule} 自己就是它的存档原文。
     */
    public record Trusted(Rule rule, UUID grantedBy, String companionName, long grantedAtMillis) {

        public static final Codec<Trusted> CODEC = RecordCodecBuilder.create(i -> i.group(
                Rule.CODEC.fieldOf("rule").forGetter(Trusted::rule),
                UUIDUtil.STRING_CODEC.optionalFieldOf("granted_by", new UUID(0L, 0L)).forGetter(Trusted::grantedBy),
                Codec.STRING.optionalFieldOf("companion", "").forGetter(Trusted::companionName),
                Codec.LONG.optionalFieldOf("granted_at", 0L).forGetter(Trusted::grantedAtMillis)
        ).apply(i, Trusted::new));

        public Trusted {
            companionName = companionName == null ? "" : companionName;
        }
    }

    private Map<UUID, String> modeNames() {
        Map<UUID, String> out = new HashMap<>();
        modes.forEach((uuid, mode) -> out.put(uuid, mode.name().toLowerCase(Locale.ROOT)));
        return out;
    }
}
