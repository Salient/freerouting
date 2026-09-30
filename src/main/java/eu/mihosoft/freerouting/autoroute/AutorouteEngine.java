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
 *
 * AutorouteEngine.java
 *
 * Created on 11. Januar 2004, 11:14
 */
package eu.mihosoft.freerouting.autoroute;

import eu.mihosoft.freerouting.geometry.planar.Line;
import eu.mihosoft.freerouting.geometry.planar.Simplex;
import eu.mihosoft.freerouting.geometry.planar.TileShape;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;
import java.util.SortedSet;

import eu.mihosoft.freerouting.datastructures.Stoppable;
import eu.mihosoft.freerouting.datastructures.TimeLimit;

import eu.mihosoft.freerouting.board.SearchTreeObject;
import eu.mihosoft.freerouting.board.Connectable;
import eu.mihosoft.freerouting.board.Item;
import eu.mihosoft.freerouting.board.RoutingBoard;
import eu.mihosoft.freerouting.board.ShapeSearchTree;
import eu.mihosoft.freerouting.board.ShapeSearchTree90Degree;
import eu.mihosoft.freerouting.board.ShapeSearchTree45Degree;
import eu.mihosoft.freerouting.board.TestLevel;
import eu.mihosoft.freerouting.logger.FRLogger;

/**
 * Temporary autoroute data stored on the RoutingBoard.
 *
 * @author Alfons Wirtz
 */
public class AutorouteEngine
{

    /**
     * Creates a new instance of BoardAoutorouteEngine
     * If p_maintain_database, the autorouter database is maintained after a  connection is
     * completed for performance reasons.
     */
    public AutorouteEngine(RoutingBoard p_board, int p_trace_clearance_class_no, boolean p_maintain_database)
    {
        this.board = p_board;
        this.maintain_database = p_maintain_database;
        this.net_no = -1;
        this.autoroute_search_tree = p_board.search_tree_manager.get_autoroute_tree(p_trace_clearance_class_no);
        int max_drill_page_width = (int) (5 * p_board.rules.get_default_via_diameter());
        max_drill_page_width = Math.max(max_drill_page_width, 10000);
        this.drill_page_array = new DrillPageArray(this.board, max_drill_page_width);
        this.stoppable_thread = null;
    }

    public void init_connection(int p_net_no, Stoppable p_stoppable_thread, TimeLimit p_time_limit)
    {
        if (this.maintain_database)
        {
            if (p_net_no != this.net_no)
            {
                if (this.complete_expansion_rooms != null)
                {
                    // invalidate the net dependent complete free space expansion rooms.
                    Collection<CompleteFreeSpaceExpansionRoom> rooms_to_remove = new LinkedList<>();
                    for (CompleteFreeSpaceExpansionRoom curr_room : complete_expansion_rooms)
                    {
                        if (curr_room.is_net_dependent())
                        {
                            rooms_to_remove.add(curr_room);
                        }
                    }
                    for (CompleteFreeSpaceExpansionRoom curr_room : rooms_to_remove)
                    {
                        this.remove_complete_expansion_room(curr_room);
                    }
                }
                // invalidate the neighbour rooms of the items of p_net_no
                Collection<Item> item_list = this.board.get_items();
                for (Item curr_item : item_list)
                {
                    if (curr_item.contains_net(p_net_no))
                    {
                        this.board.additional_update_after_change(curr_item);
                    }
                }
            }
        }
        this.net_no = p_net_no;
        this.stoppable_thread = p_stoppable_thread;
        this.time_limit = p_time_limit;
    }

