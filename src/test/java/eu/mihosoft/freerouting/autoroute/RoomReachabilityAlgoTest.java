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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
     * The activity summary must account for every attempted check, and its "proved none" must
     * equal what {@link RoomReachabilityAlgo#proved_blocked_count} claims.
     *
     * <p>This is the regression for a reporting bug that produced a self-contradictory log on
     * the 6-layer reference board: "0 proved none" in the same run whose BoardScore line said
     * "12 of which are provably unroutable". "Proved none" was derived as
     * (completed - corridor - out_of_budget), which only ever sees the EXHAUSTED branch, while
     * PAD_UNREACHABLE returns BEFORE checks_completed is incremented. So the 12 pad-unreachable
     * verdicts were invisible here and, worse, were silently counted as "not modellable",
     * overstating the coverage gap by exactly the number of connections the check had in fact
     * succeeded in proving blocked.
     *
     * <p>Driven through the real {@code check()} entry point rather than by poking the counters,
     * so the increments must actually sit at the return sites. An empty destination set takes
     * the earliest bail-out, which is the one case reachable without a board.
     */
    @Test
    public void activity_summary_accounts_for_every_attempted_check()
    {
        RoomReachabilityAlgo.reset_activity();
        try
        {
            assertNull("summary must be null before any check runs",
                    RoomReachabilityAlgo.activity_summary());

            // Empty destination set: returns REACHABLE at the top of check(), before anything
            // that needs an engine, so this counts an attempt and nothing else.
            for (int i = 0; i < 3; ++i)
            {
                RoomReachabilityAlgo.check(Collections.<eu.mihosoft.freerouting.board.Item>emptySet(),
                        Collections.<eu.mihosoft.freerouting.board.Item>emptySet(), null, null);
            }

            String summary = RoomReachabilityAlgo.activity_summary();
            assertNotNull("summary must exist once a check has been attempted", summary);
            assertTrue("summary should report the 3 attempts, got: " + summary,
                    summary.contains("3 attempted"));
            // The whole point: no blocked verdicts were reached, so both the summary's
            // "proved none" and the accessor must say zero, and they must agree.
            assertEquals("no blocked verdict was reached", 0,
                    RoomReachabilityAlgo.proved_blocked_count());
            assertTrue("with no blocked verdicts the summary must say 0 proved none, got: "
                    + summary, summary.contains("0 proved none"));
            // Every attempt is accounted for in exactly one bucket.
            assertTrue("all 3 unmodellable attempts must be reported as such, got: " + summary,
                    summary.contains("3 not modellable"));
        }
        finally
        {
            RoomReachabilityAlgo.reset_activity();
        }
    }

    /**
     * The actual regression, using the real counter values from the 6-layer reference board run
     * that exposed the bug: 3021 attempted, 2614 corridor, 386 out of budget, and 12 blocked --
     * all 12 of them PAD_UNREACHABLE, none EXHAUSTED.
     *
     * <p>The old derived formula (completed - corridor - out_of_budget) computes
     * 3000 - 2614 - 386 = 0 for these inputs and prints "0 proved none" while BoardScore prints
     * "12 provably unroutable" -- and it also reports 21 not modellable when the true figure is
     * 9, because the 12 pad verdicts fall into that bucket by subtraction. Both numbers wrong
     * from one missing counter.
     *
     * <p>Driven through {@code format_summary} rather than {@code check()} on purpose:
     * PAD_UNREACHABLE needs a real board, so this is the only level at which the arithmetic is
     * reachable. This test fails against the derived version and passes against the counted one.
     */
    @Test
    public void proved_none_counts_pad_unreachable_verdicts_not_just_exhausted_fills()
    {
        // 3021 attempted = 2614 corridor + 386 budget + 12 pad-unreachable + 9 not modellable.
        // completed = 3000, i.e. the 3021 minus the 12 early pad returns and 9 early bail-outs.
        String summary = RoomReachabilityAlgo.format_summary(3021, 3000, 2614, 386, 12, 0);

        assertTrue("the 12 PAD_UNREACHABLE verdicts must be reported as proved, not as zero."
                + " Got: " + summary, summary.contains("12 proved none"));
        assertTrue("and attributed to the no-free-space case. Got: " + summary,
                summary.contains("12 no free space at the destination pad"));
        // The second half of the same bug: those 12 must not also be counted as unexamined.
        assertTrue("not-modellable must be 9, not 21 -- the 12 blocked verdicts were examined"
                + " successfully and must not inflate the coverage gap. Got: " + summary,
                summary.contains("9 not modellable"));
    }

    /** The same arithmetic with the verdicts the other way round, so neither bucket is hard-coded. */
    @Test
    public void proved_none_counts_exhausted_fills_too()
    {
        // 100 attempted, 50 corridor, 10 budget, 0 pad-unreachable, 40 sealed-pocket.
        // All 100 reached the fill, so completed == 100 and not-modellable is 0.
        String summary = RoomReachabilityAlgo.format_summary(100, 100, 50, 10, 0, 40);

        assertTrue("got: " + summary, summary.contains("40 proved none"));
        assertTrue("got: " + summary, summary.contains("40 free space but no corridor out"));
        assertTrue("got: " + summary, summary.contains("0 not modellable"));
    }

    /**
     * "Proved none" must break the two blocked verdicts out separately, because they call for
     * different action: PAD_UNREACHABLE means the pad has no free space beside it at all
     * (placement or a clearance rule is wrong), while NO_CORRIDOR means free space exists but is
     * walled in (a routing-order or ripup-permission problem). A single lumped total hid which.
     */
    @Test
    public void summary_distinguishes_the_two_blocked_verdicts()
    {
        RoomReachabilityAlgo.reset_activity();
        try
        {
            RoomReachabilityAlgo.check(Collections.<eu.mihosoft.freerouting.board.Item>emptySet(),
                    Collections.<eu.mihosoft.freerouting.board.Item>emptySet(), null, null);
            String summary = RoomReachabilityAlgo.activity_summary();
            assertTrue("summary must name the no-free-space case, got: " + summary,
                    summary.contains("no free space at the destination pad"));
            assertTrue("summary must name the sealed-pocket case, got: " + summary,
                    summary.contains("free space but no corridor out"));
        }
        finally
        {
            RoomReachabilityAlgo.reset_activity();
        }
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
