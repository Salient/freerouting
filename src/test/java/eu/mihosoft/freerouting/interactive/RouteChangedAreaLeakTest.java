package eu.mihosoft.freerouting.interactive;

import eu.mihosoft.freerouting.board.BoardObserverAdaptor;
import eu.mihosoft.freerouting.board.Item;
import eu.mihosoft.freerouting.board.ItemIdNoGenerator;
import eu.mihosoft.freerouting.board.Pin;
import eu.mihosoft.freerouting.board.RoutingBoard;
import eu.mihosoft.freerouting.board.TestLevel;
import eu.mihosoft.freerouting.designforms.specctra.DsnFile;
import eu.mihosoft.freerouting.geometry.planar.FloatPoint;
import eu.mihosoft.freerouting.geometry.planar.IntPoint;
import eu.mihosoft.freerouting.geometry.planar.Point;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the changed_area leak described in the plan for this task: a failed
 * interactive push/shove attempt used to leave RoutingBoard.changed_area set, which made the next
 * start_marking_changed_area() call (see RoutingBoard.start_marking_changed_area()) a no-op, so
 * unrelated marks from later, spatially unrelated mouse-move events kept getting unioned into one
 * ever-growing region. The eventual pull-tight after a successful shove then had to process the
 * accumulated union of every failed attempt since the last success, not just the current one --
 * this is most of what made the interactive stall feel intermittent and unbounded.
 *
 * <p>These tests drive eu.mihosoft.freerouting.interactive.Route directly (it only needs a
 * RoutingBoard, not a live GUI) against a real board loaded headlessly via
 * eu.mihosoft.freerouting.interactive.BoardHandlingImpl, which exists exactly for this purpose.
 */
public class RouteChangedAreaLeakTest
{
    private static final String TEST_BOARD = "tests/pic_programmer.dsn";

    private static RoutingBoard load_test_board() throws Exception
    {
        BoardHandlingImpl board_handling = new BoardHandlingImpl();
        try (InputStream in = new FileInputStream(TEST_BOARD))
        {
            DsnFile.ReadResult result = DsnFile.read(in, board_handling, new BoardObserverAdaptor(),
                    new ItemIdNoGenerator(), TestLevel.RELEASE_VERSION);
            assertEquals("failed to parse " + TEST_BOARD, DsnFile.ReadResult.OK, result);
        }
        return board_handling.get_routing_board();
    }

    /** Two pins on different nets that share a common, active signal layer, plus that layer. */
    private static final class PinPair
    {
        final Pin start;
        final Pin target;
        final int layer;

        PinPair(Pin start, Pin target, int layer)
        {
            this.start = start;
            this.target = target;
            this.layer = layer;
        }
    }

    /**
     * Finds two Pin items belonging to different nets that share a common, active signal layer.
     * Pins are never shovable, so aiming a route directly at the second pin's center is a
     * deterministic, always-failing obstacle regardless of push/shove recursion depth -- exactly
     * the common "ok_point == prev_corner" failure path in Route.next_corner.
     */
    private static PinPair find_two_pins_on_different_nets_sharing_a_layer(RoutingBoard board)
    {
        List<Pin> pins = new ArrayList<>();
        for (Item item : board.get_items())
        {
            if (item instanceof Pin && item.net_count() > 0 && ((Pin) item).get_center() instanceof IntPoint)
            {
                pins.add((Pin) item);
            }
        }
        for (Pin a : pins)
        {
            for (Pin b : pins)
            {
                if (a == b || a.get_net_no(0) == b.get_net_no(0))
                {
                    continue;
                }
                for (int layer = 0; layer < board.get_layer_count(); ++layer)
                {
                    if (board.layer_structure.arr[layer].is_signal && a.is_on_layer(layer) && b.is_on_layer(layer))
                    {
                        return new PinPair(a, b, layer);
                    }
                }
            }
        }
        return null;
    }

    private static Route start_route_at(RoutingBoard board, Pin start_pin, int layer, boolean push_enabled)
    {
        int layer_count = board.get_layer_count();
        int[] pen_half_width_arr = new int[layer_count];
        boolean[] layer_active_arr = new boolean[layer_count];
        int half_width = board.rules.get_trace_half_width(start_pin.get_net_no(0), layer);
        for (int i = 0; i < layer_count; ++i)
        {
            pen_half_width_arr[i] = half_width;
        }
        layer_active_arr[layer] = true;
        int[] net_no_arr = {start_pin.get_net_no(0)};
        return new Route((Point) start_pin.get_center(), layer, pen_half_width_arr, layer_active_arr, net_no_arr,
                start_pin.clearance_class_no(), null, push_enabled, Integer.MAX_VALUE, 500, start_pin, null, board,
                false, false, false, true);
    }

    @Test
    public void repeated_failed_shoves_do_not_leak_changed_area() throws Exception
    {
        RoutingBoard board = load_test_board();
        PinPair pins = find_two_pins_on_different_nets_sharing_a_layer(board);
        assertTrue("could not find two pins on different nets sharing a signal layer in "
                + TEST_BOARD, pins != null);

        // Nothing should be marked as changed before we even start.
        assertNull("changed_area was already set before any routing happened",
                board.get_changed_area_extent());

        Route route = start_route_at(board, pins.start, pins.layer, true);
        FloatPoint blocked_target = pins.target.get_center().to_float();

        for (int attempt = 0; attempt < 8; ++attempt)
        {
            boolean route_completed = route.next_corner(blocked_target);
            assertFalse("routing into a different net's pin should never succeed", route_completed);
            assertNull("changed_area leaked after failed shove attempt #" + attempt
                            + " -- start_marking_changed_area() will now be a no-op, so the next "
                            + "(possibly unrelated) mouse-move event's marks get silently unioned "
                            + "with this one",
                    board.get_changed_area_extent());
        }
    }
}