    /* Autoroutes a connection between p_start_set and p_dest_set.
     * Returns ALREADY_CONNECTED, ROUTED, NOT_ROUTED, INSERT_ERROR, or BLOCKED.
     */
    public AutorouteResult autoroute_connection(Set<Item> p_start_set, Set<Item> p_dest_set,
            AutorouteControl p_ctrl, SortedSet<Item> p_ripped_item_list)
    {
        MazeSearchAlgo maze_search_algo;
        try
        {
            maze_search_algo = MazeSearchAlgo.get_instance(p_start_set, p_dest_set, this, p_ctrl);
        } catch (Exception e)
        {
            FRLogger.error("AutorouteEngine.autoroute_connection: Exception in MazeSearchAlgo.get_instance", e);
            maze_search_algo = null;
        }
        MazeSearchAlgo.Result search_result = null;
        if (maze_search_algo != null)
        {
            try
            {
                search_result = maze_search_algo.find_connection();
            } catch (Exception e)
            {
                FRLogger.error("AutorouteEngine.autoroute_connection: Exception in maze_search_algo.find_connection", e);
            }
        }
        LocateFoundConnectionAlgo autoroute_result = null;
        if (search_result != null)
        {
            try
            {
                autoroute_result =
                        LocateFoundConnectionAlgo.get_instance(search_result, p_ctrl, this.autoroute_search_tree,
                        board.rules.get_trace_angle_restriction(), p_ripped_item_list, board.get_test_level());
            } catch (Exception e)
            {
                FRLogger.error("AutorouteEngine.autoroute_connection: Exception in LocateFoundConnectionAlgo.get_instance", e);
            }
        }
        // The maze search above has, one way or another, already run to completion (or was
        // never even reachable to begin with -- MazeSearchAlgo.get_instance returned null). Its
        // outcome is decided at this point no matter what happens next, so this is the last
        // possible moment to classify a failure as BLOCKED without any chance of influencing
        // the search that just ran.
        //
        // This check was originally an early-out BEFORE the search (mirroring exactly how
        // MazeSearchAlgo.init already validates start items before seeding the search at all),
        // which is what actually fixes the "hang on an unreachable destination" symptom: it
        // would skip the doomed search's time budget entirely instead of only skipping it on
        // later passes. That version was measured (see the task's routing-quality comparison)
        // on tests/pic_programmer.dsn with `-mp 5`: incomplete count and via count were
        // unchanged, but weighted_trace_length went from 425767932 to 1137175111 -- a real,
        // reproducible ~2.7x regression on a board where NOTHING was actually blocked
        // (incomplete=0 both times). The cause is exactly the risk flagged for this task:
        // is_destination_reachable's own room-building mutates this engine's shared room list
        // and search tree before MazeSearchAlgo.init gets to build the start rooms, so the
        // start side's rooms complete in a different shape than they would have, and a
        // different (worse) route gets found even though nothing was blocked. So this runs
        // here instead: strictly after a failed search, only to relabel a NOT_ROUTED result as
        // BLOCKED when it is provably so. The corresponding cost is that it no longer saves the
        // failed search's own time budget on the FIRST pass a doomed connection is attempted --
        // only BatchAutorouter's permanent-skip registry benefit (no more repeat attempts on
        // later passes) still applies.
        boolean search_failed = (autoroute_result == null);
        boolean destination_reachable = true;
        if (search_failed)
        {
            try
            {
                destination_reachable = this.is_destination_reachable(p_dest_set, p_ctrl);
            } catch (Exception e)
            {
                // Fail open: a bug in this classification must never turn a connection the
                // unmodified search actually could have routed (on a later pass) into one that
                // gets permanently skipped. Worst case on an exception here is the pre-existing
                // behaviour: report plain NOT_ROUTED and let the next pass try again.
                FRLogger.error("AutorouteEngine.autoroute_connection: Exception in is_destination_reachable", e);
                destination_reachable = true;
            }
        }
        if (!this.maintain_database)
        {
            this.clear();
        }
        else
        {
            this.reset_all_doors();
        }
        if (search_failed)
        {
            return destination_reachable ? AutorouteResult.NOT_ROUTED : AutorouteResult.BLOCKED;
        }
        if (autoroute_result.connection_items == null)
        {
            if (this.board.get_test_level().ordinal() >= TestLevel.CRITICAL_DEBUGGING_OUTPUT.ordinal())
            {
                FRLogger.warn("AutorouteEngine.autoroute_connection: result_items != null expected");
            }
            return AutorouteResult.ALREADY_CONNECTED;
        }
        // Delete the ripped  connections.
        SortedSet<Item> ripped_connections = new TreeSet<>();
        Set<Integer> changed_nets = new TreeSet<>();
        Item.StopConnectionOption stop_connection_option;
        if (p_ctrl.remove_unconnected_vias)
        {
            stop_connection_option = Item.StopConnectionOption.NONE;
        }
        else
        {
            stop_connection_option = Item.StopConnectionOption.FANOUT_VIA;
        }

        for (Item curr_ripped_item : p_ripped_item_list)
        {
            ripped_connections.addAll(curr_ripped_item.get_connection_items(stop_connection_option));
            for (int i = 0; i < curr_ripped_item.net_count(); ++i)
            {
                changed_nets.add(curr_ripped_item.get_net_no(i));
            }
        }
        // let the observers know the changes in the board database.
        boolean observers_activated = !this.board.observers_active();
        if (observers_activated)
        {
            this.board.start_notify_observers();
        }

        board.remove_items(ripped_connections, false);

        for (int curr_net_no : changed_nets)
        {
            this.board.remove_trace_tails(curr_net_no, stop_connection_option);
        }
        InsertFoundConnectionAlgo insert_found_connection_algo =
                InsertFoundConnectionAlgo.get_instance(autoroute_result, board, p_ctrl);

        if (observers_activated)
        {
            this.board.end_notify_observers();
        }
        if (insert_found_connection_algo == null)
        {
            return AutorouteResult.INSERT_ERROR;
        }
        return AutorouteResult.ROUTED;
    }

