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
import java.util.Collection;
import java.util.Set;

import eu.mihosoft.freerouting.datastructures.UndoableObjects;

import eu.mihosoft.freerouting.geometry.planar.FloatPoint;

import eu.mihosoft.freerouting.board.Item;
import eu.mihosoft.freerouting.board.Via;
import eu.mihosoft.freerouting.board.Trace;
import eu.mihosoft.freerouting.board.RoutingBoard;

import eu.mihosoft.freerouting.interactive.AutorouteSettings;
import eu.mihosoft.freerouting.interactive.InteractiveActionThread;
import eu.mihosoft.freerouting.logger.FRLogger;

/**
 * To optimize the vias and traces after the batch autorouter has completed the board.
 * 
 * @author Alfons Wirtz
 */
public class BatchOptRoute
{

    /**
     *  To optimize the route on the board after the autoroute task is finished.
     */
    public BatchOptRoute(InteractiveActionThread p_thread)
    {
        this.thread = p_thread;
        this.routing_board = p_thread.hdlg.get_routing_board();
        this.sorted_route_items = null;
    }

    /**
     * Optimize the route on the board.
     */
    public void optimize_board()
    {
        // This used to be gated on get_test_level() != RELEASE_VERSION, which is the default
        // test level for every CLI/batch run -- so in practice this line never printed outside
        // of interactive debugging. Folded into the BoardScore report and made unconditional so
        // a batch run's via reduction is actually visible (see task 5).
        this.thread.hdlg.remove_ratsnest();
        int incomplete_count_before_optimize = this.thread.hdlg.get_ratsnest().incomplete_count();
        BoardScore.of(routing_board, incomplete_count_before_optimize).log("Before postroute optimize");

        // Via reduction effort: raise via_costs for postroute only. Higher via costs make the
        // maze search itself avoid vias more; combined with opt_route_item's accept-if-fewer-
        // vias rule (now BoardScore.is_better_than), that is the direct lever for reducing via
        // count. Restored in a finally block: via_costs is shared, global AutorouteSettings
        // state also read by the main autoroute phase and the interactive router, so this must
        // not leak a permanent change beyond this method. At effort 0 the factor is exactly
        // 1.0, so this is a no-op (boosted_via_costs == base_via_costs, the set/restore pair is
        // skipped entirely).
        AutorouteSettings autoroute_settings = this.thread.hdlg.get_settings().autoroute_settings;
        int base_via_costs = autoroute_settings.get_via_costs();
        int boosted_via_costs = (int) Math.round(base_via_costs * autoroute_settings.get_via_cost_boost_factor());
        boolean via_costs_boosted = boosted_via_costs != base_via_costs;
        if (via_costs_boosted)
        {
            FRLogger.info("Via reduction effort = " + autoroute_settings.get_via_reduction_effort()
                    + "/100: raising via_costs from " + base_via_costs + " to " + boosted_via_costs
                    + " for postroute only.");
            autoroute_settings.set_via_costs(boosted_via_costs);
        }
        try
        {
            boolean route_improved = true;
            int curr_pass_no = 0;
            use_increased_ripup_costs = true;

            while (route_improved)
            {
                ++curr_pass_no;
                boolean with_prefered_directions = (curr_pass_no % 2 != 0); // to create more variations
                route_improved = opt_route_pass(curr_pass_no, with_prefered_directions);
            }
        }
        finally
        {
            if (via_costs_boosted)
            {
                autoroute_settings.set_via_costs(base_via_costs);
            }
        }

        this.thread.hdlg.remove_ratsnest();
        int incomplete_count_after_optimize = this.thread.hdlg.get_ratsnest().incomplete_count();
        BoardScore.of(routing_board, incomplete_count_after_optimize).log("After postroute optimize");
    }

    /**
     * Pass to reduce the number of vias an to shorten the trace length a completely routed board.
     * Returns true, if the route was improved.
     */
    private boolean opt_route_pass(int p_pass_no, boolean p_with_prefered_directions)
    {
        boolean route_improved = false;
        int via_count_before = this.routing_board.get_vias().size();
        double trace_length_before = this.thread.hdlg.coordinate_transform.board_to_user(this.routing_board.cumulative_trace_length());
        this.thread.hdlg.screen_messages.set_post_route_info(via_count_before, trace_length_before);
        this.sorted_route_items = new ReadSortedRouteItems();
        this.min_cumulative_trace_length_before = BoardScore.calc_weighted_trace_length(routing_board);
        for (;;)
        {
            if (this.thread.is_stop_requested())
            {
                return route_improved;
            }
            Item curr_item = sorted_route_items.next();
            if (curr_item == null)
            {
                break;
            }
            if (opt_route_item(curr_item, p_pass_no, p_with_prefered_directions))
            {
                route_improved = true;
            }
        }
        this.sorted_route_items = null;
        if (this.use_increased_ripup_costs && !route_improved)
        {
            this.use_increased_ripup_costs = false;
            route_improved = true; // to keep the optimizer going with lower ripup costs
        }
        return route_improved;
    }

