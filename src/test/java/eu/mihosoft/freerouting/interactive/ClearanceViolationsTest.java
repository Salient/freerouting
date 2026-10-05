package eu.mihosoft.freerouting.interactive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// Note: this project pins JUnit at 4.10 (see build.gradle), which predates
// org.junit.Assert.assertNotEquals (added in 4.11). Distinctness is asserted below with
// assertFalse(a.equals(b)) instead.

/**
 * Covers the filtering and de-duplication logic that ClearanceViolations and ClearanceReport
 * both rely on (ClearanceViolations.is_reportable / pair_key). Item.clearance_violations()
 * reports every overlap twice -- once from each item's point of view, first_item/second_item
 * swapped -- and does not filter by net at all, so before this logic was extracted the GUI and
 * the -rm verify report disagreed about the same board: the GUI counted same-net pairs and
 * rounding noise that the report already excluded. These tests use plain synthetic values
 * rather than real Item/board graphs, since the whole point of extracting these as static
 * methods was to make the decision testable without one.
 */
public class ClearanceViolationsTest
{
    // --- is_reportable ---------------------------------------------------------------------

    @Test
    public void same_net_pairs_are_never_reportable_no_matter_how_large_the_overlap()
    {
        // Two items on the same net are connected, not too close -- see the class-level note
        // on ClearanceViolations.is_reportable. A huge overlap must not override that.
        assertFalse(ClearanceViolations.is_reportable(true, false, 1000.0));
        assertFalse(ClearanceViolations.is_reportable(true, false, ClearanceViolations.MIN_REPORTED_OVERLAP_MIL));
        assertFalse(ClearanceViolations.is_reportable(true, false, 0.0));
    }

    @Test
    public void different_net_overlaps_under_the_rounding_floor_are_not_reportable()
    {
        // freerouting's integer clearance check leaves overlaps of a few hundredths of a mil
        // on copper that actually meets clearance; those are rounding noise, not violations.
        assertFalse(ClearanceViolations.is_reportable(false, false, 0.0));
        assertFalse(ClearanceViolations.is_reportable(false, false, 0.499));
        assertFalse(ClearanceViolations.is_reportable(false, false, Math.nextDown(ClearanceViolations.MIN_REPORTED_OVERLAP_MIL)));
    }

    @Test
    public void different_net_overlaps_at_or_above_the_rounding_floor_are_reportable()
    {
        assertTrue(ClearanceViolations.is_reportable(false, false, ClearanceViolations.MIN_REPORTED_OVERLAP_MIL));
        assertTrue(ClearanceViolations.is_reportable(false, false, 5.0));
        assertTrue(ClearanceViolations.is_reportable(false, false, 1000.0));
    }

    // --- pair_key ----------------------------------------------------------------------------

    @Test
    public void pair_key_is_order_independent_so_the_mirrored_sighting_collapses()
    {
        // Item.clearance_violations() reports the same overlap between item 7 and item 42 once
        // as (first=7, second=42) and once as (first=42, second=7). Both sightings must produce
        // the same key so a de-duplicating Set collapses them to one entry.
        String forward = ClearanceViolations.pair_key(7, 42, 3);
        String backward = ClearanceViolations.pair_key(42, 7, 3);
        assertEquals(forward, backward);
    }

    @Test
    public void pair_key_is_stable_for_equal_ids_in_either_order()
    {
        assertEquals(ClearanceViolations.pair_key(1, 1, 0), ClearanceViolations.pair_key(1, 1, 0));
    }

    @Test
    public void pair_key_differs_when_the_layer_differs()
    {
        // Two items can violate clearance on more than one layer (e.g. a via's shape on every
        // layer it spans); those are distinct violations and must not collapse into one.
        String layer_zero = ClearanceViolations.pair_key(1, 2, 0);
        String layer_one = ClearanceViolations.pair_key(1, 2, 1);
        assertFalse(layer_zero.equals(layer_one));
    }

    @Test
    public void pair_key_differs_when_the_item_pair_differs()
    {
        String pair_a = ClearanceViolations.pair_key(1, 2, 0);
        String pair_b = ClearanceViolations.pair_key(1, 3, 0);
        String pair_c = ClearanceViolations.pair_key(2, 3, 0);
        assertFalse(pair_a.equals(pair_b));
        assertFalse(pair_a.equals(pair_c));
        assertFalse(pair_b.equals(pair_c));
    }

    @Test
    public void pair_key_uses_separators_so_concatenated_ids_cannot_collide()
    {
        // Without a separator between the ids and the layer, (id=1, id=23, layer=4) and
        // (id=12, id=3, layer=4) could both stringify to "1234". The '-' and '@' separators
        // keep them apart.
        assertFalse(ClearanceViolations.pair_key(1, 23, 4).equals(ClearanceViolations.pair_key(12, 3, 4)));
        assertFalse(ClearanceViolations.pair_key(1, 2, 34).equals(ClearanceViolations.pair_key(1, 23, 4)));
    }

    /**
     * Same-component pads are exempt however far short they fall. A footprint's internal pad
     * spacing is fixed by the part, so there is nothing layout could do about it - and without
     * this, every multi-row connector reports one violation per adjacent pad pair.
     */
    @Test
    public void same_component_pairs_are_never_reportable()
    {
        assertFalse(ClearanceViolations.is_reportable(false, true, 1000.0));
        assertFalse(ClearanceViolations.is_reportable(false, true, 2.5));
        assertFalse(ClearanceViolations.is_reportable(false, true,
                ClearanceViolations.MIN_REPORTED_OVERLAP_MIL));
    }

    /** Different components at the same shortfall ARE reportable - the exemption is narrow. */
    @Test
    public void different_component_pairs_are_still_reportable()
    {
        assertTrue(ClearanceViolations.is_reportable(false, false, 2.5));
    }
}
