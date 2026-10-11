package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.memory.NoteBook;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台活失败时替她写一条 {@code lesson} 札记(Reflexion 式):下一次调用同一件活,
 * {@code <memory>} 里那一行会提醒她绕开上次的坑。
 *
 * <h2>只认失败,只走启发式</h2>
 * 收尾状态是 {@code failed} / {@code timeout} / {@code interrupted} 才算失败——{@code done} 没什么好学的,
 * {@code stopped} 是主人自己按的停止,也不是她的错。失败正文里认得出的原因映射成一句可迁移的教训;
 * 认不出的退回一句通用的"没做成"。<b>不额外叫模型</b>:这条反射要在收尾那一刻同步写完,
 * 不能为它再挂一个回合。
 *
 * <h2>同名覆盖,不堆条数</h2>
 * 名字按任务固定({@code lesson-task-<task>}),同一件活反复失败只更新那一条;连教训一字不变时
 * 连写都不写——{@link NoteBook#revision()} 不涨,索引也就不会白重贴。
 *
 * <p>纯 JVM,不碰 Minecraft,可被 headless 单测钉住。
 */
public final class ReflectionRecorder {

    private ReflectionRecorder() {}

    /** 失败状态:不是做成,也不是主人叫停。 */
    private static final Set<String> FAILED_STATUSES = Set.of("failed", "timeout", "interrupted");

    /** 开标签里的属性;正文不参与匹配——正文是她说给模型看的话,属性才是账。 */
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"");

    /** 教训行上限:索引一行就该一眼看完。 */
    static final int MAX_LESSON = 200;

    /** 一条失败原因的映射规则,先匹配上的先算。 */
    private record Rule(List<String> needles, String reason, String advice) {}

    /** 顺序即优先级:具体词必须排在泛化的 {@code "no "} 之前。 */
    private static final List<Rule> RULES = List.of(
            new Rule(List.of("no path", "can't reach", "cannot reach", "could not reach",
                            "couldn't reach", "unreachable", "no reachable", "out of reach"),
                    "target unreachable",
                    "move_goto closer first (it stops beside a solid block), then retry"),
            new Rule(List.of("timed out", "timeout", "ran out of time", "out of time"),
                    "ran out of time",
                    "split it into smaller steps or start closer"),
            new Rule(List.of("interrupted", "died", "death", "死亡", "中断"),
                    "interrupted before it finished",
                    "stabilize (health, threats) and pick it back up"),
            new Rule(List.of("harvest", "no clear shot", "cannot be broken", "none of them can",
                            "none can", "right tool"),
                    "no tool or line of sight",
                    "bring the right tool or clear the line of sight before retrying"),
            new Rule(List.of("inventory full", "no space", "full bag"),
                    "inventory full",
                    "store or drop items first"),
            new Rule(List.of("refused", "denied", "permission", "not allowed", "not permit"),
                    "blocked by the permission layer",
                    "ask the owner first, or pick a target it allows"),
            new Rule(List.of("unknown", "not available", "invalid", "no such"),
                    "unknown or invalid target",
                    "check the id/name against the tool list before retrying"),
            new Rule(List.of("not found", "no ", "none found", "found none", "gone or had changed",
                            "in the loaded area"),
                    "target not found nearby",
                    "scan or move closer so the area loads; it may have moved"),
            new Rule(List.of("internal error", "exception"),
                    "internal error",
                    "don't repeat the exact call; tell the owner it's a bug"));

    /** 写好的一条 lesson;{@code name} 是稳定落点,{@code description} 是 {@code <memory>} 里那一行。 */
    public record Lesson(String name, String description, String content) {}

    /**
     * 纯函数:一件活收尾 → 一条可迁移的教训。不是失败、读不出任务名,都返回 null。
     *
     * @param task    任务名(快捷工具名,或"组 动作")
     * @param status  {@code done / failed / timeout / stopped / interrupted}
     * @param message 收尾正文
     */
    public static Lesson lessonFor(String task, String status, String message) {
        if (task == null || task.isBlank() || status == null
                || !FAILED_STATUSES.contains(status.strip().toLowerCase(Locale.ROOT))) {
            return null;
        }
        String plain = oneLine(message);
        Rule rule = match(plain);
        String reason = rule != null ? rule.reason() : "it did not get done";
        String advice = rule != null ? rule.advice()
                : "read the result and change something before retrying";
        String description = clamp(oneLine(task) + ": " + reason + " — " + advice);
        return new Lesson("lesson-task-" + identifier(task), description,
                status.strip().toLowerCase(Locale.ROOT) + ": " + plain);
    }

    /**
     * 收尾事件 → 教训;种类不对、缺 task/status 就返回 null。
     * 只认开标签里的属性,正文照原样读回。
     */
    public static Lesson fromEvent(EventQueue.Entry entry) {
        if (entry == null || !EventTypes.TASK_FINISHED.equals(entry.type()) || entry.text() == null) {
            return null;
        }
        String text = entry.text();
        int open = text.indexOf('>');
        if (open < 0) {
            return null;
        }
        Matcher m = ATTR.matcher(text.substring(0, open));
        String task = null;
        String status = null;
        while (m.find()) {
            if ("task".equals(m.group(1))) {
                task = m.group(2);
            } else if ("status".equals(m.group(1))) {
                status = m.group(2);
            }
        }
        int close = text.lastIndexOf("</event>");
        String body = close > open ? text.substring(open + 1, close) : text.substring(open + 1);
        return lessonFor(unescape(task), unescape(status), unescape(body));
    }

    /**
     * 收尾事件进来了:是失败就写一条 lesson,同名同文不重写。
     * 调用方负责把异常兜住——反思失败不该动到循环。
     */
    public static void record(NoteBook book, EventQueue.Entry entry) {
        Lesson lesson = fromEvent(entry);
        if (lesson == null) {
            return;
        }
        NoteBook.Note existing = book.read(lesson.name());
        if (existing != null && lesson.description().equals(existing.description())) {
            return;
        }
        book.write(lesson.name(), lesson.description(), "lesson", lesson.content());
    }

    // ---- 纯函数的小工具 ----

    private static Rule match(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Rule r : RULES) {
            for (String needle : r.needles()) {
                if (lower.contains(needle)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 正文折成一行:索引行按定义就是一行,换行会把 frontmatter 截断。 */
    private static String oneLine(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("\\s+", " ").strip();
    }

    private static String clamp(String s) {
        return s.length() <= MAX_LESSON ? s : s.substring(0, MAX_LESSON - 1).strip() + "…";
    }

    /** 任务名 → 文件名里那段:只留小写字母数字与连字符;全非 ASCII 时退回稳定哈希。 */
    private static String identifier(String task) {
        StringBuilder sb = new StringBuilder();
        for (char c : task.strip().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
            } else if ((c == '-' || c == '_' || c == ' ') && sb.length() > 0
                    && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() > 0 ? sb.toString() : Integer.toHexString(task.strip().hashCode());
    }

    /** 事件的属性/正文是 XML 转义过的,读回来先还原;{@code &amp;} 放最后,免得二次解开。 */
    private static String unescape(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&");
    }
}
