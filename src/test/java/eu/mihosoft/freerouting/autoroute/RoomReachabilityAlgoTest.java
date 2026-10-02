package eu.mihosoft.freerouting.autoroute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import eu.mihosoft.freerouting.board.SearchTreeObject;
import eu.mihosoft.freerouting.geometry.planar.TileShape;

import static org.junit.Assert.assertEquals;

/**
 * Covers {@link RoomReachabilityAlgo#flood_fill}, the pure breadth-first search over the
 * expansion-room graph, with synthetic room graphs built from {@link FakeCompleteExpansionRoom}
 * -- no real board, {@code ShapeSearchTree}, or {@code AutorouteEngine} involved. This is
 * deliberately possible because {@code flood_fill} itself does not depend on any of those; the
 * only board-shaped thing it touches is {@link RoomReachabilityAlgo.RoomExpander}, which these
 * tests fake too (see {@link #lazy_expansion_is_read_after_expand_is_called}).
 * <p>
 * What these tests are specifically here to pin down, per the task's hard requirements:
 * <ul>
 * <li>this is a plain multi-hop BFS over doors, not a single-ring adjacency check
 * ({@link #multi_hop_chain_is_found});
 * <li>a room with no door onto it (how the real room-building code represents an immovable
 * obstacle -- see {@code SortedRoomNeighbours.calculate}'s {@code is_route()} guard) correctly
 * blocks the fill ({@link #missing_door_blocks_the_fill});
 * <li>a room that DOES have doors on both sides (how the real code represents a rippable
 * obstacle room) is walked straight through, not treated as a wall
 * ({@link #passable_room_in_the_middle_of_a_chain_does_not_block_it});
 * <li>both caps -- room count and the wall-clock budget supplier -- stop the fill and report
 * BUDGET_EXCEEDED rather than EXHAUSTED or FOUND, which is what lets the caller fail open
 * instead of reporting a false BLOCKED ({@link #room_cap_is_enforced},
 * {@link #time_budget_is_checked_before_expanding_the_frontier}).
 * </ul>
 */
public class RoomReachabilityAlgoTest
{
    private static final RoomReachabilityAlgo.RoomExpander NO_OP_EXPANDER = room ->
    {
        // Most tests build the whole graph up front, so there is nothing left to lazily expand.
    };

    private static void link(FakeCompleteExpansionRoom p_a, FakeCompleteExpansionRoom p_b)
    {
        ExpansionDoor door = new ExpansionDoor(p_a, p_b, 1);
        p_a.add_door(door);
        p_b.add_door(door);
    }

    @Test
    public void seed_room_itself_satisfying_the_target_is_found_immediately()
    {
        FakeCompleteExpansionRoom seed = new FakeCompleteExpansionRoom("seed");

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Collections.singletonList((CompleteExpansionRoom) seed), room -> room == seed, NO_OP_EXPANDER,
                1000, () -> false);

