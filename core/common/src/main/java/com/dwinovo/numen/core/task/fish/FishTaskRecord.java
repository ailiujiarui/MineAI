package com.dwinovo.numen.core.task.fish;

import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.task.TaskRecord;

/** Typed descriptor for the {@code work fish} background task: one cast. */
public final class FishTaskRecord extends TaskRecord {

    /** 一竿的期限:等咬钩的时长,外加对准、抛出去、落水的那几秒。 */
    private static final long CAST_TICKS = FishCompanionTask.CAST_LIFETIME + 15 * 20;

    public FishTaskRecord(ServerCall source) {
        super(source, source.her().level().getGameTime() + CAST_TICKS);
    }

    /** 钓鱼是自主长跑的活:等主人点头等同无限等。 */
    @Override
    public long consentTimeoutTicks() {
        return ConsentDesk.UNBOUNDED_TICKS;
    }


    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        return "钓鱼,抛了一竿";
    }
}
