package com.dwinovo.numen.bench.report;

import java.util.Map;

/**
 * 一次运行记录里的一行原始事件({@link RunTrace} 读出来的样子):{@code kind} 是事件种类,{@code ms} 是相对开始的毫秒,
 * {@code fields} 是这一行的其余字段,值都按字符串收——记录文件里本来就只有字符串与数。缺的字段读出空串。
 *
 * <p>聚合器({@link EvalMetrics})只认这几种:{@code turn}(轮次边界)、{@code tool_call}、{@code tool_result}、
 * {@code program_end}(程序结局,{@code stopped} 是一次打断)、{@code api_call}(程序里每次 API 调用的函数与结局)、
 * {@code consent}/{@code consent_withdrawn}、{@code end}。别的行原样留着,聚合器不看。
 */
public record TraceEvent(String kind, long ms, Map<String, String> fields) {

    public TraceEvent {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** 这个字段的值;没有是空串。 */
    public String field(String name) {
        return fields.getOrDefault(name, "");
    }

    /** 这个字段是不是 {@code true}。 */
    public boolean flag(String name) {
        return Boolean.parseBoolean(field(name));
    }
}
