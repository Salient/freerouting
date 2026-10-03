/*
 *   Copyright (C) 2014  Alfons Wirtz
 *   website www.freerouting.net
 *
 *   Copyright (C) 2017 Michael Hoffer <info@michaelhoffer.de>
 *   Website www.freerouting.mihosoft.eu
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

import java.util.Iterator;

import eu.mihosoft.freerouting.board.FixedState;
import eu.mihosoft.freerouting.board.PolylineTrace;
import eu.mihosoft.freerouting.board.RoutingBoard;
import eu.mihosoft.freerouting.board.Trace;
import eu.mihosoft.freerouting.datastructures.UndoableObjects;
import eu.mihosoft.freerouting.logger.FRLogger;

/**
 * A single, comparable measurement of how good a (partially) routed board is.
 * <p>
 * This is the one place the batch autorouter's quality objective is defined. It used to be
 * duplicated: {@code BatchOptRoute.opt_route_pass} computed it inline to decide whether a
 * re-route was an improvement, and there was no equivalent anywhere else to report the same
 * numbers. Both now go through this class, so the number used to accept or reject a re-route
 * and the number printed in a log line are guaranteed to be the same number.
 * <p>
 * The comparison order is strictly lexicographic:
 * <pre>
 *     incomplete count  &gt;  via count  &gt;  weighted trace length
 * </pre>
 * i.e. a board with fewer incomplete connections always wins regardless of vias or trace
 * length; among boards with the same incomplete count, fewer vias always wins regardless of
 * trace length; only when both are tied does trace length decide.
 * <p>
 * Reproducibility of a given run is explicitly NOT a goal for this autorouter (see the
 * project's routing plan); the only thing that matters is the quality of the final board, which
 * is exactly what this class measures.
 *
 * @author Alfons Wirtz (original objective, formerly inline in BatchOptRoute)
 */
public final class BoardScore implements Comparable<BoardScore>
{
    /** Number of connections not yet routed (from the board's ratsnest). */
    /**
     * NOT COMPARABLE ACROSS -rm MODES, for the weighted-length term.
     *
     * <p>calc_weighted_trace_length counts only UNFIXED and SHOVE_FIXED traces, because the
     * optimiser it came from can only move those. In -rm finish the imported copper is
     * USER_FIXED, so the figure covers ONLY the newly routed traces: measured on the reference
     * board it reads 0 before routing and 88,900,508 after, against 55,655,452,702 for the same
     * board in -rm reroute where everything is movable. A 600x difference that is not a
     * regression.
     *
     * <p>So compare like with like: same board AND same -rm mode. incomplete_count, via_count
     * and corner_count have no such caveat - they count the whole board either way.
     */
    public final int incomplete_count;
    /** Number of vias currently on the board. */
    public final int via_count;
    /**
     * Cumulative trace length, weighted by each trace's half width plus its clearance value.
     * Traces that are shove_fixed count at half weight, matching the historic behaviour this
     * was extracted from (it discourages the optimizer from being penalized for shove-fixed
     * traces it did not choose to place, e.g. traces pinned by pad exit direction).
     */
    public final double weighted_trace_length;
    /**
     * Total corner count over every {@link PolylineTrace} on the board.
     * <p>
     * REPORTED ONLY. This term carries zero weight in {@link #compareTo}, and nothing in this
     * codebase currently branches on it. The user named "aesthetics" as a quality metric
     * alongside completeness, via count, and trace length, but neither of those other numbers
     * captures it: a board can have short traces and few vias and still look like a mess of
     * staircases and needless detours, and that is exactly what a high corner count flags.
     * Before spending any weight on it in an accept/reject decision (which would change actual
     * routing outcomes), we want to see -- across boards a human judges by eye -- whether corner
     * count actually tracks what looks good. So for now this is purely a reported diagnostic.
     */
    public final int corner_count;
    /**
     * Of {@link #incomplete_count}, how many are connections
     * {@code AutorouteEngine.AutorouteResult.BLOCKED} proved unroutable rather than merely not
     * yet found. REPORTED ONLY, like {@link #corner_count}: it is a subset of incomplete_count,
     * not an additional quantity, so it takes no part in {@link #compareTo} -- counting it there
     * too would double-weight the same incompletes. Defaults to 0 via the three-arg overloads
     * below for callers that were written before this field existed and have no such count to
     * give.
     */
    public final int blocked_count;

    private BoardScore(int p_incomplete_count, int p_via_count, double p_weighted_trace_length, int p_corner_count,
            int p_blocked_count)
    {
        this.incomplete_count = p_incomplete_count;
        this.via_count = p_via_count;
        this.weighted_trace_length = p_weighted_trace_length;
        this.corner_count = p_corner_count;
        this.blocked_count = p_blocked_count;
    }

    /**
     * Builds a score directly from already-known numbers, without scanning a board. Used where
     * the weighted trace length term is not simply "the current board's weighted trace length"
     * -- for example BatchOptRoute tracks a running minimum across a whole optimization pass,
     * not a single board snapshot -- so the caller must supply it explicitly.
     */
    public static BoardScore of(int p_incomplete_count, int p_via_count, double p_weighted_trace_length, int p_corner_count)
    {
        return new BoardScore(p_incomplete_count, p_via_count, p_weighted_trace_length, p_corner_count, 0);
    }

