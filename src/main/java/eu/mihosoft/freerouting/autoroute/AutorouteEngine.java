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
        // Stage 2 (experimental, see the task report): a pre-search connectivity check on a
        // throwaway AutorouteEngine, so that a provably-blocked connection can skip the maze
        // search's own time budget entirely -- not just avoid repeat attempts on later passes,
        // which is all the post-failure placement below can do. Deliberately a SEPARATE engine
        // instance, not `this`: the whole reason Stage 1 runs after the search instead of before
        // (see the big comment below) is that building rooms off the destination side first
        // reshapes the start side's rooms, because room shapes depend on completion order. A
        // throwaway engine builds its own rooms in its own private room list, backed by the same
        // shared, cached-per-clearance-class search tree (SearchTreeManager.get_autoroute_tree)
        // -- but then immediately tears them back out via its own clear() before `this` engine
        // or MazeSearchAlgo ever touches the tree, so (if clear() is as complete as it looks)
        // there should be nothing left for the real search to see.
        if (STAGE_2_PRE_SEARCH_CHECK_ENABLED)
        {
            try
            {
                AutorouteEngine throwaway_engine = new AutorouteEngine(this.board, p_ctrl.trace_clearance_class_no, false);
                // Needed for the tree lookups inside RoomReachabilityAlgo.check to use the right
                // net: a fresh AutorouteEngine's net_no defaults to -1 until init_connection sets
                // it, and -1 never matches any item's real net, which would make every
                // contains_net/is_trace_obstacle check come out wrong.
                throwaway_engine.init_connection(p_ctrl.net_no, this.stoppable_thread, this.time_limit);
                RoomReachabilityAlgo.Verdict pre_search_verdict =
                        RoomReachabilityAlgo.check(p_start_set, p_dest_set, throwaway_engine, p_ctrl);
                throwaway_engine.clear();
                if (pre_search_verdict != RoomReachabilityAlgo.Verdict.REACHABLE)
                {
                    this.last_blocked_reason = pre_search_verdict;
                    return AutorouteResult.BLOCKED;
                }
            } catch (Exception e)
            {
                // Fail open, same reasoning as the post-search placement below: never let a bug
                // here turn a connection the unmodified search could have routed into one that
                // gets permanently skipped. Just fall through to the normal search.
                FRLogger.error("AutorouteEngine.autoroute_connection: Exception in Stage 2 pre-search RoomReachabilityAlgo.check", e);
            }
        }

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
        // room-building mutates this engine's shared room list and search tree before
        // MazeSearchAlgo.init gets to build the start rooms, so the start side's rooms complete
        // in a different shape than they would have, and a different (worse) route gets found
        // even though nothing was blocked. So this runs here instead: strictly after a failed
        // search, only to relabel a NOT_ROUTED result as BLOCKED when it is provably so. The
        // corresponding cost is that it no longer saves the failed search's own time budget on
        // the FIRST pass a doomed connection is attempted -- only BatchAutorouter's
        // permanent-skip registry benefit (no more repeat attempts on later passes) still
        // applies.
        //
        // RoomReachabilityAlgo.check (task 10) subsumes what used to be a pad-adjacency-only
        // check here (is_destination_reachable): it still answers "does any destination item
        // have free space to enter from", but if so it keeps going, flood-filling the expansion
        // room graph through doors to see whether that free space actually connects anywhere
        // back to a start item, rather than stopping at the first door. See its class javadoc.
        boolean search_failed = (autoroute_result == null);
        RoomReachabilityAlgo.Verdict reachability = RoomReachabilityAlgo.Verdict.REACHABLE;
        if (search_failed)
        {
            try
            {
                reachability = RoomReachabilityAlgo.check(p_start_set, p_dest_set, this, p_ctrl);
            } catch (Exception e)
            {
                // Fail open: a bug in this classification must never turn a connection the
                // unmodified search actually could have routed (on a later pass) into one that
                // gets permanently skipped. Worst case on an exception here is the pre-existing
                // behaviour: report plain NOT_ROUTED and let the next pass try again.
                FRLogger.error("AutorouteEngine.autoroute_connection: Exception in RoomReachabilityAlgo.check", e);
                reachability = RoomReachabilityAlgo.Verdict.REACHABLE;
            }
            this.last_blocked_reason = reachability;
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
            boolean destination_reachable = (reachability == RoomReachabilityAlgo.Verdict.REACHABLE);
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
     * Why the most recent {@link #autoroute_connection} call that returned
     * {@code AutorouteResult.BLOCKED} was classified that way. Only meaningful immediately after
     * such a call -- {@code BatchAutorouter} reads it right there to put a more specific reason
     * in its log line (see {@code BatchAutorouter.note_blocked_connection}). Not reset between
     * calls, so callers must not read it after a call that did NOT return BLOCKED.
     */
    public RoomReachabilityAlgo.Verdict get_last_blocked_reason()
    {
        return this.last_blocked_reason;
    }

    private RoomReachabilityAlgo.Verdict last_blocked_reason = RoomReachabilityAlgo.Verdict.REACHABLE;

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
     * Switch for Stage 2 (see the comment at the top of {@link #autoroute_connection}). Enabled:
     * the acceptance test passed. With this flipped on, BoardScore (incomplete count, via count,
     * weighted trace length, corner count) AND the produced .rte came back byte-for-byte
     * identical to the unmodified baseline on all 4 of the task's sample boards (pic_programmer,
     * Issue22-AutoRouter_interrupted, Issue29-hw48na, Issue15-StackOverflow) at {@code -mp 5} --
     * i.e. {@link AutorouteEngine#clear()} on a throwaway engine really does leave no trace in
     * the shared, cached-per-clearance-class search tree, confirming empirically what the task
     * description only hypothesized. Wall-clock time across those 4 runs also came back within
     * normal run-to-run variance of the Stage-1-only numbers (no measured slowdown from running
     * this on every connection attempt, not just failed ones) -- see the task report for the
     * actual before/after numbers on both counts.
     */
    static final boolean STAGE_2_PRE_SEARCH_CHECK_ENABLED = true;
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
         * The maze search ran and failed (as NOT_ROUTED also indicates), and the room-graph
         * connectivity check (see {@link RoomReachabilityAlgo}, run immediately afterward -- see
         * the comment in {@link #autoroute_connection} for why it is not a pre-search early-out)
         * then proved either that no destination item has any free space to enter from, or that
         * it does but no chain of doors connects that free space to any start item. Distinct
         * from plain NOT_ROUTED, which means only that the search did not find a connection this
         * time -- that is not proof of impossibility (ripup freedom grows with the pass
         * number), whereas BLOCKED is a geometric fact about the board as it stands. See
         * {@link #get_last_blocked_reason()} for which of the two it was.
         */
        BLOCKED
    }
}
