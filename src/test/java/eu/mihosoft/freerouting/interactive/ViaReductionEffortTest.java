package eu.mihosoft.freerouting.interactive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers AutorouteSettings' via reduction effort (0-100), which BatchOptRoute uses to raise
 * via_costs during its postroute pass only (see BatchOptRoute.optimize_board). The factor here
 * gates whether that raise-then-restore happens at all
 * ({@code boosted_via_costs != base_via_costs}), so an exact 1.0 at effort 0 is what makes the
 * default a true no-op, not just a numerically negligible one.
 */
public class ViaReductionEffortTest
{
    @Test
    public void default_effort_is_zero()
    {
        AutorouteSettings settings = new AutorouteSettings(2);
        assertEquals(0, settings.get_via_reduction_effort());
    }

    @Test
    public void at_zero_the_boost_factor_is_the_exact_identity_value()
    {
        AutorouteSettings settings = new AutorouteSettings(2);
        settings.set_via_reduction_effort(0);
        assertEquals(1.0, settings.get_via_cost_boost_factor(), 0.0);
    }

    @Test
    public void at_zero_boosting_any_via_cost_reproduces_it_exactly()
    {
        AutorouteSettings settings = new AutorouteSettings(2);
        settings.set_via_reduction_effort(0);
        for (int base_via_costs : new int[]{1, 5, 50, 999})
        {
            int boosted = (int) Math.round(base_via_costs * settings.get_via_cost_boost_factor());
            assertEquals(base_via_costs, boosted);
        }
    }

    @Test
    public void positive_effort_raises_the_boost_factor_above_one()
    {
        AutorouteSettings settings = new AutorouteSettings(2);
        settings.set_via_reduction_effort(100);
        assertTrue(settings.get_via_cost_boost_factor() > 1.0);
        int base_via_costs = 50;
        int boosted = (int) Math.round(base_via_costs * settings.get_via_cost_boost_factor());
        assertTrue(boosted > base_via_costs);
    }

    @Test
    public void boost_factor_increases_monotonically_with_effort()
    {
        AutorouteSettings settings = new AutorouteSettings(2);
        double last = 1.0;
        for (int effort = 0; effort <= 100; effort += 10)
        {
            settings.set_via_reduction_effort(effort);
            double factor = settings.get_via_cost_boost_factor();
            assertTrue(factor >= last);
            last = factor;
        }
        assertTrue(last > 1.0);
    }

    @Test
    public void effort_clamps_to_0_100()
    {
        AutorouteSettings settings = new AutorouteSettings(2);
        settings.set_via_reduction_effort(-3);
        assertEquals(0, settings.get_via_reduction_effort());
        settings.set_via_reduction_effort(250);
        assertEquals(100, settings.get_via_reduction_effort());
    }

    @Test
    public void copy_constructor_preserves_the_effort()
    {
        AutorouteSettings settings = new AutorouteSettings(2);
        settings.set_via_reduction_effort(64);
        AutorouteSettings copy = new AutorouteSettings(settings);
        assertEquals(64, copy.get_via_reduction_effort());
    }
}
