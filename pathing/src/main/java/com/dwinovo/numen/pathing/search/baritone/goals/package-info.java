/**
 * The goal family the vendored A* is driven by, plus the bridge from Numen's
 * own goals.
 *
 * <ul>
 *   <li>{@link com.dwinovo.numen.pathing.search.baritone.goals.GoalBlock},
 *       {@link com.dwinovo.numen.pathing.search.baritone.goals.GoalXZ},
 *       {@link com.dwinovo.numen.pathing.search.baritone.goals.GoalYLevel} —
 *       the admissible location goals;</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.baritone.goals.GoalNear},
 *       {@link com.dwinovo.numen.pathing.search.baritone.goals.GoalGetToBlock},
 *       {@link com.dwinovo.numen.pathing.search.baritone.goals.GoalTwoBlocks} —
 *       "close enough" and "next to it" goals;</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.baritone.goals.GoalComposite},
 *       {@link com.dwinovo.numen.pathing.search.baritone.goals.GoalRunAway} —
 *       any-of and retreat;</li>
 *   <li>{@link com.dwinovo.numen.pathing.search.baritone.goals.GoalAdapters} —
 *       turns a {@link com.dwinovo.numen.pathing.search.Goal} into one of the
 *       above.</li>
 * </ul>
 *
 * <p>Heuristics are the octile/vertical lower bounds Baritone uses, built from
 * {@link com.dwinovo.numen.pathing.search.baritone.goals.HeuristicCosts}. The
 * faithful ports of goals that stop short of the target ({@code GoalNear},
 * {@code GoalGetToBlock}) keep upstream's center/block heuristic and are
 * documented where that is not a strict bound.
 */
package com.dwinovo.numen.pathing.search.baritone.goals;
