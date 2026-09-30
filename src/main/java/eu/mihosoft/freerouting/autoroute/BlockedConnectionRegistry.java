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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Registry {@code BatchAutorouter} uses to remember, across ripup passes, which (item, net)
 * connections are known to be provably unroutable, so they can be skipped on every remaining
 * pass instead of re-attempted, and separately how many times each connection has merely come
 * back NOT_ROUTED (the search ran and did not find a route that attempt).
 * <p>
 * These two are kept deliberately distinct and are NOT interchangeable:
 * <ul>
 * <li>{@link #mark_blocked} / {@link #is_blocked} are for
 * {@code AutorouteEngine.AutorouteResult.BLOCKED}: a geometric fact about the board as it
 * stands (no destination item has any free space to enter from), proven by a check that ran
 * before the maze search did. Ripup cannot change a fact like that, so once marked, permanent.
 * <li>{@link #record_not_routed} / {@link #repeatedly_failed_count} are for NOT_ROUTED: the
 * search ran and did not find a route on that attempt. That is NOT proof of impossibility --
 * ripup freedom grows with the pass number in {@code BatchAutorouter.autoroute_item}
 * (ripup_costs = start_ripup_costs * pass_no), so a connection that failed early may still
 * succeed later. This registry therefore only counts these, for visibility; nothing in this
 * class or its caller turns a NOT_ROUTED count into a skip decision.
 * </ul>
 * <p>
 * Pulled out of {@code BatchAutorouter} as its own class specifically so this bookkeeping can be
 * unit tested with synthetic {@link ConnectionKey}s, independent of a real board, {@code Item},
 * or {@code AutorouteEngine}.
 */
public class BlockedConnectionRegistry
{
    private final Set<ConnectionKey> blocked = new HashSet<ConnectionKey>();
    private final Map<ConnectionKey, Integer> not_routed_counts = new HashMap<ConnectionKey, Integer>();

    /** True if p_key was already marked blocked by an earlier call to {@link #mark_blocked}. */
    public boolean is_blocked(ConnectionKey p_key)
    {
        return this.blocked.contains(p_key);
    }

    /**
     * Marks p_key as provably unroutable.
     *
     * @return true the first time this key is marked (the caller should log it then and only
     * then, per the task's "do not re-log the same connection every pass"), false if it was
     * already marked.
     */
    public boolean mark_blocked(ConnectionKey p_key)
    {
        return this.blocked.add(p_key);
    }

    /** Records one more NOT_ROUTED result for p_key. Reporting only -- see the class javadoc. */
    public void record_not_routed(ConnectionKey p_key)
    {
        this.not_routed_counts.merge(p_key, 1, Integer::sum);
    }

    /** Number of distinct connections marked blocked so far. */
    public int blocked_count()
    {
        return this.blocked.size();
    }

    /**
     * Number of distinct connections that have recorded at least p_min_failures NOT_ROUTED
     * results so far. Reporting only; see the class javadoc for why this is not a give-up rule.
     */
    public int repeatedly_failed_count(int p_min_failures)
    {
        int result = 0;
        for (int curr_count : this.not_routed_counts.values())
        {
            if (curr_count >= p_min_failures)
            {
                ++result;
            }
        }
        return result;
    }
}