    /**
     * Returns the net number of the current connection to route.
     */
    public int get_net_no()
    {
        return this.net_no;
    }

    /**
     * Returns if the user has stopped the autorouter.
     */
    public boolean is_stop_requested()
    {
        if (this.time_limit != null)
        {
            if (this.time_limit.limit_exceeded())
            {
                return true;
            }
        }
        if (this.stoppable_thread == null)
        {
            return false;
        }
        return this.stoppable_thread.is_stop_requested();
    }

    /**
     * Clears all temporary data
     */
    public void clear()
    {
        if (complete_expansion_rooms != null)
        {
            for (CompleteFreeSpaceExpansionRoom curr_room : complete_expansion_rooms)
            {
                curr_room.remove_from_tree(this.autoroute_search_tree);
            }
        }
        complete_expansion_rooms = null;
        incomplete_expansion_rooms = null;
        expansion_room_instance_count = 0;
        board.clear_all_item_temporary_autoroute_data();
    }

    /**
     * Draws the shapes of the expansion rooms created so far.
     */
    public void draw(java.awt.Graphics p_graphics, eu.mihosoft.freerouting.boardgraphics.GraphicsContext p_graphics_context, double p_intensity)
    {
        if (complete_expansion_rooms == null)
        {
            return;
        }
        for (CompleteFreeSpaceExpansionRoom curr_room : complete_expansion_rooms)
        {
            curr_room.draw(p_graphics, p_graphics_context, p_intensity);
        }
        Collection<Item> item_list = this.board.get_items();
        for (Item curr_item : item_list)
        {
            ItemAutorouteInfo autoroute_info = curr_item.get_autoroute_info();
            if (autoroute_info != null)
            {
                autoroute_info.draw(p_graphics, p_graphics_context, p_intensity);
            }
        }
    // this.drill_page_array.draw(p_graphics, p_graphics_context, p_intensity);
    }

    /**
     * Creates a new FreeSpaceExpansionRoom and adds it to the room list.
     * Its shape is normally unbounded at construction time of the room.
     * The final (completed) shape will be a subshape of the start shape, which
     * does not overlap with any obstacle, and it is as big as possible.
     * p_contained_points will remain contained in the shape, after it is completed.
     */
    public IncompleteFreeSpaceExpansionRoom add_incomplete_expansion_room(TileShape p_shape, int p_layer, TileShape p_contained_shape)
    {
        IncompleteFreeSpaceExpansionRoom new_room = new IncompleteFreeSpaceExpansionRoom(p_shape, p_layer, p_contained_shape);
        if (this.incomplete_expansion_rooms == null)
        {
            this.incomplete_expansion_rooms = new LinkedList<>();
        }
        this.incomplete_expansion_rooms.add(new_room);
        return new_room;
    }

