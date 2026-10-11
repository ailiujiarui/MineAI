package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次延迟执行的操作的许可作用域。授权清单只在 ConsentDesk 保存,复核由 Gate 裁决。
 * 调用把凭据交给后台操作时,操作在提交或失败时 close;同步操作用 try-with-resources。
 * 主人停止、身体离世或死亡也会撤销凭据。凭据不能跨维度或身体复用。
 */
public final class Authorization implements AutoCloseable {
    private final NumenPlayer body;
    private final ServerLevel level;
    private final String what;
    private boolean closed;

    private Authorization(ServerCall call) {
        body = call.her();
        level = body.serverLevel();
        what = call.lua();
    }

    static Pending<Authorization> start(ServerCall call, List<Action> actions) {
        Authorization authorization = new Authorization(call);
        List<ConsentItem> items = new ArrayList<>();
        var gate = Permission.gateFor(call.her(), authorization);
        for (Action action : List.copyOf(actions)) {
            Verdict verdict = gate.judgeLive(action, authorization.level);
            if (verdict.asks()) {
                items.add(gate.consentItemLive(action, verdict, authorization.level));
            } else {
                try {
                    require(verdict, authorization.what);
                } catch (ApiError error) {
                    return Pending.failed(error);
                }
            }
        }
        Consents consents = Consents.of(call.her());
        consents.track(authorization);
        if (items.isEmpty()) {
            return Pending.of(authorization);
        }
        Pending<Void> answer = consents.await(authorization.what, items, authorization);
        Pending<Authorization> out = Pending.create();
        answer.whenDone(allowed -> {
            out.report(answer.stderr());
            out.complete(authorization);
        }, error -> {
            authorization.close();
            out.fail(error);
        });
        return out;
    }

    /** 动手前对当前目标重新裁决;不会再征询,未覆盖的动作按 NEEDS_CONSENT 返回。 */
    public void verify(List<Action> actions) {
        if (closed || body.isRemoved() || body.serverLevel() != level) {
            throw new ApiError(ErrorKind.INTERRUPTED, "authorization ended — " + what + " did not run", null);
        }
        var gate = Permission.gateFor(body, this);
        for (Action action : actions) {
            require(gate.judgeLive(action, level), what);
        }
    }

    static void require(Verdict verdict, String what) {
        switch (verdict.kind()) {
            case ALLOW -> { }
            case DENY -> throw ServerCall.refused(what, verdict.reason());
            case ASK, PENDING -> throw ServerCall.withheld(what, verdict.reason());
        }
    }

    @Override
    public void close() {
        close(ConsentDesk.Withdrawal.UNNEEDED);
    }

    void close(ConsentDesk.Withdrawal why) {
        if (!closed) {
            closed = true;
            ConsentDesk.of(body).release(this, why);
            Consents.of(body).forget(this);
        }
    }
}