        assertEquals(RoomReachabilityAlgo.FloodResult.FOUND, result);
    }

    @Test
    public void multi_hop_chain_is_found()
    {
        // A -- B -- C -- D, seeded at A, target is D: proves this walks multiple doors, not just
        // the first ring of neighbours (that was the old is_destination_reachable's whole gap).
        FakeCompleteExpansionRoom a = new FakeCompleteExpansionRoom("A");
        FakeCompleteExpansionRoom b = new FakeCompleteExpansionRoom("B");
        FakeCompleteExpansionRoom c = new FakeCompleteExpansionRoom("C");
        FakeCompleteExpansionRoom d = new FakeCompleteExpansionRoom("D");
        link(a, b);
        link(b, c);
        link(c, d);

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Collections.singletonList((CompleteExpansionRoom) a), room -> room == d, NO_OP_EXPANDER, 1000,
                () -> false);

        assertEquals(RoomReachabilityAlgo.FloodResult.FOUND, result);
    }

    @Test
    public void missing_door_blocks_the_fill()
    {
        // A has no door to D at all -- this is how the real room-building code represents an
        // immovable obstacle (SortedRoomNeighbours.calculate never creates a door onto an item
        // with is_route() == false), so the fill correctly cannot cross it.
        FakeCompleteExpansionRoom a = new FakeCompleteExpansionRoom("A");
        FakeCompleteExpansionRoom d = new FakeCompleteExpansionRoom("D");
        // deliberately: no link(a, d)

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Collections.singletonList((CompleteExpansionRoom) a), room -> room == d, NO_OP_EXPANDER, 1000,
                () -> false);

        assertEquals(RoomReachabilityAlgo.FloodResult.EXHAUSTED, result);
    }

    @Test
    public void passable_room_in_the_middle_of_a_chain_does_not_block_it()
    {
        // A -- [rippable obstacle room] -- D. Models a trace or via (Item.is_route() == true)
        // sitting between two pockets of free space: the real room-building code gives it doors
        // on both sides (see ObstacleExpansionRoom / SortedRoomNeighbours.calculate), and this
        // flood fill must walk straight through it rather than special-casing or stopping at it --
        // there is no rippability check in flood_fill itself, by design (see the class javadoc of
        // RoomReachabilityAlgo): passability is entirely a property of whether a door exists,
        // decided upstream.
        FakeCompleteExpansionRoom a = new FakeCompleteExpansionRoom("A (free space)");
        FakeCompleteExpansionRoom rippableObstacle = new FakeCompleteExpansionRoom("rippable trace");
        FakeCompleteExpansionRoom d = new FakeCompleteExpansionRoom("D (free space)");
        link(a, rippableObstacle);
        link(rippableObstacle, d);

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Collections.singletonList((CompleteExpansionRoom) a), room -> room == d, NO_OP_EXPANDER, 1000,
                () -> false);

        assertEquals(RoomReachabilityAlgo.FloodResult.FOUND, result);
    }

    @Test
    public void room_cap_is_enforced()
    {
        // A long chain, longer than the cap, with the target placed beyond the cap. Must report
        // BUDGET_EXCEEDED (fail open), not EXHAUSTED (which the caller would read as a proven
        // NO_CORRIDOR) and not FOUND.
        List<FakeCompleteExpansionRoom> chain = new ArrayList<>();
        for (int i = 0; i < 50; ++i)
        {
            chain.add(new FakeCompleteExpansionRoom("room" + i));
        }
        for (int i = 0; i + 1 < chain.size(); ++i)
        {
            link(chain.get(i), chain.get(i + 1));
        }
        FakeCompleteExpansionRoom target = chain.get(chain.size() - 1);

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Collections.singletonList((CompleteExpansionRoom) chain.get(0)), room -> room == target,
                NO_OP_EXPANDER, 5, () -> false);

        assertEquals(RoomReachabilityAlgo.FloodResult.BUDGET_EXCEEDED, result);
    }

    @Test
    public void time_budget_is_checked_before_expanding_the_frontier()
    {
        // Target is one hop away and would trivially be FOUND, but the budget supplier already
        // reports "out of time" -- must report BUDGET_EXCEEDED, proving the time check happens
        // before any further expansion rather than only after the room cap is hit.
        FakeCompleteExpansionRoom a = new FakeCompleteExpansionRoom("A");
        FakeCompleteExpansionRoom b = new FakeCompleteExpansionRoom("B");
        link(a, b);

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Collections.singletonList((CompleteExpansionRoom) a), room -> room == b, NO_OP_EXPANDER, 1000,
                () -> true);

        assertEquals(RoomReachabilityAlgo.FloodResult.BUDGET_EXCEEDED, result);
    }

    @Test
    public void lazy_expansion_is_read_after_expand_is_called()
    {
        // A starts with no doors at all. The RoomExpander hook is responsible for materializing
        // them (mirroring AutorouteEngine.complete_neigbour_rooms completing an incomplete
        // neighbour before its doors are trusted) -- flood_fill must call it before reading
        // get_doors(), not rely on the graph already being fully built.
        FakeCompleteExpansionRoom a = new FakeCompleteExpansionRoom("A");
        FakeCompleteExpansionRoom b = new FakeCompleteExpansionRoom("B");
        RoomReachabilityAlgo.RoomExpander lazyExpander = room ->
        {
            if (room == a && room.get_doors().isEmpty())
            {
                link(a, b);
            }
        };

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Collections.singletonList((CompleteExpansionRoom) a), room -> room == b, lazyExpander, 1000,
                () -> false);

        assertEquals(RoomReachabilityAlgo.FloodResult.FOUND, result);
    }

    @Test
    public void disconnected_component_is_exhausted_without_hanging()
    {
        // A small triangle (A-B-C all mutually linked) with no target anywhere: a regression
        // guard against revisiting rooms forever on a cycle -- if visited-room dedup were
        // broken, this would loop indefinitely instead of returning EXHAUSTED.
        FakeCompleteExpansionRoom a = new FakeCompleteExpansionRoom("A");
        FakeCompleteExpansionRoom b = new FakeCompleteExpansionRoom("B");
        FakeCompleteExpansionRoom c = new FakeCompleteExpansionRoom("C");
        link(a, b);
        link(b, c);
        link(c, a);
        FakeCompleteExpansionRoom unreachableTarget = new FakeCompleteExpansionRoom("unreachable");

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Collections.singletonList((CompleteExpansionRoom) a), room -> room == unreachableTarget,
                NO_OP_EXPANDER, 1000, () -> false);

        assertEquals(RoomReachabilityAlgo.FloodResult.EXHAUSTED, result);
    }

    @Test
    public void multiple_seeds_are_all_explored()
    {
        // Two disjoint seed rooms (as seed_destination_rooms collects one per destination
        // item/shape/layer); the target hangs off the SECOND seed only, proving every seed
        // actually gets explored rather than the fill stopping after the first.
        FakeCompleteExpansionRoom seed1 = new FakeCompleteExpansionRoom("seed1");
        FakeCompleteExpansionRoom seed2 = new FakeCompleteExpansionRoom("seed2");
        FakeCompleteExpansionRoom target = new FakeCompleteExpansionRoom("target");
        link(seed2, target);

        RoomReachabilityAlgo.FloodResult result = RoomReachabilityAlgo.flood_fill(
                Arrays.asList((CompleteExpansionRoom) seed1, (CompleteExpansionRoom) seed2), room -> room == target,
                NO_OP_EXPANDER, 1000, () -> false);

        assertEquals(RoomReachabilityAlgo.FloodResult.FOUND, result);
    }

    /**
     * Minimal {@link CompleteExpansionRoom} fake: real doors ({@link ExpansionDoor}, unmodified)
     * link real fake rooms, but the rooms themselves carry no geometry, board, or search-tree
     * state -- only what {@link RoomReachabilityAlgo#flood_fill} actually reads
     * ({@code get_doors()}) plus the bookkeeping every {@link ExpansionRoom} must support.
     */
    private static final class FakeCompleteExpansionRoom implements CompleteExpansionRoom
    {
        private final List<ExpansionDoor> doors = new ArrayList<>();
        private final String name;

        FakeCompleteExpansionRoom(String p_name)
        {
            this.name = p_name;
        }

        @Override
        public void add_door(ExpansionDoor p_door)
        {
            this.doors.add(p_door);
        }

        @Override
        public List<ExpansionDoor> get_doors()
        {
            return this.doors;
        }

        @Override
        public void clear_doors()
        {
            this.doors.clear();
        }

        @Override
        public void reset_doors()
        {
            // no maze-search state in this fake
        }

        @Override
        public boolean door_exists(ExpansionRoom p_other)
        {
            for (ExpansionDoor curr_door : this.doors)
            {
                if (curr_door.first_room == p_other || curr_door.second_room == p_other)
                {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean remove_door(ExpandableObject p_door)
        {
            return this.doors.remove(p_door);
        }

        @Override
        public TileShape get_shape()
        {
            return null; // flood_fill never reads this
        }

        @Override
        public int get_layer()
        {
            return 0;
        }

        @Override
        public Collection<TargetItemExpansionDoor> get_target_doors()
        {
            return Collections.emptyList();
        }

        @Override
        public SearchTreeObject get_object()
        {
            return null;
        }

        @Override
        public void draw(java.awt.Graphics p_graphics, eu.mihosoft.freerouting.boardgraphics.GraphicsContext p_graphics_context,
                double p_intensity)
        {
            // not exercised by these tests
        }

        @Override
        public String toString()
        {
            return this.name;
        }
    }
}
