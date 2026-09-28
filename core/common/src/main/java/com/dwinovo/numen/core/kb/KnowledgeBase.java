package com.dwinovo.numen.core.kb;

import com.dwinovo.numen.core.Constants;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 整合包自己的离线知识库:把包里已有的说明文字读成可检索的片段。
 *
 * <p>同伴要"看懂"某个模组的机器或某条任务,本来只能在主人的客户端上点开指南按钮——那按钮只在
 * 客户端存在,专用服务端(或没有主人的时候)够不着。这里的取法完全不同:包里的书与任务文本
 * 早就躺在服务器能读到的地方,索引一次就够了,不联网、不写盘。
 *
 * <h2>索引什么</h2>
 * <ul>
 *   <li><b>GuideME 指南</b>——每个模组 jar 里 {@code assets/<mod>/ae2guide/**.md}。走服务端资源包
 *       枚举({@code PackResources#listResources(CLIENT_RESOURCES, …)}),开发目录与成品 jar 一视同仁。
 *       同一页有 {@code _zh_cn} 译文时优先取译文,否则取默认英文。</li>
 *   <li><b>Patchouli 手册</b>——{@code assets|data/<mod>/patchouli_books/**.json} 里的
 *       name/title/text/description 等文字字段。</li>
 *   <li><b>FTB Quests</b>——服务器运行目录下 {@code config/ftbquests/quests/**} 的 {@code .snbt/.json},
 *       取其中的标题、描述、任务与奖励文字。</li>
 * </ul>
 *
 * <h2>界限</h2>
 * 首次查询时才建索引({@link #search} / {@link #isEmpty()} / {@link #sampleTerm()}),建完即冻结:
 * 内存有上限(片段数、单篇字数、单文件读取字节数都封顶),不会因为一个超大整合包把堆吃光。资源包在
 * 运行中重载后索引不会自动刷新——这是刻意的取舍:索引是只读快照,重载后重建由持有者决定。
 */
public final class KnowledgeBase {

    /** GuideME 指南在资源包里的目录名。 */
    private static final String GUIDE_DIR = "ae2guide";
    /** Patchouli 手册在资源包里的目录名。 */
    private static final String PATCHOULI_DIR = "patchouli_books";

    // 三道上限:片段总数、单片段字数、单篇文档保留字数。整合包的书再多也压得住。
    private static final int MAX_CHUNKS = 3000;
    private static final int CHUNK_CHARS = 600;
    private static final int MAX_DOC_CHARS = 12000;
    /** 一次索引最多读这么多文件(任务文件可能很多,挡住病态整合包)。 */
    private static final int MAX_FILES = 6000;
    /** 单个文件最多读这么多字节。 */
    private static final int MAX_READ_BYTES = 2_000_000;
    /** 回执里每个片段截多长给模型看。 */
    private static final int SNIPPET_CHARS = 320;

    /** JSON 里认作"给人看的文字"的键;其余结构继续下钻找它们。 */
    private static final Set<String> TEXT_KEYS = Set.of(
            "title", "name", "text", "description", "subtitle", "desc", "entry", "chapter");
    private static final Pattern QUOTED = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern PATCHOULI_CODE = Pattern.compile("\\$\\([^)]*\\)");
    private static final Pattern COLOR_CODE = Pattern.compile("&[0-9a-fk-orA-FK-OR]");
    private static final Pattern BRACE = Pattern.compile("\\{[^{}]*}");

    private static volatile KnowledgeBase instance;

    private final MinecraftServer server;
    private volatile List<Chunk> chunks;

    private int guideDocs;
    private int patchouliDocs;
    private int questDocs;

    private KnowledgeBase(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 这台服务器对应的知识库,第一次问它才去建索引。同一进程里服务器换了一具就重开一份
     * (gametest 会反复起停服务端),否则沿用。
     */
    public static KnowledgeBase get(MinecraftServer server) {
        KnowledgeBase kb = instance;
        if (kb == null || kb.server != server) {
            synchronized (KnowledgeBase.class) {
                if (instance == null || instance.server != server) {
                    instance = new KnowledgeBase(server);
                }
                kb = instance;
            }
        }
        return kb;
    }

    /** 索引里有没有东西;空的包(干净测试环境)直接说没有。 */
    public boolean isEmpty() {
        buildIfNeeded();
        return chunks.isEmpty();
    }

    /**
     * 从索引里取一个确实出现过的词,给用例当探针用:拿它去搜一定命中。索引为空回 {@code null}。
     */
    public String sampleTerm() {
        buildIfNeeded();
        for (Chunk c : chunks) {
            for (String w : c.text.split("[^\\p{L}\\p{N}_]+")) {
                if (w.length() >= 4) return w;
            }
        }
        return null;
    }

    /**
     * 不分大小写的多词检索:每个词都要在片段里出现过才算命中,得分是各词出现次数之和,标题/来源里也出现的词额外加权。
     * 空的查询或空索引直接给空表——工具据此回一句干净的"没有"。
     *
     * @param query 查询词,空白分隔;中文整体当一个词
     * @param limit 最多回几个片段;夹在 1..20
     */
    public List<Hit> search(String query, int limit) {
        buildIfNeeded();
        if (query == null || query.isBlank() || chunks.isEmpty()) return List.of();
        List<String> terms = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!t.isBlank()) terms.add(t);
        }
        if (terms.isEmpty()) return List.of();

        List<Hit> scored = new ArrayList<>();
        for (Chunk c : chunks) {
            String lower = c.text.toLowerCase(Locale.ROOT);
            int score = 0;
            boolean all = true;
            for (String t : terms) {
                int n = count(lower, t);
                if (n == 0) {
                    all = false;
                    break;
                }
                score += n;
            }
            if (!all) continue;
            String src = c.source.toLowerCase(Locale.ROOT);
            for (String t : terms) {
                if (src.contains(t)) score += 2;
            }
            scored.add(new Hit(c.source, snippet(c.text, lower, terms), score));
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        int cap = Math.min(scored.size(), Math.clamp(limit, 1, 20));
        return List.copyOf(scored.subList(0, cap));
    }

    // ---- 建索引 ----

    private void buildIfNeeded() {
        if (chunks != null) return;
        synchronized (this) {
            if (chunks != null) return;
            List<Chunk> out = new ArrayList<>();
            long start = System.currentTimeMillis();
            try {
                ResourceManager rm = server.getResourceManager();
                if (rm != null) {
                    indexGuides(rm, out);
                    indexPatchouli(rm, out);
                }
                indexQuests(out);
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-kb] 建索引时出错,已建部分保留: {}", ex.toString());
            }
            chunks = List.copyOf(out);
            Constants.LOG.info("[numen-kb] 索引 {} 篇文档 / {} 个片段(指南 {}、手册 {}、任务 {}),用时 {} ms",
                    guideDocs + patchouliDocs + questDocs, chunks.size(),
                    guideDocs, patchouliDocs, questDocs, System.currentTimeMillis() - start);
        }
    }

    /** GuideME:{@code ae2guide/**.md};同一逻辑页优先 {@code _zh_cn},其次默认语言。 */
    private void indexGuides(ResourceManager rm, List<Chunk> out) {
        Map<ResourceLocation, IoSupplier<InputStream>> found = new LinkedHashMap<>();
        forEachAsset(rm, GUIDE_DIR, (loc, sup) -> {
            if (loc.getPath().endsWith(".md")) found.putIfAbsent(loc, sup);
        });

        // 逻辑页 = 去掉语言子目录后的相对路径;按 zh_cn(0) < 默认(1) < 其它语言(2) 挑一版。
        Map<String, GuidePick> best = new HashMap<>();
        for (Map.Entry<ResourceLocation, IoSupplier<InputStream>> e : found.entrySet()) {
            GuidePath gp = parseGuidePath(e.getKey().getPath());
            if (gp == null) continue;
            String key = e.getKey().getNamespace() + "|" + gp.page;
            GuidePick prev = best.get(key);
            if (prev == null || gp.rank < prev.rank) {
                best.put(key, new GuidePick(gp.rank, e.getKey(), e.getValue()));
            }
        }

        for (Map.Entry<String, GuidePick> e : best.entrySet()) {
            String page = e.getKey().substring(e.getKey().indexOf('|') + 1);
            String pageName = page.endsWith(".md") ? page.substring(0, page.length() - 3) : page;
            String label = "guide:" + e.getValue().loc.getNamespace() + " | " + pageName;
            if (addDoc(out, label, read(e.getValue().supplier))) guideDocs++;
        }
    }

    /** Patchouli:{@code patchouli_books/**.json},取文字字段。 */
    private void indexPatchouli(ResourceManager rm, List<Chunk> out) {
        for (PackType type : PackType.values()) {
            forEachPack(rm, pack -> {
                for (String ns : pack.getNamespaces(type)) {
                    pack.listResources(type, ns, PATCHOULI_DIR, (loc, sup) -> {
                        if (!loc.getPath().endsWith(".json")) return;
                        String text = extractJson(read(sup));
                        String rel = loc.getPath().substring(PATCHOULI_DIR.length() + 1);
                        if (addDoc(out, "patchouli:" + loc.getNamespace() + " | " + rel, text)) {
                            patchouliDocs++;
                        }
                    });
                }
            });
        }
    }

    /** FTB Quests:服务器运行目录下 {@code config/ftbquests/quests/**} 的 snbt/json。 */
    private void indexQuests(List<Chunk> out) {
        Path root = server.getServerDirectory().resolve("config").resolve("ftbquests").resolve("quests");
        if (!Files.isDirectory(root)) return;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".snbt") || n.endsWith(".json");
                    })
                    .sorted()
                    .limit(MAX_FILES)
                    .toList();
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-kb] 列举任务文件失败: {}", ex.toString());
            return;
        }
        for (Path file : files) {
            String raw = readFile(file);
            if (raw == null) continue;
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            String text = name.endsWith(".json") ? extractJson(raw) : extractSnbt(raw);
            String rel = root.relativize(file).toString().replace('\\', '/');
            if (addDoc(out, "quests | " + rel, text)) questDocs++;
        }
    }

    // ---- 资源包枚举 ----

    /** 资源包(含各模组 jar)里 {@code assets/<namespace>/<dir>/**} 的每一项。 */
    private static void forEachAsset(ResourceManager rm, String dir, ResourceOutput out) {
        forEachPack(rm, pack -> {
            for (String ns : pack.getNamespaces(PackType.CLIENT_RESOURCES)) {
                pack.listResources(PackType.CLIENT_RESOURCES, ns, dir, out::accept);
            }
        });
    }

    private static void forEachPack(ResourceManager rm, PackConsumer consumer) {
        try (Stream<PackResources> packs = rm.listPacks()) {
            for (PackResources pack : (Iterable<PackResources>) packs::iterator) {
                consumer.accept(pack);
            }
        } catch (RuntimeException ex) {
            Constants.LOG.warn("[numen-kb] 枚举资源包失败: {}", ex.toString());
        }
    }

    // ---- 文本提取 ----

    /** 读一个资源包条目;缺失或读坏回 null。 */
    private static String read(IoSupplier<InputStream> supplier) {
        if (supplier == null) return null;
        try (InputStream in = supplier.get()) {
            return new String(in.readNBytes(MAX_READ_BYTES), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static String readFile(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_READ_BYTES) {
                bytes = java.util.Arrays.copyOf(bytes, MAX_READ_BYTES);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** JSON 里 name/title/text 等文字字段,一行一条;解析不了就当没有。 */
    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            collectJson(JsonParser.parseString(raw), sb);
            return sb.isEmpty() ? null : sb.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void collectJson(JsonElement element, StringBuilder sb) {
        if (element == null) return;
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                if (TEXT_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                    appendStrings(e.getValue(), sb);
                } else {
                    collectJson(e.getValue(), sb);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectJson(child, sb);
        }
    }

    private static void appendStrings(JsonElement element, StringBuilder sb) {
        if (element == null) return;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String line = cleanLine(element.getAsString());
            if (!line.isBlank()) sb.append(line).append('\n');
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) appendStrings(child, sb);
        } else if (element.isJsonObject()) {
            collectJson(element, sb);
        }
    }

    /** SNBT 取引号里的字符串——标题、描述、任务与奖励多半都在引号里。 */
    private static String extractSnbt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        Matcher m = QUOTED.matcher(raw);
        while (m.find()) {
            String s = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
            String line = cleanLine(s);
            if (!line.isBlank()) sb.append(line).append('\n');
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 抹掉 Patchouli 的 {@code $(…)} 格式、{@code &x} 颜色码与花括号占位,留下可读文字。 */
    private static String cleanLine(String s) {
        String out = PATCHOULI_CODE.matcher(s).replaceAll(" ");
        out = COLOR_CODE.matcher(out).replaceAll(" ");
        out = BRACE.matcher(out).replaceAll(" ");
        return out.strip();
    }

    // ---- 切分 ----

    /** 把一篇文档切成片段并收进 {@code out};空文档或到上限时什么都不做,回 false。 */
    private boolean addDoc(List<Chunk> out, String source, String text) {
        if (out.size() >= MAX_CHUNKS || text == null || text.isBlank()) return false;
        String doc = text.strip();
        if (doc.length() > MAX_DOC_CHARS) doc = doc.substring(0, MAX_DOC_CHARS);
        boolean added = false;
        for (String part : chunk(doc)) {
            if (out.size() >= MAX_CHUNKS) break;
            if (part.isBlank()) continue;
            out.add(new Chunk(source, part));
            added = true;
        }
        return added;
    }

    /** 按行攒片段,一行太长就硬切,保证每片不超过 {@link #CHUNK_CHARS}。 */
    private static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.length() > CHUNK_CHARS) {
                if (!buf.isEmpty()) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                for (int i = 0; i < line.length(); i += CHUNK_CHARS) {
                    out.add(line.substring(i, Math.min(line.length(), i + CHUNK_CHARS)));
                }
                continue;
            }
            if (buf.length() + line.length() + 1 > CHUNK_CHARS && !buf.isEmpty()) {
                out.add(buf.toString());
                buf.setLength(0);
            }
            if (!buf.isEmpty()) buf.append('\n');
            buf.append(line);
        }
        if (!buf.isEmpty()) out.add(buf.toString());
        return out;
    }

    /** 片段里第一次命中任一词的上下文窗口,便于模型直接读。 */
    private static String snippet(String text, String lower, List<String> terms) {
        int at = -1;
        for (String t : terms) {
            int i = lower.indexOf(t);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) at = 0;
        int from = Math.max(0, at - 80);
        int to = Math.min(text.length(), from + SNIPPET_CHARS);
        String body = text.substring(from, to).strip();
        return (from > 0 ? "…" : "") + body + (to < text.length() ? "…" : "");
    }

    private static int count(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
            if (n > 1000) break; // 单片段里出现上千次已无区分度,封顶免得退化
        }
        return n;
    }

    /**
     * {@code ae2guide/foo.md} / {@code ae2guide/_zh_cn/foo.md} 拆成"逻辑页 + 语言优先级"。
     * 语言优先级:zh_cn=0、默认=1、其它语言=2。
     */
    private static GuidePath parseGuidePath(String path) {
        String rel = path.startsWith(GUIDE_DIR + "/") ? path.substring(GUIDE_DIR.length() + 1) : path;
        int rank = 1;
        if (rel.startsWith("_")) {
            int slash = rel.indexOf('/');
            if (slash > 0) {
                String lang = rel.substring(1, slash).toLowerCase(Locale.ROOT);
                rel = rel.substring(slash + 1);
                rank = lang.equals("zh_cn") ? 0 : 2;
            }
        }
        return rel.isBlank() ? null : new GuidePath(rel, rank);
    }

    /** 一条命中:来源标签 + 片段文字 + 得分。 */
    public record Hit(String source, String text, int score) {}

    private record Chunk(String source, String text) {}

    private record GuidePath(String page, int rank) {}

    private record GuidePick(int rank, ResourceLocation loc, IoSupplier<InputStream> supplier) {}

    @FunctionalInterface
    private interface PackConsumer {
        void accept(PackResources pack);
    }

    @FunctionalInterface
    private interface ResourceOutput {
        void accept(ResourceLocation location, IoSupplier<InputStream> supplier);
    }
}
