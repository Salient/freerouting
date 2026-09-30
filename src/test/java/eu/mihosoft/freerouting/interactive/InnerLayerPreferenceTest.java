package eu.mihosoft.freerouting.interactive;

import eu.mihosoft.freerouting.autoroute.AutorouteControl.ExpansionCostFactor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers AutorouteSettings' inner-layer preference (0-100), which biases the autorouter away
 * from the two outermost signal layers.
 * <p>
 * The two quantities this control feeds are:
 * <ul>
 *   <li>{@code get_outer_layer_trace_cost_factor()}, multiplied into the outer layers' trace
 *       costs by {@code get_trace_cost_arr()} (consumed as ctrl.trace_costs by MazeSearchAlgo
 *       and DestinationDistance);</li>
 *   <li>{@code get_outer_via_cost_factor()}, multiplied by a net's own min_normal_via_cost in
 *       AutorouteControl.apply_inner_layer_via_costs() to populate add_via_costs (consumed by
 *       MazeSearchAlgo, written by nothing else).</li>
 * </ul>
 * Both must be an exact identity/zero at preference 0 -- not just numerically close -- because
 * AutorouteControl's population of add_via_costs is gated on
 * {@code outer_via_cost_factor > 0}, and get_trace_cost_arr()'s boost is gated on
 * {@code inner_layer_preference > 0}. Proving those two factors are exactly 1.0 and exactly 0.0
 * at preference 0 is what proves both gates stay closed, i.e. that the feature is a true no-op
 * by construction, not by floating-point luck.
 */
public class InnerLayerPreferenceTest
{
    @Test
    public void default_preference_is_zero()
    {
        AutorouteSettings settings = new AutorouteSettings(4);
        assertEquals(0, settings.get_inner_layer_preference());
    }

    @Test
    public void at_zero_the_outer_cost_factors_are_the_exact_identity_values()
    {
        AutorouteSettings settings = new AutorouteSettings(4);
        settings.set_inner_layer_preference(0);
        assertEquals(1.0, settings.get_outer_layer_trace_cost_factor(), 0.0);
        assertEquals(0.0, settings.get_outer_via_cost_factor(), 0.0);
    }

    @Test
    public void at_zero_get_trace_cost_arr_is_unchanged_on_every_layer()
    {
        for (int layer_count : new int[]{1, 2, 3, 6})
        {
            AutorouteSettings settings = new AutorouteSettings(layer_count);
            for (int i = 0; i < layer_count; ++i)
            {
                settings.set_preferred_direction_trace_costs(i, 2.5 + i);
                settings.set_against_preferred_direction_trace_costs(i, 3.5 + i);
            }
            ExpansionCostFactor[] before_setting_anything = settings.get_trace_cost_arr();
            settings.set_inner_layer_preference(0);
            ExpansionCostFactor[] after = settings.get_trace_cost_arr();
            for (int i = 0; i < layer_count; ++i)
            {
                assertEquals("layer_count=" + layer_count + " layer=" + i,
                        before_setting_anything[i].horizontal, after[i].horizontal, 0.0);
                assertEquals("layer_count=" + layer_count + " layer=" + i,
                        before_setting_anything[i].vertical, after[i].vertical, 0.0);
            }
        }
    }

    @Test
    public void positive_preference_raises_only_the_two_outermost_layers()
    {
        int layer_count = 6;
        AutorouteSettings settings = new AutorouteSettings(layer_count);
        for (int i = 0; i < layer_count; ++i)
        {
            settings.set_preferred_direction_trace_costs(i, 1.0);
            settings.set_against_preferred_direction_trace_costs(i, 1.0);
        }
        ExpansionCostFactor[] baseline = settings.get_trace_cost_arr();
        settings.set_inner_layer_preference(100);
        ExpansionCostFactor[] boosted = settings.get_trace_cost_arr();

        // Outer layers (0 and layer_count - 1) got more expensive.
        assertTrue(boosted[0].horizontal > baseline[0].horizontal);
        assertTrue(boosted[layer_count - 1].horizontal > baseline[layer_count - 1].horizontal);

        // Every inner layer is untouched.
        for (int i = 1; i < layer_count - 1; ++i)
        {
            assertEquals(baseline[i].horizontal, boosted[i].horizontal, 0.0);
            assertEquals(baseline[i].vertical, boosted[i].vertical, 0.0);
        }
    }

    @Test
    public void factors_increase_monotonically_with_preference()
    {
        AutorouteSettings settings = new AutorouteSettings(6);
        double last_trace_factor = 1.0;
        double last_via_factor = 0.0;
        for (int preference = 0; preference <= 100; preference += 10)
        {
            settings.set_inner_layer_preference(preference);
            double trace_factor = settings.get_outer_layer_trace_cost_factor();
            double via_factor = settings.get_outer_via_cost_factor();
            assertTrue(trace_factor >= last_trace_factor);
            assertTrue(via_factor >= last_via_factor);
            last_trace_factor = trace_factor;
            last_via_factor = via_factor;
        }
        // And it actually moved, not just stayed flat.
        assertTrue(last_trace_factor > 1.0);
        assertTrue(last_via_factor > 0.0);
    }

    @Test
    public void a_board_with_fewer_than_three_layers_has_no_inner_layer_to_prefer()
    {
        for (int layer_count : new int[]{1, 2})
        {
            AutorouteSettings settings = new AutorouteSettings(layer_count);
            for (int i = 0; i < layer_count; ++i)
            {
                settings.set_preferred_direction_trace_costs(i, 1.0);
                settings.set_against_preferred_direction_trace_costs(i, 1.0);
            }
            ExpansionCostFactor[] baseline = settings.get_trace_cost_arr();
            settings.set_inner_layer_preference(100);
            ExpansionCostFactor[] boosted = settings.get_trace_cost_arr();
            for (int i = 0; i < layer_count; ++i)
            {
                assertEquals("layer_count=" + layer_count + " layer=" + i,
                        baseline[i].horizontal, boosted[i].horizontal, 0.0);
                assertEquals("layer_count=" + layer_count + " layer=" + i,
                        baseline[i].vertical, boosted[i].vertical, 0.0);
            }
        }
    }

    @Test
    public void preference_clamps_to_0_100()
    {
        AutorouteSettings settings = new AutorouteSettings(4);
        settings.set_inner_layer_preference(-5);
        assertEquals(0, settings.get_inner_layer_preference());
        settings.set_inner_layer_preference(500);
        assertEquals(100, settings.get_inner_layer_preference());
    }

    @Test
    public void copy_constructor_preserves_the_preference()
    {
        AutorouteSettings settings = new AutorouteSettings(4);
        settings.set_inner_layer_preference(37);
        AutorouteSettings copy = new AutorouteSettings(settings);
        assertEquals(37, copy.get_inner_layer_preference());
    }
}