    /**
     * Trie to improve the route by retouting the connections containing p_item.
     */
    private boolean opt_route_item(Item p_item, int p_pass_no, boolean p_with_prefered_directions)
    {
        java.util.ResourceBundle resources =
                java.util.ResourceBundle.getBundle("eu.mihosoft.freerouting.interactive.InteractiveState", this.thread.hdlg.get_locale());
        this.thread.hdlg.screen_messages.set_status_message(
                resources.getString("batch_optimizer") + " " + resources.getString("stop_message"));
        this.thread.hdlg.screen_messages.set_pass_number(p_pass_no);
        this.thread.hdlg.remove_ratsnest();
        int incomplete_count_before = this.thread.hdlg.get_ratsnest().incomplete_count();
        int via_count_before = this.routing_board.get_vias().size();
        Set<Item> ripped_items = new java.util.TreeSet<Item>();
        ripped_items.add(p_item);
        if (p_item instanceof Trace)
        {
            // add also the fork items, especially because not all fork items may be 
            // returned by ReadSortedRouteItems because of matching end points.
            Trace curr_trace = (Trace) p_item;
            Set<Item> curr_contact_list = curr_trace.get_start_contacts();
            for (int i = 0; i < 2; ++i)
            {
                if (contains_only_unfixed_traces(curr_contact_list))
                {
                    ripped_items.addAll(curr_contact_list);
                }
                curr_contact_list = curr_trace.get_end_contacts();
            }
        }
        Set<Item> ripped_connections = new java.util.TreeSet<Item>();
        for (Item curr_item : ripped_items)
        {
            ripped_connections.addAll(curr_item.get_connection_items(Item.StopConnectionOption.NONE));
        }
        for (Item curr_item : ripped_connections)
        {
            if (curr_item.is_user_fixed())
            {
                return false;
            }
        }
        routing_board.generate_snapshot();
        this.routing_board.remove_items(ripped_connections, false);
        for (int i = 0; i < p_item.net_count(); ++i)
        {
            this.routing_board.combine_traces(p_item.get_net_no(i));
        }
        int ripup_costs = this.thread.hdlg.get_settings().autoroute_settings.get_start_ripup_costs();
        if (this.use_increased_ripup_costs)
        {
            ripup_costs *= ADDITIONAL_RIPUP_COST_FACTOR_AT_START;
        }
        if (p_item instanceof Trace)
        {
            // taking less ripup costs seems to produce better results
            ripup_costs = (int) Math.round(0.6 * (double) ripup_costs);
        }
        BatchAutorouter.autoroute_passes_for_optimizing_item(this.thread, MAX_AUTOROUTE_PASSES,
                ripup_costs, p_with_prefered_directions);
        this.thread.hdlg.remove_ratsnest();
        int incomplete_count_after = this.thread.hdlg.get_ratsnest().incomplete_count();
        // score_before's weighted_trace_length is deliberately the running minimum seen so far
        // in this optimize_board() pass, not a fresh measurement of the current board -- that
        // running minimum is what "min_cumulative_trace_length_before" has always tracked here.
        // score_after is a fresh, single-scan measurement of the board as it stands right now.
        BoardScore score_before = BoardScore.of(incomplete_count_before, via_count_before, this.min_cumulative_trace_length_before, -1);
        BoardScore score_after = BoardScore.of(routing_board, incomplete_count_after);
        boolean route_improved = !this.thread.is_stop_requested() && score_after.is_better_than(score_before);
        if (route_improved)
        {
            if (score_after.incomplete_count < score_before.incomplete_count ||
                    score_after.incomplete_count == score_before.incomplete_count && score_after.via_count < score_before.via_count)
            {
                this.min_cumulative_trace_length_before = score_after.weighted_trace_length;
            }
            else
            {
                // Only cumulative trace length shortened.
                // Catch unexpected increase of cumulative trace length somewhere for examole by removing acid trapsw.
                this.min_cumulative_trace_length_before = Math.min(this.min_cumulative_trace_length_before, score_after.weighted_trace_length);
            }
            routing_board.pop_snapshot();
            double new_trace_length = this.thread.hdlg.coordinate_transform.board_to_user(this.routing_board.cumulative_trace_length());
            this.thread.hdlg.screen_messages.set_post_route_info(score_after.via_count, new_trace_length);
        }
        else
        {
            routing_board.undo(null);
        }
        return route_improved;
    }

    static boolean contains_only_unfixed_traces(Collection<Item> p_item_list)
    {
        for (Item curr_item : p_item_list)
        {
            if (curr_item.is_user_fixed() || !(curr_item instanceof Trace))
            {
                return false;
            }
        }
        return true;
    }