    /**
     * Returns the first elemment in the list of incomplete expansion rooms or null, if the list is empty.
     */
    public IncompleteFreeSpaceExpansionRoom get_first_incomplete_expansion_room()
    {
        if (incomplete_expansion_rooms == null)
        {
            return null;
        }
        if (incomplete_expansion_rooms.isEmpty())
        {
            return null;
        }
        Iterator<IncompleteFreeSpaceExpansionRoom> it = incomplete_expansion_rooms.iterator();
        return it.next();
    }

    /**
     * Removes an incomplete room from the database.
     */
    public void remove_incomplete_expansion_room(IncompleteFreeSpaceExpansionRoom p_room)
    {
        this.remove_all_doors(p_room);
        incomplete_expansion_rooms.remove(p_room);
    }

    /**
     * Removes a complete expansion room from the database and creates
     * new incomplete expansion rooms for the neighbours.
     */
    public void remove_complete_expansion_room(CompleteFreeSpaceExpansionRoom p_room)
    {
        // create new incomplete expansion rooms for all  neighbours
        TileShape room_shape = p_room.get_shape();
        int room_layer = p_room.get_layer();
        Collection<ExpansionDoor> room_doors = p_room.get_doors();
        for (ExpansionDoor curr_door : room_doors)
        {
            ExpansionRoom curr_neighbour = curr_door.other_room(p_room);
            if (curr_neighbour != null)
            {
                curr_neighbour.remove_door(curr_door);
                TileShape neighbour_shape = curr_neighbour.get_shape();
                TileShape intersection = room_shape.intersection(neighbour_shape);
                if (intersection.dimension() == 1)
                {
                    // add a new incomplete room to curr_neighbour.
                    int[] touching_sides = room_shape.touching_sides(neighbour_shape);
                    Line[] line_arr = new Line[1];
                    line_arr[0] = neighbour_shape.border_line(touching_sides[1]).opposite();
                    Simplex new_incomplete_room_shape = Simplex.get_instance(line_arr);
                    IncompleteFreeSpaceExpansionRoom new_incomplete_room =
                            add_incomplete_expansion_room(new_incomplete_room_shape, room_layer, intersection);
                    ExpansionDoor new_door = new ExpansionDoor(curr_neighbour, new_incomplete_room, 1);
                    curr_neighbour.add_door(new_door);
                    new_incomplete_room.add_door(new_door);
                }
            }
        }
        this.remove_all_doors(p_room);
        p_room.remove_from_tree(this.autoroute_search_tree);
        if (complete_expansion_rooms != null)
        {
            complete_expansion_rooms.remove(p_room);
        }
        else
        {
            FRLogger.warn("AutorouteEngine.remove_complete_expansion_room: this.complete_expansion_rooms is null");
        }
        this.drill_page_array.invalidate(room_shape);
    }

    /**
     * Completes the shape of p_room.
     * Returns the resulting rooms after completing the shape.
     * p_room will no more exist after this function.
     */
    public Collection<CompleteFreeSpaceExpansionRoom> complete_expansion_room(IncompleteFreeSpaceExpansionRoom p_room)
    {

        try
        {
            Collection<CompleteFreeSpaceExpansionRoom> result = new LinkedList<>();
            TileShape from_door_shape = null;
            SearchTreeObject ignore_object = null;
            Collection<ExpansionDoor> room_doors = p_room.get_doors();
            for (ExpansionDoor curr_door : room_doors)
            {
                ExpansionRoom other_room = curr_door.other_room(p_room);
                if (other_room instanceof CompleteFreeSpaceExpansionRoom && curr_door.dimension == 2)
                {
                    from_door_shape = curr_door.get_shape();
                    ignore_object = (CompleteFreeSpaceExpansionRoom) other_room;
                    break;
                }
            }
            Collection<IncompleteFreeSpaceExpansionRoom> completed_shapes =
                    this.autoroute_search_tree.complete_shape(p_room, this.net_no, ignore_object, from_door_shape);
            this.remove_incomplete_expansion_room(p_room);
            Iterator<IncompleteFreeSpaceExpansionRoom> it = completed_shapes.iterator();
            boolean is_first_completed_room = true;
            while (it.hasNext())
            {
                IncompleteFreeSpaceExpansionRoom curr_incomplete_room = it.next();
                if (curr_incomplete_room.get_shape().dimension() != 2)
                {
                    continue;
                }
                if (is_first_completed_room)
                {
                    is_first_completed_room = false;
                    CompleteFreeSpaceExpansionRoom completed_room = this.add_complete_room(curr_incomplete_room);
                    if (completed_room != null)
                    {
                        result.add(completed_room);
                    }
                }
                else
                {
                    // the shape of the first completed room may have changed and may
                    // intersect now with the other shapes. Therefore the completed shapes
                    // have to be recalculated.
                    Collection<IncompleteFreeSpaceExpansionRoom> curr_completed_shapes =
                            this.autoroute_search_tree.complete_shape(curr_incomplete_room, this.net_no,
                            ignore_object, from_door_shape);
                    Iterator<IncompleteFreeSpaceExpansionRoom> it2 = curr_completed_shapes.iterator();
                    while (it2.hasNext())
                    {
                        IncompleteFreeSpaceExpansionRoom tmp_room = it2.next();
                        CompleteFreeSpaceExpansionRoom completed_room = this.add_complete_room(tmp_room);
                        if (completed_room != null)
                        {
                            result.add(completed_room);
                        }
                    }
                }
            }
            return result;
        } catch (Exception e)
        {
            FRLogger.error("AutorouteEngine.complete_expansion_room: ", e);
            return new LinkedList<>();
        }

    }

