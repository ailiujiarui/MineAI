package com.dwinovo.numen.agent.goal;

import java.util.function.Consumer;

/**
 * 目标的最后一关:模型判官说"达成"、并给了可机检的宣称时,拿宣称去量一遍真实世界。
 *
 * <p>判官仍然是模型(见 {@link GoalJudge}),它可能说已达成了而世界并不作数。宣称这一层把判词
 * 里可以确定对错的部分摘出来——背包里的数量、某一格是什么方块、机器配置——交给宿主用确定性的
 * {@code verify} 工具量。量出来了才收工;量不出来就把"世界实际是什么样"当作还差什么交回给她。
 *
 * <p>它是宿主给的:引擎这边只认"量一次"这一个动作,怎么量(派发工具、问服务端)是宿主的
 * 事。{@code agent} 保持纯 JVM,不碰 Minecraft。
 *
 * <p><b>放行优先,但不是放行一切</b>:宿主根本没能力量(没装这个工具、派发失败、超时)一律当已验证
 * ——一个会抽风的检查不该把本来就已经完成的目标卡死。但检查确实跑了、只是没能给出判词(回执读不动、
 * 工具答非所问)时不算放行:那正是"没量过却当成达成"的口子,交给调用方按"没核对上"处理。
 * 只有世界明确说"不成立",以及"跑了却没判词",才拦。
 *
 * <p>回调可能在非内核线程上;宿主负责切回主线程。
 */
public interface GoalVerifier {

    /**
     * 量一次宣称。
     *
     * @param goal   被判的目标(量回来时可能已经换了,调用方自己核)
     * @param claim  判官给的机检宣称,形如 {@code "have minecraft:iron_ingot 3"}
     * @param onDone 量完回调:{@code verified} 说世界认没认,{@code detail} 说世界实际是什么样
     */
    void verify(GoalState goal, String claim, Consumer<Result> onDone);

    /**
     * 一次核对的结果。
     *
     * @param verified 世界认不认这条宣称;宿主量不了时为 {@code true}(放行)
     * @param detail   人读的差异说明({@code expected} vs {@code actual});放行时说明为什么没量
     * @param measured 本次到底有没有量出判词。<b>跑不了</b>(验证者不在、派发失败、超时)是 {@code false}
     *                 且 {@code verified=true}——环境缺能力,不该把本来完成的目标卡死;<b>跑了但给不出判词</b>
     *                 (回执读不动、工具答非所问)是 {@code false} 且 {@code verified=false}——工具在,只是没
     *                 回答,就不能假装世界认了(见 {@link #unmeasured})。
     */
    record Result(boolean verified, String detail, boolean measured) {

        /** 量出判词的两态:世界认,或世界明确不认。 */
        public Result(boolean verified, String detail) {
            this(verified, detail, true);
        }

        /** 检查跑了,但没能给出判词:既不是认,也不是放行——调用方据此不收工。 */
        public static Result unmeasured(String detail) {
            return new Result(false, detail, false);
        }
    }

    /** 没有验证者可用:一律放行,不让这层挡住一个原本就会收工的目标。 */
    GoalVerifier PASS_THROUGH = (goal, claim, onDone) -> onDone.accept(new Result(true, "no verifier"));
}
