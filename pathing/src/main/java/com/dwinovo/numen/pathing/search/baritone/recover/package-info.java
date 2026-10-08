/**
 * Stuck/backtrack recovery over the vendored Baritone search core.
 *
 * <p>Baritone answers two different failure questions with code spread across
 * {@code AbstractNodeCostSearch} and {@code PathingBehavior}; this package
 * gathers the two answers into one small, world-free API:
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.pathing.search.baritone.recover.StuckRecovery}
 *       — given a finished {@link com.dwinovo.numen.pathing.search.baritone.PathCalculationResult}
 *       or a running finder, produce the best partial path
 *       ({@code bestSoFar} / {@code pathToMostRecentNodeConsidered}); given a
 *       repeated "stuck" signal, produce a recompute request from the current
 *       node ({@code pathStart()} semantics);</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.baritone.recover.Recovery} —
 *       the outcome: fall back to a path, recompute from a node, or do
 *       nothing.</li>
 * </ul>
 *
 * <p>It edits nothing under {@code calc/}, {@code movement/} or the live
 * {@code search}/{@code drive} packages; a later step wires it into the live
 * executor.
 */
package com.dwinovo.numen.pathing.search.baritone.recover;