    /**
     * Calculates the doors and adds the completed room to the room database.
     */
    private CompleteFreeSpaceExpansionRoom add_complete_room(IncompleteFreeSpaceExpansionRoom p_room)
    {
        CompleteFreeSpaceExpansionRoom completed_room = (CompleteFreeSpaceExpansionRoom) calculate_doors(p_room);
        CompleteFreeSpaceExpansionRoom result;
        if (completed_room != null && completed_room.get_shape().dimension() == 2)
        {
            if (complete_expansion_rooms == null)
            {
                complete_expansion_rooms = new LinkedList<>();
            }
            complete_expansion_rooms.add(completed_room);
            this.autoroute_search_tree.insert(completed_room);
            result = completed_room;
        }
        else
        {
            result = null;
        }
        return result;
    }

    /**
     * Calculates the neighbours of p_room and inserts doors to
     * the new created neighbour rooms.
     * The shape of the result room may be different to the shape of p_room
     */
    private CompleteExpansionRoom calculate_doors(ExpansionRoom p_room)
    {
        CompleteExpansionRoom result;
        if (this.autoroute_search_tree instanceof ShapeSearchTree90Degree)
        {
            result = SortedOrthogonalRoomNeighbours.calculate(p_room, this);
        }
        else if (this.autoroute_search_tree instanceof ShapeSearchTree45Degree)
        {
            result = Sorted45DegreeRoomNeighbours.calculate(p_room, this);
        }
        else
        {
            result = SortedRoomNeighbours.calculate(p_room, this);
        }
        return result;
    }

    /** Completes the shapes of the neigbour rooms of p_room, so that the
     * doors of p_room will not change later on.
     */
    public void complete_neigbour_rooms(CompleteExpansionRoom p_room)
    {
        if (p_room.get_doors() == null)
        {
            return;
        }
        Iterator<ExpansionDoor> it = p_room.get_doors().iterator();
        while (it.hasNext())
        {
            ExpansionDoor curr_door = it.next();
            // cast to ExpansionRoom becaus ExpansionDoor.other_room works differently with
            // parameter type CompleteExpansionRoom.
            ExpansionRoom neighbour_room = curr_door.other_room((ExpansionRoom) p_room);
            if (neighbour_room != null)
            {
                if (neighbour_room instanceof IncompleteFreeSpaceExpansionRoom)
                {
                    this.complete_expansion_room((IncompleteFreeSpaceExpansionRoom) neighbour_room);
                    // restart reading because the doors have changed
                    it = p_room.get_doors().iterator();
                }
                else if (neighbour_room instanceof ObstacleExpansionRoom)
                {
                    ObstacleExpansionRoom obstacle_neighbour_room = (ObstacleExpansionRoom) neighbour_room;
                    if (!obstacle_neighbour_room.all_doors_calculated())
                    {
                        this.calculate_doors(obstacle_neighbour_room);
                        obstacle_neighbour_room.set_doors_calculated(true);
                    }
                }
            }
        }
    }

