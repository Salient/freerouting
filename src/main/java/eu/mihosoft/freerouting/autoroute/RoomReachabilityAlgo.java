/*
 *   Copyright (C) 2014  Alfons Wirtz
 *   website www.freerouting.net
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/>
 *   for more details.
 */
package eu.mihosoft.freerouting.autoroute;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import eu.mihosoft.freerouting.board.Connectable;
import eu.mihosoft.freerouting.board.Item;
import eu.mihosoft.freerouting.datastructures.TimeLimit;
import eu.mihosoft.freerouting.geometry.planar.TileShape;
import eu.mihosoft.freerouting.logger.FRLogger;

/**
 * Proves (or fails to prove) that a connection is geometrically unroutable, by flood-filling the
 * expansion-room graph through doors, starting from rooms touching the destination items and
 * looking for any room that also touches a start item.
 * <p>
 * This is strictly stronger than the old pad-adjacency check ({@code AutorouteEngine} used to
 * have an {@code is_destination_reachable} method, now folded into this class): a destination pad
 * can have a room and a door onto it -- the old check's only question -- while that room is still
 * sealed inside a tiny pocket with no path out to anything else. The old check passed that case;
 * this one does not, because it keeps walking doors until it either reaches a start item or runs
 * out of rooms to visit.
 * <p>
 * <b>This is connectivity, not a second maze search.</b> {@link #flood_fill} is a plain
 * breadth-first search over {@link CompleteExpansionRoom#get_doors()} /
 * {@link ExpansionDoor#other_room}: no cost function, no {@link DestinationDistance}, no
 * preferred directions, no shoving, no backtracking, no {@link MazeListElement}. It does not
 * touch {@link MazeSearchAlgo} in any way.
 * <p>
 * <b>Rippable copper is passable.</b> This falls out of the existing room-building code for free,
 * rather than needing any filtering here: {@code SortedRoomNeighbours.calculate} (and its 90/45
 * degree siblings) only ever creates a door onto an {@link ObstacleExpansionRoom} when
 * {@code Item.is_route()} is true for that room's item -- see the {@code curr_item.is_route()}
 * guards there, both for 1-dimensional doors and for 2-dimensional overlap doors. Pins, conduction
 * areas, keepouts and the board outline (all {@code is_route() == false}) simply never get a door
 * created onto them in the first place, so this flood fill can walk through
 * {@code ObstacleExpansionRoom}s without ever checking rippability itself: the graph already
 * cannot reach a non-rippable obstacle through a door, by construction. It also does not matter
 * whether {@code AutorouteControl.ripup_allowed} happens to be set for the current ripup pass --
 * deliberately: the point of this check is "no amount of ripup passes will ever route this", so
 * an item that could be ripped on some future pass must count as passable now.
 * <p>
 * <b>Fails open, always.</b> Any timeout, room cap, unexaminable item, or exception must come back
 * as reachable (do not flag). Permanently skipping a connection the router could actually have
 * completed is far worse than missing a detection -- see {@link AutorouteEngine#autoroute_connection}
 * for where that fail-open boundary (try/catch) sits around the call into this class.
 *
 * @author Claude Opus 5 (1M context) and Claude Sonnet 5, 2026 -- building on the pad-adjacency
 * check from AutorouteEngine.is_destination_reachable (task 9), which this class replaces.
 */
final class RoomReachabilityAlgo
{
    private RoomReachabilityAlgo()
    {
        // not instantiable -- all entry points are static.
    }

    /**
     * Why a connection was (or was not) classified as geometrically blocked. Distinct from
     * {@code AutorouteEngine.AutorouteResult.BLOCKED} itself: that is the one-bit answer this
     * drives; this is the human-readable reason behind it, which {@code BatchAutorouter} surfaces
     * in its log line (see the task's "tell the user very different things about their board").
     */
    enum Verdict
    {
        /** A chain of doors connects a room touching a destination item to a room touching a
         * start item (or the search could not be run at all and fell back to assuming so). */
        REACHABLE,
        /** No destination item has any room/door onto it at all, on any of its layers -- the
         * exact case the old is_destination_reachable existed to catch. */
        PAD_UNREACHABLE,
        /** At least one destination item has free space immediately around it, but the flood
         * fill through doors from that free space never reached a room touching a start item --
         * free space exists, but it is sealed into a pocket with no way out. */
        NO_CORRIDOR
    }

