package eu.mihosoft.freerouting.interactive;

import eu.mihosoft.freerouting.board.Layer;
import eu.mihosoft.freerouting.board.LayerStructure;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers the layer stepping behind ctrl + mouse wheel and the '+' / '-' keys
 * (BoardHandling.cycle_current_layer).
 */
public class LayerCyclingTest
{
    /** Top, an inner plane, two inner signal layers, another plane, Bottom. */
    private static LayerStructure mixed_stack()
    {
        return new LayerStructure(new Layer[]{
                new Layer("Top Layer", true),
                new Layer("GND", false),
                new Layer("Sig [Horiz]", true),
                new Layer("Sig [Vert]", true),
                new Layer("PWR", false),
                new Layer("Bottom Layer", true)});
    }

    private static LayerStructure all_signal_stack()
    {
        return new LayerStructure(new Layer[]{
                new Layer("Top Layer", true),
                new Layer("Mid", true),
                new Layer("Bottom Layer", true)});
    }

    private static int step(LayerStructure p_stack, int p_from, int p_step)
    {
        return BoardHandling.stepped_signal_layer(p_stack, p_from, p_step);
    }

    @Test
    public void steps_to_the_next_signal_layer_skipping_planes()
    {
        LayerStructure stack = mixed_stack();
        // Top (0) -> Sig [Horiz] (2): GND (1) is not a signal layer.
        assertEquals(2, step(stack, 0, 1));
        // Sig [Vert] (3) -> Bottom (5): PWR (4) is skipped.
        assertEquals(5, step(stack, 3, 1));
    }

    @Test
    public void steps_backwards_skipping_planes()
    {
        LayerStructure stack = mixed_stack();
        assertEquals(3, step(stack, 5, -1));
        assertEquals(0, step(stack, 2, -1));
    }

    @Test
    public void stops_at_the_outermost_signal_layer_instead_of_wrapping()
    {
        LayerStructure stack = mixed_stack();
        // Wrapping here would be a nasty surprise mid-route: a flick of the wheel at the
        // top of the stack would jump to the bottom of the board.
        assertEquals(0, step(stack, 0, -1));
        assertEquals(5, step(stack, 5, 1));
        assertEquals(0, step(stack, 0, -5));
        assertEquals(5, step(stack, 5, 5));
    }

    @Test
    public void a_multi_notch_scroll_moves_multiple_layers()
    {
        LayerStructure stack = mixed_stack();
        // Some mice report several notches in one event.
        assertEquals(3, step(stack, 0, 2));
        assertEquals(5, step(stack, 0, 3));
        // ...and clamps rather than running off the end.
        assertEquals(5, step(stack, 0, 99));
    }

    @Test
    public void a_zero_step_is_a_no_op()
    {
        assertEquals(2, step(mixed_stack(), 2, 0));
    }

    @Test
    public void works_on_a_stack_with_no_planes()
    {
        LayerStructure stack = all_signal_stack();
        assertEquals(1, step(stack, 0, 1));
        assertEquals(2, step(stack, 1, 1));
        assertEquals(2, step(stack, 2, 1));
        assertEquals(0, step(stack, 1, -1));
    }

    @Test
    public void a_null_stack_does_not_throw()
    {
        assertEquals(1, BoardHandling.stepped_signal_layer(null, 1, 1));
    }
}
