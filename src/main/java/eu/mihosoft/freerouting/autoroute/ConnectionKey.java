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

/**
 * Identifies one (item, net) autoroute attempt across ripup passes.
 * <p>
 * {@code BatchAutorouter.autoroute_pass} re-attempts every unrouted item on every pass, keyed
 * only by the item and which of its nets is being routed -- see
 * {@code BatchAutorouter.autoroute_item(Item p_item, int p_route_net_no, ...)}. This class
 * captures exactly that pair so a registry can remember, across passes, which attempts already
 * turned out to be provably unroutable (see AutorouteEngine.AutorouteResult.BLOCKED) without
 * re-running the geometric check or the maze search.
 * <p>
 * Deliberately holds only primitives (net number, {@link eu.mihosoft.freerouting.board.Item#get_id_no()}),
 * not an {@code Item} reference, so it is trivial to construct with synthetic data in a unit
 * test and does not keep board objects alive past their usefulness.
 */
public final class ConnectionKey
{
    public final int net_no;
    public final int item_id_no;

    public ConnectionKey(int p_net_no, int p_item_id_no)
    {
        this.net_no = p_net_no;
        this.item_id_no = p_item_id_no;
    }

    @Override
    public boolean equals(Object p_other)
    {
        if (this == p_other)
        {
            return true;
        }
        if (!(p_other instanceof ConnectionKey))
        {
            return false;
        }
        ConnectionKey other = (ConnectionKey) p_other;
        return this.net_no == other.net_no && this.item_id_no == other.item_id_no;
    }

    @Override
    public int hashCode()
    {
        // Small, disjoint fields -- a simple mix is enough and keeps this readable.
        return 31 * this.net_no + this.item_id_no;
    }

    @Override
    public String toString()
    {
        return "net " + this.net_no + ", item " + this.item_id_no;
    }
}
