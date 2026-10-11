package com.dwinovo.numen.adapter;

import com.dwinovo.numen.agent.adapter.ReloadReport;
import com.dwinovo.numen.api.adapter.AdapterHandlers;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.ServerCall;
import java.util.List;
import java.util.Map;

/** 适配器诊断走已有 SDK,值和错误由同一回执通道交回。 */
public final class AdapterApi {
    private AdapterApi() {}

    public record State(List<String> active, ReloadReport report, Map<String, String> failures) {}

    @Fn("Reload adapter files and report loaded, skipped, failed and changed adapters.")
    @Example("return numen.adapter.reload()")
    public static ReloadReport reload(ServerCall call) {
        return AdapterManager.reload();
    }

    @Fn("List active adapters, the last load report and failing handlers.")
    @Example("return numen.adapter.list()")
    public static State list(ServerCall call) {
        return new State(AdapterManager.registry().ordered().stream().map(spec -> spec.id()).toList(),
                AdapterManager.lastReport(), AdapterHandlers.failures());
    }
}
