package com.dwinovo.numen.agent.recover;

import com.dwinovo.numen.agent.bt.ActionNode;
import com.dwinovo.numen.agent.bt.BtContext;
import com.dwinovo.numen.agent.bt.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoverTest {

    private static Node.Status tick(Recover recover, BtContext ctx, long t) {
        ctx.beginTick(t, () -> "");
        return recover.tick(ctx);
    }

    @Test
    void repairsThenRetriesTheChild() {
        int[] fails = {1};
        Node flaky = new ActionNode("flaky", c -> fails[0]-- > 0 ? Node.Status.FAILURE : Node.Status.SUCCESS);
        Recover recover = new Recover(flaky,
                () -> FailureSignal.of("no pickaxe", FailureKind.NO_TOOL),
                kind -> new ActionNode("get tool", c -> Node.Status.SUCCESS));

        BtContext ctx = new BtContext();
        assertEquals(Node.Status.RUNNING, tick(recover, ctx, 1), "孩子失败,先修");
        assertEquals(Node.Status.RUNNING, tick(recover, ctx, 2), "修好了,让孩子重试");
        assertEquals(Node.Status.SUCCESS, tick(recover, ctx, 3), "这次孩子成了");
        assertEquals(FailureKind.NO_TOOL, recover.lastKind());
        assertEquals(Recovery.ACQUIRE_TOOL, recover.lastRecovery());
    }

    @Test
    void withNoRemedyTheFailurePropagates() {
        Recover recover = new Recover(new ActionNode("x", c -> Node.Status.FAILURE),
                () -> FailureSignal.of(FailureKind.UNREACHABLE), kind -> null);
        assertEquals(Node.Status.FAILURE, tick(recover, new BtContext(), 1));
        assertEquals(FailureKind.UNREACHABLE, recover.lastKind());
    }

    @Test
    void recoveriesAreBounded() {
        Node alwaysFail = new ActionNode("x", c -> Node.Status.FAILURE);
        Node remedy = new ActionNode("fix", c -> Node.Status.SUCCESS);
        Recover recover = new Recover(alwaysFail,
                () -> FailureSignal.of(FailureKind.TARGET_GONE), kind -> remedy, 1);

        BtContext ctx = new BtContext();
        assertEquals(Node.Status.RUNNING, tick(recover, ctx, 1), "第一轮:修");
        assertEquals(Node.Status.RUNNING, tick(recover, ctx, 2), "修好,重试");
        assertEquals(Node.Status.FAILURE, tick(recover, ctx, 3), "到顶了,不再无限修");
    }
}
