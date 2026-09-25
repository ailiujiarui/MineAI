package com.dwinovo.numen.agent.goal;

import com.dwinovo.numen.agent.http.CancelToken;

import java.util.function.Consumer;

/**
 * 目标判官:一次 run 说完后,判目标达没达成。<b>判的人不是她</b>——执行与判定分开,她才骗不了自己。
 *
 * <p>把它抽成接口,是为了让"判断层"可替换:默认是另开一次干净模型调用的
 * {@link LlmGoalJudge};配了 JEV 就走 {@link JevGoalJudge}(便宜、带校准置信度)。换判官不
 * 影响 {@link GoalSteward} 的续跑/收工/额度逻辑。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public interface GoalJudge {

    /**
     * 判一次。
     *
     * @param goal   被判的目标
     * @param facts  身体事实(背包/位置/当前任务),服务端推来的,不是她自述
     * @param since  目标设定以来发生的事,已拼好
     * @param cancel 作废令牌;判到一半目标换了/开了新 run 就该作废
     * @param onDone 判完回调(内核线程)
     */
    void judge(GoalState goal, String facts, String since, CancelToken cancel, Consumer<Outcome> onDone);

    /**
     * 一次判定结果。
     *
     * @param verdict     判词(达成 / 打转 / 还差 + 理由)
     * @param freshTokens 这次判断烧掉的 token(LLM 判官才有;JEV 记 0)
     * @param failure     判不出来时的原因;为 {@code null} 表示判成功了。判不出来≠做完了
     */
    record Outcome(GoalPrompts.Verdict verdict, long freshTokens, String failure) {

        public static Outcome of(GoalPrompts.Verdict verdict, long freshTokens) {
            return new Outcome(verdict, Math.max(0L, freshTokens), null);
        }

        public static Outcome failed(String words) {
            return new Outcome(null, 0L, words == null ? "判不出来" : words);
        }

        public boolean ok() {
            return failure == null && verdict != null;
        }
    }
}