    /** As above, additionally reporting how many of p_incomplete_count are provably BLOCKED. */
    public static BoardScore of(int p_incomplete_count, int p_via_count, double p_weighted_trace_length, int p_corner_count,
            int p_blocked_count)
    {
        return new BoardScore(p_incomplete_count, p_via_count, p_weighted_trace_length, p_corner_count, p_blocked_count);
    }

    /**
     * Computes a score from a board's current state: via count, weighted trace length, and
     * corner count all come from p_board itself. The incomplete count cannot come from the
     * board alone (it depends on a RatsNest, which callers typically already have and cache
     * through the interactive session; constructing an independent one here would just be
     * redundant work), so it must be supplied by the caller.
     */
    public static BoardScore of(RoutingBoard p_board, int p_incomplete_count)
    {
        return of(p_board, p_incomplete_count, 0);
    }

    /** As above, additionally reporting how many of p_incomplete_count are provably BLOCKED. */
    public static BoardScore of(RoutingBoard p_board, int p_incomplete_count, int p_blocked_count)
    {
        int via_count = p_board.get_vias().size();
        double weighted_trace_length = calc_weighted_trace_length(p_board);
        int corner_count = calc_corner_count(p_board);
        return new BoardScore(p_incomplete_count, via_count, weighted_trace_length, corner_count, p_blocked_count);
    }

    /**
     * Calculates the cumulative trace lengths multiplied by the trace radius of all traces
     * on the board, which are not shove_fixed. Moved verbatim from
     * {@code BatchOptRoute.calc_weighted_trace_length}.
     */
    public static double calc_weighted_trace_length(RoutingBoard p_board)
    {
        double result = 0;
        int default_clearance_class = eu.mihosoft.freerouting.rules.BoardRules.default_clearance_class();
        Iterator<UndoableObjects.UndoableObjectNode> it = p_board.item_list.start_read_object();
        for (;;)
        {
            UndoableObjects.Storable curr_item = p_board.item_list.read_object(it);
            if (curr_item == null)
            {
                break;
            }
            if (curr_item instanceof Trace)
            {
                Trace curr_trace = (Trace) curr_item;
                FixedState fixed_state = curr_trace.get_fixed_state();
                if (fixed_state == FixedState.UNFIXED || fixed_state == FixedState.SHOVE_FIXED)
                {
                    double weighted_trace_length = curr_trace.get_length() * (curr_trace.get_half_width() + p_board.clearance_value(curr_trace.clearance_class_no(), default_clearance_class, curr_trace.get_layer()));
                    if (fixed_state == FixedState.SHOVE_FIXED)
                    {
                        // to produce less violations with pin exit directions.
                        weighted_trace_length /= 2;
                    }
                    result += weighted_trace_length;
                }
            }
        }
        return result;
    }

    /**
     * Sums {@link PolylineTrace#corner_count()} over every trace on the board. Unlike
     * {@link #calc_weighted_trace_length}, this counts every trace regardless of fixed state:
     * the user looking at the board for aesthetics sees all the copper, not just the part the
     * optimizer is still free to move.
     */
    public static int calc_corner_count(RoutingBoard p_board)
    {
        int result = 0;
        Iterator<UndoableObjects.UndoableObjectNode> it = p_board.item_list.start_read_object();
        for (;;)
        {
            UndoableObjects.Storable curr_item = p_board.item_list.read_object(it);
            if (curr_item == null)
            {
                break;
            }
            if (curr_item instanceof PolylineTrace)
            {
                result += ((PolylineTrace) curr_item).corner_count();
            }
        }
        return result;
    }

    /**
     * Lexicographic comparison: incomplete count, then via count, then weighted trace length.
     * A negative result means this score is better (fewer incompletes / vias / shorter length).
     * Corner count deliberately takes no part in this -- see the field comment.
     */
    @Override
    public int compareTo(BoardScore p_other)
    {
        if (this.incomplete_count != p_other.incomplete_count)
        {
            return Integer.compare(this.incomplete_count, p_other.incomplete_count);
        }
        if (this.via_count != p_other.via_count)
        {
            return Integer.compare(this.via_count, p_other.via_count);
        }
        return Double.compare(this.weighted_trace_length, p_other.weighted_trace_length);
    }

    /** Convenience for the common accept/reject question: is this score strictly better than p_other? */
    public boolean is_better_than(BoardScore p_other)
    {
        return compareTo(p_other) < 0;
    }

    @Override
    public String toString()
    {
        String blocked_suffix = blocked_count > 0
                ? " (" + blocked_count + " of which are provably unroutable, not just unfound)"
                : "";
        return "incomplete=" + incomplete_count + blocked_suffix
                + ", vias=" + via_count
                + ", weighted_trace_length=" + Math.round(weighted_trace_length)
                + ", corners=" + corner_count + " (reported only, unscored)";
    }

    /** Logs this score at WARN, prefixed with p_label, so batch run quality is always visible. */
    public void log(String p_label)
    {
        FRLogger.warn(p_label + ": " + this);
    }
}
