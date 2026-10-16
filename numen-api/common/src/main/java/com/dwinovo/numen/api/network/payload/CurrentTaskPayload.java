package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.task.Activity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server → Client:她此刻在做什么。<b>这是「她在做什么」的唯一真源</b>。
 *
 * <h2>为什么必须由服务端推</h2>
 * 活不都是客户端派起来的:服务器重启后的重放、死亡复活后的重放都不经过它。
 * 客户端要是靠"我派出去过什么"自己记账,这些活它一概不知道——她明明在跟随,
 * 头顶却没有「正在…」气泡,{@code <runtime_state>} 里也写着她闲着。
 * 槽在服务端,推送就从服务端来。
 *
 * <p>槽的转换只发生在 {@code CompanionBrain} 一处,所以推送也只从那一处发出:
 * 派发、重放、被顶替、干完、死亡清空,走的是同一个出口。客户端不再推断,只照抄。
 *
 * @param taskId    公开任务 id;<b>空串 = 她现在闲着</b>
 * @param tool      任务名:函数全名(numen.move.to / numen.work.dig / numen.move.follow …)
 * @param describe  给模型看的一句描述,由任务记录自己给出
 * @param activity  给主人看的一句({@link Activity}:语言键 + 现场的词),客户端按当前语言翻
 * @param standing  常驻(没有终点,不会发 task_finished)
 * @param elapsedMs 已经干了多久——重放回来的活也有正确的起点,不会从推送那一刻重新计时
 *
 * <p>任务名与描述由任务记录给(插件的任务也在内),长短不归这个包定,用 {@link Wire#text()};描述长到整包装不下时
 * 描述换成一句说明、给主人看的那句换成只说工具名({@link #shrunk}),是哪件活、干了多久照旧。
 */
public record CurrentTaskPayload(UUID entityUuid, String taskId, String tool,
                                 String describe, Activity activity, boolean standing, long elapsedMs)
        implements CustomPacketPayload, Wire.Oversized<CurrentTaskPayload> {

    public static final Type<CurrentTaskPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "current_task"));

    /** 一句话最多几个现场的词。 */
    private static final int MAX_ACTIVITY_ARGS = 8;

    private static final StreamCodec<ByteBuf, Activity> ACTIVITY_CODEC = StreamCodec.composite(
            Wire.TO_CLIENT.text(), Activity::key,
            Wire.TO_CLIENT.text().apply(ByteBufCodecs.list(MAX_ACTIVITY_ARGS)), Activity::args,
            Activity::new);

    public static final StreamCodec<ByteBuf, CurrentTaskPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, p.entityUuid);
                Wire.TO_CLIENT.text().encode(buf, p.taskId);
                Wire.TO_CLIENT.text().encode(buf, p.tool);
                Wire.TO_CLIENT.text().encode(buf, p.describe);
                ACTIVITY_CODEC.encode(buf, p.activity);
                ByteBufCodecs.BOOL.encode(buf, p.standing);
                ByteBufCodecs.VAR_LONG.encode(buf, p.elapsedMs);
            },
            buf -> new CurrentTaskPayload(UUIDUtil.STREAM_CODEC.decode(buf),
                    Wire.TO_CLIENT.text().decode(buf), Wire.TO_CLIENT.text().decode(buf),
                    Wire.TO_CLIENT.text().decode(buf), ACTIVITY_CODEC.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf)));

    /** 她闲着。 */
    public static CurrentTaskPayload idle(UUID entityUuid) {
        return new CurrentTaskPayload(entityUuid, "", "", "", Activity.of(Activity.DEFAULT_KEY, ""), false, 0L);
    }

    public boolean idle() {
        return taskId.isEmpty();
    }

    @Override
    public CurrentTaskPayload shrunk(Predicate<CurrentTaskPayload> fits, int bytes, int budget) {
        return new CurrentTaskPayload(entityUuid, taskId, tool,
                Wire.TO_CLIENT.tooBig("Its description", bytes) + ", so it is not shown.",
                Activity.of(Activity.DEFAULT_KEY, tool), standing, elapsedMs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(CurrentTaskPayload p) {
        com.dwinovo.numen.api.network.ClientPayloadSink.currentTask.accept(p);
    }
}