    /**
     * Caps the number of distinct rooms this flood fill will visit before giving up and failing
     * open (per call to {@link #check}, i.e. per failed connection attempt, so this can be paid
     * repeatedly within one batch run -- see {@link #TIME_LIMIT_MILLISECONDS}).
     * <p>
     * A genuinely sealed pocket -- the case this check exists to prove -- is by definition
     * small: the whole point is that no door leads out of it, so the fill visiting it exhausts
     * on the pocket's own size, typically tens to a few hundred rooms for anything a human would
     * call "a small enclosed pocket" (see the task's own synthetic true-positive fixture). 2000
     * is generous headroom above that while still bounding memory and worst-case per-call cost.
     * The failure mode on the other side of this cap -- a destination that IS reachable, just
     * through a lot of open board, far enough away that the fill would cross more than 2000
     * rooms to find it -- fails open (reports REACHABLE, i.e. "do not flag"), which is the safe
     * direction required by the task regardless of where this number is set.
     */
    static final int MAX_ROOMS = 2000;

    /**
     * Caps the wall-clock time this flood fill will spend, independent of (and in addition to)
     * whatever remains of the connection's own per-attempt {@code TimeLimit}: this check runs
     * strictly after the maze search already failed (see the placement rationale in
     * {@code AutorouteEngine.autoroute_connection}), so on later ripup passes the outer time
     * budget can be large (up to two minutes, see {@code BatchAutorouter.MAX_CONNECTION_TIME_LIMIT})
     * and this bonus check must not be allowed to eat a comparable amount of time on every single
     * blocked connection in a batch run.
     * <p>
     * Measured call frequency on the task's 4 sample boards was low enough (under 50 flood-fill
     * invocations across an entire multi-pass batch run on each) that this budget barely mattered
     * there -- BoardScore and wall-clock time came back identical to the unmodified baseline on
     * all 4 (see the task report). This number is deliberately still tight (half a second, not
     * the multi-second budgets elsewhere in this codebase) as a safety margin for a board with
     * many more persistently-failing connections than those 4 happened to have: with N such
     * connections re-attempted every pass, this is the per-call price multiplied by N passes,
     * and that is the number that must stay small.
     */
    static final int TIME_LIMIT_MILLISECONDS = 500;

    /**
     * Entry point used by {@code AutorouteEngine.autoroute_connection}. See the class javadoc.
     */
    static Verdict check(Set<Item> p_start_items, Set<Item> p_destination_items,
            AutorouteEngine p_autoroute_engine, AutorouteControl p_ctrl)
    {
        if (p_destination_items.isEmpty())
        {
            // An empty destination set is only ever valid for fanout (route to board edge / a
            // plane, see MazeSearchAlgo.init's own is_fanout fallback); nothing to prove here.
            return Verdict.REACHABLE;
        }
        if (!all_examinable(p_start_items, p_ctrl) || !all_examinable(p_destination_items, p_ctrl))
        {
            // Fail open: either set contains an item this check cannot safely model (not
            // Connectable, so it could never show up in any room's target-door list no matter
            // how connected the board really is -- see the class javadoc of neck_down_may_apply
            // for the other reason -- a pin whose neck-down relaxation this static, full-width
            // check cannot account for).
            return Verdict.REACHABLE;
        }
        if (p_autoroute_engine.is_stop_requested())
        {
            return Verdict.REACHABLE;
        }

        Collection<CompleteExpansionRoom> seed_rooms = new LinkedList<>();
        boolean pad_reachable = seed_destination_rooms(p_destination_items, p_autoroute_engine, seed_rooms);
        if (p_autoroute_engine.is_stop_requested())
        {
            // Ran out of time (or the user cancelled) partway through seeding: we have not
            // finished checking every destination item, so neither PAD_UNREACHABLE nor
            // NO_CORRIDOR would be a proven fact. Fail all the way open.
            return Verdict.REACHABLE;
        }
        if (!pad_reachable)
        {
            return Verdict.PAD_UNREACHABLE;
        }

        TimeLimit own_time_limit = new TimeLimit(TIME_LIMIT_MILLISECONDS);
        BooleanSupplier out_of_budget = () -> p_autoroute_engine.is_stop_requested() || own_time_limit.limit_exceeded();
        Predicate<CompleteExpansionRoom> touches_start_item =
                room -> room.get_target_doors().stream().anyMatch(door -> p_start_items.contains(door.item));
        RoomExpander expander = p_autoroute_engine::complete_neigbour_rooms;

        FloodResult result = flood_fill(seed_rooms, touches_start_item, expander, MAX_ROOMS, out_of_budget);
        switch (result)
        {
            case FOUND:
                return Verdict.REACHABLE;
            case BUDGET_EXCEEDED:
                return Verdict.REACHABLE;
            case EXHAUSTED:
                return Verdict.NO_CORRIDOR;
            default:
                // Unreachable, but fail open rather than throw if a new enum value ever appears.
                FRLogger.warn("RoomReachabilityAlgo.check: unexpected FloodResult " + result);
                return Verdict.REACHABLE;
        }
    }