    /**
     *  Returns the current position of the item, which will be rerouted or null, if the optimizer is not active.
     */
    public FloatPoint get_current_position()
    {
        if (sorted_route_items == null)
        {
            return null;
        }
        return sorted_route_items.get_current_position();
    }
    private final InteractiveActionThread thread;
    private final RoutingBoard routing_board;
    private ReadSortedRouteItems sorted_route_items;
    private boolean use_increased_ripup_costs; // in the first passes the ripup costs are icreased for better performance.
    private double min_cumulative_trace_length_before = 0;
    private static int MAX_AUTOROUTE_PASSES = 6;
    private static int ADDITIONAL_RIPUP_COST_FACTOR_AT_START = 10;

    /**
     *  Reads the vias and traces on the board in ascending x order.
     *  Because the vias and traces on the board change while optimizing the item list
     *  of the board is read from scratch each time the next route item is returned.
     */
    private class ReadSortedRouteItems
    {

        ReadSortedRouteItems()
        {
            min_item_coor = new FloatPoint(Integer.MIN_VALUE, Integer.MIN_VALUE);
            min_item_layer = -1;
        }

        Item next()
        {
            Item result = null;
            FloatPoint curr_min_coor = new FloatPoint(Integer.MAX_VALUE, Integer.MAX_VALUE);
            int curr_min_layer = Integer.MAX_VALUE;
            Iterator<UndoableObjects.UndoableObjectNode> it = routing_board.item_list.start_read_object();
            for (;;)
            {
                UndoableObjects.Storable curr_item = routing_board.item_list.read_object(it);
                if (curr_item == null)
                {
                    break;
                }
                if (curr_item instanceof Via)
                {
                    Via curr_via = (Via) curr_item;
                    if (!curr_via.is_user_fixed())
                    {
                        FloatPoint curr_via_center = curr_via.get_center().to_float();
                        int curr_via_min_layer = curr_via.first_layer();
                        if (curr_via_center.x > min_item_coor.x ||
                                curr_via_center.x == min_item_coor.x && (curr_via_center.y > min_item_coor.y || curr_via_center.y == min_item_coor.y && curr_via_min_layer > min_item_layer))
                        {
                            if (curr_via_center.x < curr_min_coor.x || curr_via_center.x == curr_min_coor.x && (curr_via_center.y < curr_min_coor.y ||
                                    curr_via_center.y == curr_min_coor.y && curr_via_min_layer < curr_min_layer))
                            {
                                curr_min_coor = curr_via_center;
                                curr_min_layer = curr_via_min_layer;
                                result = curr_via;
                            }
                        }
                    }
                }
            }
            // Read traces last to prefer vias to traces at the same location
            it = routing_board.item_list.start_read_object();
            for (;;)
            {
                UndoableObjects.Storable curr_item = routing_board.item_list.read_object(it);
                if (curr_item == null)
                {
                    break;
                }
                if (curr_item instanceof Trace)
                {
                    Trace curr_trace = (Trace) curr_item;
                    if (!curr_trace.is_shove_fixed())
                    {
                        FloatPoint first_corner = curr_trace.first_corner().to_float();
                        FloatPoint last_corner = curr_trace.last_corner().to_float();
                        FloatPoint compare_corner;
                        if (first_corner.x < last_corner.x ||
                                first_corner.x == last_corner.x && first_corner.y < last_corner.y)
                        {
                            compare_corner = last_corner;
                        }
                        else
                        {
                            compare_corner = first_corner;
                        }
                        int curr_trace_layer = curr_trace.get_layer();
                        if (compare_corner.x > min_item_coor.x ||
                                compare_corner.x == min_item_coor.x && (compare_corner.y > min_item_coor.y || compare_corner.y == min_item_coor.y && curr_trace_layer > min_item_layer))
                        {
                            if (compare_corner.x < curr_min_coor.x || compare_corner.x == curr_min_coor.x &&
                                    (compare_corner.y < curr_min_coor.y || compare_corner.y == curr_min_coor.y && curr_trace_layer < curr_min_layer))
                            {
                                boolean is_connected_to_via = false;
                                Set<Item> trace_contacts = curr_trace.get_normal_contacts();
                                for (Item curr_contact : trace_contacts)
                                {
                                    if (curr_contact instanceof Via && !curr_contact.is_user_fixed())
                                    {
                                        is_connected_to_via = true;
                                        break;
                                    }
                                }
                                if (!is_connected_to_via)
                                {
                                    curr_min_coor = compare_corner;
                                    curr_min_layer = curr_trace_layer;
                                    result = curr_trace;
                                }
                            }
                        }
                    }
                }
            }
            min_item_coor = curr_min_coor;
            min_item_layer = curr_min_layer;
            return result;

        }

        FloatPoint get_current_position()
        {
            return min_item_coor;
        }
        private FloatPoint min_item_coor;
        private int min_item_layer;
    }
}
