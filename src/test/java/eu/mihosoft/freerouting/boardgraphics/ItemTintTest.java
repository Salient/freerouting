package eu.mihosoft.freerouting.boardgraphics;

import org.junit.Test;

import java.awt.Color;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the derived colours for component pads and copper pours
 * (GraphicsContext.pad_color / pour_color).
 *
 * <p>The property that matters most here is that a pour can never be mistaken for a pad on
 * any layer, for any trace colour, for any net. That is enforced by brightness rather than
 * by hue, so it survives the per-net tint applied to pours.
 */
public class ItemTintTest
{
    /** A spread of plausible per-layer trace colours, including freerouting's defaults. */
    private static final Color[] TRACE_COLORS = {
            Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA,
            Color.WHITE, Color.BLACK, Color.GRAY, Color.ORANGE,
            new Color(154, 94, 46),   // the pad tint itself, the nastiest case
            new Color(30, 30, 30), new Color(200, 180, 90), new Color(0, 90, 120)};

    private static float brightness(Color p_color)
    {
        return Color.RGBtoHSB(p_color.getRed(), p_color.getGreen(), p_color.getBlue(), null)[2];
    }

    private static double distance(Color p_a, Color p_b)
    {
        double dr = p_a.getRed() - p_b.getRed();
        double dg = p_a.getGreen() - p_b.getGreen();
        double db = p_a.getBlue() - p_b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    @Test
    public void a_pour_is_never_as_dark_as_a_pad_on_any_layer_or_net()
    {
        for (Color trace : TRACE_COLORS)
        {
            Color pad = GraphicsContext.pad_color(trace);
            for (int net_no = 0; net_no < 500; ++net_no)
            {
                Color pour = GraphicsContext.pour_color(trace, net_no);
                assertTrue("pour for net " + net_no + " on trace colour " + trace
                                + " is not brighter than the pad colour " + pad,
                        brightness(pour) > brightness(pad));
            }
        }
    }

    @Test
    public void a_pad_and_a_pour_are_never_close_enough_to_confuse()
    {
        for (Color trace : TRACE_COLORS)
        {
            Color pad = GraphicsContext.pad_color(trace);
            for (int net_no = 0; net_no < 500; ++net_no)
            {
                Color pour = GraphicsContext.pour_color(trace, net_no);
                assertTrue("pad " + pad + " and pour " + pour + " (net " + net_no
                                + ", trace " + trace + ") are too similar",
                        distance(pad, pour) > 60.0);
            }
        }
    }

    @Test
    public void a_pour_is_lighter_than_the_trace_it_derives_from()
    {
        for (Color trace : TRACE_COLORS)
        {
            // Skip colours already at full brightness: nothing can be lighter than white.
            if (brightness(trace) >= 0.82f)
            {
                continue;
            }
            Color pour = GraphicsContext.pour_color(trace, 7);
            assertTrue("pour " + pour + " should be lighter than trace " + trace,
                    brightness(pour) > brightness(trace));
        }
    }

    @Test
    public void pours_on_different_nets_are_distinguishable()
    {
        // Same layer, different nets: the whole point of the per-net tint.
        Color base = new Color(0, 160, 0);
        int distinguishable = 0;
        int compared = 0;
        for (int a = 1; a <= 40; ++a)
        {
            for (int b = a + 1; b <= 40; ++b)
            {
                ++compared;
                if (distance(GraphicsContext.pour_color(base, a),
                        GraphicsContext.pour_color(base, b)) > 8.0)
                {
                    ++distinguishable;
                }
            }
        }
        // Not every pair can differ on a small palette, but the large majority should.
        assertTrue("only " + distinguishable + " of " + compared + " net pairs were distinguishable",
                distinguishable > compared * 0.8);
    }

    @Test
    public void the_same_net_always_gets_the_same_colour()
    {
        Color base = Color.BLUE;
        assertTrue(GraphicsContext.pour_color(base, 42).equals(GraphicsContext.pour_color(base, 42)));
    }

    @Test
    public void a_null_trace_colour_does_not_throw()
    {
        assertNotNull(GraphicsContext.pad_color(null));
        assertNotNull(GraphicsContext.pour_color(null, 3));
    }

    @Test
    public void a_negative_net_number_still_produces_a_colour()
    {
        // net_count() == 0 passes 0, but be defensive about the arithmetic not overflowing
        // into a negative modulus.
        assertNotNull(GraphicsContext.pour_color(Color.GREEN, -5));
        assertNotNull(GraphicsContext.pour_color(Color.GREEN, Integer.MIN_VALUE));
    }
}