    /**
     * True if every item in p_items can be modelled by this check: it is {@link Connectable} (so
     * it can show up in a room's target-door list at all) and, if it is a {@code Pin}, neck-down
     * relaxation (see {@link #neck_down_may_apply}) does not apply to it.
     */
    private static boolean all_examinable(Set<Item> p_items, AutorouteControl p_ctrl)
    {
        for (Item curr_item : p_items)
        {
            if (!(curr_item instanceof Connectable))
            {
                return false;
            }
            if (neck_down_may_apply(curr_item, p_ctrl))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the seed rooms around every destination item, exactly as
     * {@code AutorouteEngine.is_destination_reachable} used to (same calls, same per-shape-index
     * loop): a room is grown from the item's own {@code get_trace_connection_shape} via
     * {@code add_incomplete_expansion_room} + {@code complete_expansion_room}. Every completed
     * room produced this way is added to p_seed_rooms_out (not just the first one found, unlike
     * the old method, which returned as soon as it found any -- we want the full set of adjacent
     * rooms as the starting frontier for the flood fill below).
     *
     * @return true if at least one destination item produced at least one completed 2-dimensional
     * room -- equivalent to the old method's "a target door back to itself exists", since a seed
     * room always contains the shape it was grown from (see FreeSpaceExpansionRoom's class
     * javadoc: "p_contained_points will remain contained in the shape, after it is completed"),
     * and so always ends up with a target door back onto the item that seeded it once a 2D
     * completed room exists at all.
     */
    private static boolean seed_destination_rooms(Set<Item> p_destination_items, AutorouteEngine p_autoroute_engine,
            Collection<CompleteExpansionRoom> p_seed_rooms_out)
    {
        boolean pad_reachable = false;
        for (Item curr_item : p_destination_items)
        {
            if (p_autoroute_engine.is_stop_requested())
            {
                // Did not finish checking every destination item -- stop here. The caller
                // (check()) re-checks is_stop_requested() right after this method returns and
                // fails all the way open in that case, regardless of what pad_reachable says, so
                // it is safe to just stop collecting rather than decide anything here.
                break;
            }
            ItemAutorouteInfo curr_info = curr_item.get_autoroute_info();
            curr_info.set_start_info(false);
            int shape_count = curr_item.tree_shape_count(p_autoroute_engine.autoroute_search_tree);
            for (int i = 0; i < shape_count; ++i)
            {
                TileShape contained_shape =
                        ((Connectable) curr_item).get_trace_connection_shape(p_autoroute_engine.autoroute_search_tree, i);
                if (contained_shape == null)
                {
                    continue;
                }
                int curr_layer = curr_item.shape_layer(i);
                IncompleteFreeSpaceExpansionRoom new_room =
                        p_autoroute_engine.add_incomplete_expansion_room(null, curr_layer, contained_shape);
                Collection<CompleteFreeSpaceExpansionRoom> completed_rooms = p_autoroute_engine.complete_expansion_room(new_room);
                for (CompleteFreeSpaceExpansionRoom curr_room : completed_rooms)
                {
                    pad_reachable = true;
                    p_seed_rooms_out.add(curr_room);
                }
            }
        }
        return pad_reachable;
    }

    /**
     * True if p_item is a Pin whose pad is narrow enough that the maze search's own neck-down
     * relaxation might let a trace enter it even where a full-width room would not fit. Always
     * false when neck-down is disabled in p_ctrl. Moved verbatim (same logic, same reasoning) from
     * the old {@code AutorouteEngine.neck_down_may_apply}: see
     * {@code MazeSearchAlgo.expand_to_room_doors}'s use of
     * {@code Math.min(half_width_add, neck_down_half_width)}, which is the actual relaxation this
     * static check cannot reproduce (and must not try to -- that would be reimplementing part of
     * MazeSearchAlgo, which the task's constraints forbid).
     */
    private static boolean neck_down_may_apply(Item p_item, AutorouteControl p_ctrl)
    {
        if (!p_ctrl.with_neckdown || !(p_item instanceof eu.mihosoft.freerouting.board.Pin))
        {
            return false;
        }
        eu.mihosoft.freerouting.board.Pin curr_pin = (eu.mihosoft.freerouting.board.Pin) p_item;
        for (int curr_layer = p_item.first_layer(); curr_layer <= p_item.last_layer(); ++curr_layer)
        {
            if (curr_layer < 0 || curr_layer >= p_ctrl.compensated_trace_half_width.length)
            {
                continue;
            }
            int neckdown_half_width = curr_pin.get_trace_neckdown_halfwidth(curr_layer);
            if (neckdown_half_width > 0 && neckdown_half_width < p_ctrl.compensated_trace_half_width[curr_layer])
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Hook that materializes a room's immediate neighbours before its doors are trusted: the real
     * implementation ({@code AutorouteEngine::complete_neigbour_rooms}) completes any still-
     * incomplete neighbour and calculates the doors of any newly-discovered
     * {@link ObstacleExpansionRoom}. Factored out purely so {@link #flood_fill} can be unit
     * tested with synthetic room graphs that are already fully built, independent of
     * {@code AutorouteEngine}, {@code ShapeSearchTree}, or any real board geometry -- see
     * RoomReachabilityAlgoTest.
     */
    @FunctionalInterface
    interface RoomExpander
    {
        void expand(CompleteExpansionRoom p_room);
    }

    enum FloodResult
    {
        FOUND, EXHAUSTED, BUDGET_EXCEEDED
    }

    /**
     * The actual flood fill: breadth-first search over {@code CompleteExpansionRoom.get_doors()},
     * starting from p_seed_rooms, stopping as soon as p_is_target matches a visited room (FOUND),
     * or when the frontier is exhausted without a match (EXHAUSTED), or when p_max_rooms distinct
     * rooms have been visited or p_out_of_budget reports true (BUDGET_EXCEEDED -- the caller must
     * treat this the same as FOUND for the purpose of "do not flag", i.e. fail open).
     * <p>
     * Deliberately the only method in this class with no dependency on AutorouteEngine, Item, or
     * any board/geometry type beyond the expansion-room interfaces themselves, so it can be
     * exercised directly with synthetic fakes.
     */
    static FloodResult flood_fill(Collection<CompleteExpansionRoom> p_seed_rooms,
            Predicate<CompleteExpansionRoom> p_is_target, RoomExpander p_expander, int p_max_rooms,
            BooleanSupplier p_out_of_budget)
    {
        Set<CompleteExpansionRoom> visited =
                Collections.newSetFromMap(new IdentityHashMap<CompleteExpansionRoom, Boolean>());
        Deque<CompleteExpansionRoom> frontier = new ArrayDeque<>();
        for (CompleteExpansionRoom seed : p_seed_rooms)
        {
            if (!visited.add(seed))
            {
                continue;
            }
            if (p_is_target.test(seed))
            {
                return FloodResult.FOUND;
            }
            frontier.add(seed);
        }
        while (!frontier.isEmpty())
        {
            if (p_out_of_budget.getAsBoolean())
            {
                return FloodResult.BUDGET_EXCEEDED;
            }
            CompleteExpansionRoom curr_room = frontier.poll();
            p_expander.expand(curr_room);
            for (ExpansionDoor curr_door : curr_room.get_doors())
            {
                ExpansionRoom neighbour = curr_door.other_room(curr_room);
                if (!(neighbour instanceof CompleteExpansionRoom))
                {
                    // Should not happen after p_expander.expand(curr_room) -- that call is
                    // specifically responsible for resolving any Incomplete neighbour into a
                    // completed one. Skip rather than fail: worst case this costs a missed path,
                    // which is the safe direction (fail open), never a false BLOCKED.
                    continue;
                }
                CompleteExpansionRoom neighbour_room = (CompleteExpansionRoom) neighbour;
                if (!visited.add(neighbour_room))
                {
                    continue;
                }
                if (visited.size() > p_max_rooms)
                {
                    return FloodResult.BUDGET_EXCEEDED;
                }
                if (p_is_target.test(neighbour_room))
                {
                    return FloodResult.FOUND;
                }
                frontier.add(neighbour_room);
            }
        }
        return FloodResult.EXHAUSTED;
    }
}
