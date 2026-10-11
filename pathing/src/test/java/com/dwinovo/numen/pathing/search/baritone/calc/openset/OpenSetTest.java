/*
 * Tests for the vendored Baritone open sets: the binary heap and the legacy
 * linked list must both pop nodes in ascending combined-cost order, and the
 * heap must handle a decrease-key update.
 */
package com.dwinovo.numen.pathing.search.baritone.calc.openset;

import com.dwinovo.numen.pathing.search.baritone.Goal;
import com.dwinovo.numen.pathing.search.baritone.calc.PathNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSetTest {

    private static final Goal NOWHERE = new Goal() {
        @Override
        public boolean isInGoal(int x, int y, int z) {
            return false;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return 0;
        }
    };

    private static PathNode node(int x, double combinedCost) {
        PathNode node = new PathNode(x, 0, 0, NOWHERE);
        node.combinedCost = combinedCost;
        return node;
    }

    @Test
    void theBinaryHeapPopsInAscendingCombinedCost() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet(2);
        for (PathNode node : new PathNode[] {node(0, 5), node(1, 1), node(2, 3), node(3, 4), node(4, 2)}) {
            heap.insert(node);
        }

        double previous = Double.NEGATIVE_INFINITY;
        int popped = 0;
        while (!heap.isEmpty()) {
            PathNode next = heap.removeLowest();
            assertTrue(next.combinedCost >= previous, "out of order: " + previous + " then " + next.combinedCost);
            previous = next.combinedCost;
            popped++;
        }
        assertEquals(5, popped);
        assertEquals(0, heap.size());
    }

    @Test
    void theBinaryHeapReordersAfterADecreaseKey() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        PathNode late = node(0, 10);
        PathNode other = node(1, 20);
        heap.insert(late);
        heap.insert(other);

        late.combinedCost = -5;
        heap.update(late);

        assertSame(late, heap.removeLowest(), "the decreased key must come out first");
    }

    @Test
    void theLinkedListOpenSetAlsoPopsInAscendingCombinedCost() {
        LinkedListOpenSet list = new LinkedListOpenSet();
        for (PathNode node : new PathNode[] {node(0, 5), node(1, 1), node(2, 3), node(3, 4), node(4, 2)}) {
            list.insert(node);
        }

        double previous = Double.NEGATIVE_INFINITY;
        while (!list.isEmpty()) {
            PathNode next = list.removeLowest();
            assertTrue(next.combinedCost >= previous, "out of order: " + previous + " then " + next.combinedCost);
            previous = next.combinedCost;
        }
    }
}