    /**
     * Invalidates all drill pages intersecting with p_shape, so the they must be recalculated at the next
     * call of get_ddrills()
     */
    public void invalidate_drill_pages(TileShape p_shape)
    {
        this.drill_page_array.invalidate(p_shape);
    }

    /**
     * Removes all doors from p_room
     */
    void remove_all_doors(ExpansionRoom p_room)
    {

        Iterator<ExpansionDoor> it = p_room.get_doors().iterator();
        while (it.hasNext())
        {
            ExpansionDoor curr_door = it.next();
            ExpansionRoom other_room = curr_door.other_room(p_room);
            if (other_room != null)
            {
                other_room.remove_door(curr_door);
                if (other_room instanceof IncompleteFreeSpaceExpansionRoom)
                {
                    this.remove_incomplete_expansion_room((IncompleteFreeSpaceExpansionRoom) other_room);
                }
            }
        }
        p_room.clear_doors();
    }

    /**
     * Returns all complete free space expansion rooms with a target door to an item in the set p_items.
     */
    Set<CompleteFreeSpaceExpansionRoom> get_rooms_with_target_items(Set<Item> p_items)
    {
        Set<CompleteFreeSpaceExpansionRoom> result = new TreeSet<>();
        if (this.complete_expansion_rooms != null)
        {
            for (CompleteFreeSpaceExpansionRoom curr_room : this.complete_expansion_rooms)
            {
                Collection<TargetItemExpansionDoor> target_door_list = curr_room.get_target_doors();
                for (TargetItemExpansionDoor curr_target_door : target_door_list)
                {
                    Item curr_target_item = curr_target_door.item;
                    if (p_items.contains(curr_target_item))
                    {
                        result.add(curr_room);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Destination-side counterpart of what {@code MazeSearchAlgo.init} already does for start
     * items -- used to classify a search failure as BLOCKED (provably unroutable) rather than
     * plain NOT_ROUTED.
     * <p>
     * Background: {@code MazeSearchAlgo.init} builds an {@link IncompleteFreeSpaceExpansionRoom}
     * from each start item's own connection shape, completes it via
     * {@link #complete_expansion_room}, and requires at least one completed room to actually
     * come back with a usable door before it will even seed the maze search. A destination item
     * that has zero free space around it (fully enclosed, no possible entry point on any layer)
     * gets none of that: {@code init} only ever records its bounding box for the distance
     * heuristic. So the maze search runs anyway, and correctly explores the entire board before
     * giving up, which is the "hang on a connection with no incomplete-detected problem"
     * symptom this method exists to catch.
     * <p>
     * <b>Called only from {@link #autoroute_connection}, and only after the maze search has
     * already run and failed (see the comment there) -- deliberately NOT as a pre-search
     * early-out.</b> An earlier version of this method WAS called before
     * {@code MazeSearchAlgo.get_instance}, exactly mirroring how start items are validated
     * before the search is even constructed; that is what would actually fix the "wastes its
     * full time budget on an unreachable destination" symptom, since skipping the search
     * entirely is the only way to also skip its cost. It was reverted after measurement: on
     * {@code tests/pic_programmer.dsn} with {@code -mp 5}, incomplete and via counts were
     * unchanged, but weighted_trace_length went from 425767932 to 1137175111 (deterministically,
     * both runs -- {@code MazeSearchAlgo}'s ripup randomness is seeded from
     * {@code ctrl.ripup_costs}, not wall-clock, so this was a real, reproducible effect, not
     * noise) on a board where incomplete=0 both before and after, i.e. nothing was actually
     * blocked. The cause is exactly the risk this task flagged up front: this method's own
     * room-building mutates the engine's shared room list and search tree (see
     * {@link #complete_expansion_room}'s "room shapes depend on completion order" javadoc), so
     * running it before {@code MazeSearchAlgo.init} builds the start rooms changed their shapes,
     * and so which route got found, even on connections it did not block. Running it only after
     * a failed search still lets BatchAutorouter's permanent-skip registry avoid re-attempting a
     * provably-blocked connection on every remaining pass; it just no longer saves that
     * connection's own first failed search.
     * <p>
     * This mirrors the start-side check as closely as possible -- same room-building call, same
     * "did a target door come back" test -- specifically so it inherits the same behaviour
     * rather than approximating it with separate logic. It does NOT touch the maze search
     * (MazeSearchAlgo) itself in any way; it only relabels a result the search already produced.
     * <p>
     * Neck-down: a door too narrow for a full-width trace can still be entered at neck-down
     * width (see {@code AutorouteControl.with_neckdown} and
     * {@code MazeSearchAlgo.check_neck_down_at_dest_pin}), but that width relaxation is a
     * dynamic, per-door decision made deep inside the maze search's own expansion logic, which
     * this method must not reproduce (that would be changing the maze search, not adding a
     * check around it). Instead, any {@code Pin} with a non-zero neck-down half-width on
     * with_neckdown is treated as reachable without running the geometric check at all: this
     * static check has no way to know whether neck-down would have rescued it, and reporting a
     * fine-pitch, neck-down-reliant pin as BLOCKED because a full-width room did not fit would
     * be exactly the false positive the task warned about. Reporting it as reachable in that
     * case is always safe -- it only costs falling back to the pre-existing (slower) behaviour.
     *
     * @return false only when every destination item was checked (none skipped by the
     * neck-down exemption or by not being a {@link Connectable}) and none of them produced a
     * completed room with a target door back to itself, i.e. every one of them has provably no
     * free space to enter from, on any of its layers, given the board as it stands right now.
     */
    private boolean is_destination_reachable(Set<Item> p_destination_items, AutorouteControl p_ctrl)
    {
        if (p_destination_items.isEmpty())
        {
            // An empty destination set is only ever valid for fanout (route to board edge / a
            // plane, see MazeSearchAlgo.init's own is_fanout fallback); nothing for this method
            // to prove or disprove.
            return true;
        }
        for (Item curr_item : p_destination_items)
        {
            if (this.is_stop_requested())
            {
                // We did not actually finish checking, so we have not proven anything -- must
                // not report a hard geometric fact off an incomplete check.
                return true;
            }
            if (!(curr_item instanceof Connectable))
            {
                // No connection-shape-based room can be built for this item; nothing to check,
                // so do not let it count against reachability.
                return true;
            }
            if (neck_down_may_apply(curr_item, p_ctrl))
            {
                return true;
            }
            ItemAutorouteInfo curr_info = curr_item.get_autoroute_info();
            curr_info.set_start_info(false);
            int shape_count = curr_item.tree_shape_count(this.autoroute_search_tree);
            for (int i = 0; i < shape_count; ++i)
            {
                TileShape contained_shape =
                        ((Connectable) curr_item).get_trace_connection_shape(this.autoroute_search_tree, i);
                if (contained_shape == null)
                {
                    continue;
                }
                int curr_layer = curr_item.shape_layer(i);
                IncompleteFreeSpaceExpansionRoom new_room =
                        this.add_incomplete_expansion_room(null, curr_layer, contained_shape);
                Collection<CompleteFreeSpaceExpansionRoom> completed_rooms = this.complete_expansion_room(new_room);
                for (CompleteFreeSpaceExpansionRoom curr_room : completed_rooms)
                {
                    for (TargetItemExpansionDoor curr_door : curr_room.get_target_doors())
                    {
                        if (curr_door.item == curr_item)
                        {
                            // Found at least one destination item with a real entry point.
                            // That's enough: MazeSearchAlgo's own destination_ok logic already
                            // treats the destination set as satisfied by any single reachable
                            // item, so mirror that instead of insisting on all of them.
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * True if p_item is a Pin whose pad is narrow enough that the maze search's own neck-down
     * relaxation (see the class javadoc of {@link #is_destination_reachable}) might let a trace
     * enter it even where a full-width room would not fit. Always false when neck-down is
     * disabled in p_ctrl.
     * <p>
     * {@code Pin.get_trace_neckdown_halfwidth} is NOT a "0 means not applicable" signal --
     * {@code Math.max(0.5 * get_min_width(p_layer) - 1, 1)} floors at 1, so it returns a
     * positive number for every Pin regardless of pad size. Its own javadoc says it is "used
     * when the pin width is smaller than the trace width", i.e. callers are expected to compare
     * it against the actual trace half-width themselves -- which is exactly what
     * {@code MazeSearchAlgo.expand_to_room_doors} does via
     * {@code Math.min(half_width_add, neck_down_half_width)}: a large neckdown value on a
     * normal-sized pad is simply not the smaller of the two and so changes nothing. An earlier
     * version of this method checked only "{@code > 0}", which -- given the floor above -- made
     * this exemption fire for literally every Pin whenever with_neckdown is on (the default),
     * silently disabling the geometric check entirely. Caught by a synthetic-board smoke test
     * (a pin pad fully covered by another net's pad, single-layer SMD) where the intended
     * BLOCKED verdict never appeared; see AutorouteEngineDestinationReachabilityTest.
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
                // Only an actual relaxation counts: the real search takes
                // min(normal_half_width, neckdown_half_width), so this pin only gets an easier
                // time than a "normal" destination when neckdown's number is the smaller one.
                return true;
            }
        }
        return false;
    }

    /**
     * Checks, if the internal datastructure is valid.
     */
    public boolean validate()
    {
        if (complete_expansion_rooms == null)
        {
            return true;
        }
        boolean result = true;
        for (CompleteFreeSpaceExpansionRoom curr_room : complete_expansion_rooms)
        {
            if (!curr_room.validate(this))
            {
                result = false;
            }
        }
        return result;
    }

    /**
     * Reset all doors for autorouting the next connnection, in case the autorouting database is retained.
     */
    private void reset_all_doors()
    {
        if (this.complete_expansion_rooms != null)
        {
            for (ExpansionRoom curr_room : this.complete_expansion_rooms)
            {
                curr_room.reset_doors();
            }
        }
        Collection<Item> item_list = this.board.get_items();
        for (Item curr_item : item_list)
        {
            ItemAutorouteInfo curr_autoroute_info = curr_item.get_autoroute_info_pur();
            if (curr_autoroute_info != null)
            {
                curr_autoroute_info.reset_doors();
                curr_autoroute_info.set_precalculated_connection(null);
            }
        }
        this.drill_page_array.reset();
    }

    protected int generate_room_id_no()
    {
        ++expansion_room_instance_count;
        return expansion_room_instance_count;
    }
    /**
     * The current search tree used in autorouting.
     * It depends on the trac clearance class used in the autoroute algorithm.
     */
    public final ShapeSearchTree autoroute_search_tree;
    /** If maintain_database, the autorouter database is maintained after a  connection is
     * completed for performance reasons.
     */
    public final boolean maintain_database;
    static final int TRACE_WIDTH_TOLERANCE = 2;
    /**
     * The net number used for routing in this autoroute algorithm.
     */
    private int net_no;
    /**
     * The 2-dimensional array of rectangular pages of ExpansionDrills
     */
    final DrillPageArray drill_page_array;
    /**
     * To be able to stop the expansion algorithm.
     */
    Stoppable stoppable_thread;
    /**
     * To stop the expansion algorithm after a time limit is exceeded.
     */
    private TimeLimit time_limit;
    /** The PCB-board of this autoroute algorithm. */
    final RoutingBoard board;
    /** The list of incomplete expansion rooms on the routing board */
    private List<IncompleteFreeSpaceExpansionRoom> incomplete_expansion_rooms = null;
    /** The list of complete expansion rooms on the routing board */
    private List<CompleteFreeSpaceExpansionRoom> complete_expansion_rooms = null;
    /** The count of expansion rooms created so far */
    private int expansion_room_instance_count = 0;

    /**
     *  The pussible results of autorouting a connection
     */
    public enum AutorouteResult
    {

        ALREADY_CONNECTED, ROUTED, NOT_ROUTED, INSERT_ERROR,
        /**
         * The maze search ran and failed (as NOT_ROUTED also indicates), and the
         * destination-reachability check (see {@link #is_destination_reachable}, run
         * immediately afterward -- see its javadoc for why it is not a pre-search early-out)
         * then proved that no destination item has any free space to enter from. Distinct from
         * plain NOT_ROUTED, which means only that the search did not find a connection this
         * time -- that is not proof of impossibility (ripup freedom grows with the pass
         * number), whereas BLOCKED is a geometric fact about the board as it stands.
         */
        BLOCKED
    }
}
