package eu.mihosoft.freerouting.designforms.frpcb;

import org.junit.Test;

import java.io.File;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers AltiumConstraintsFile against a synthetic Constraints.xml reproducing every
 * structural quirk of a real one. See constraints-sample.xml for why the fixture is
 * synthetic rather than a copy of the board this was developed against.
 */
public class AltiumConstraintsFileTest
{
    private static final double TOLERANCE = 0.001;

    private static AltiumConstraintsFile.Constraints read_sample()
    {
        URL resource = AltiumConstraintsFileTest.class.getResource("constraints-sample.xml");
        assertNotNull("constraints-sample.xml is missing from the test resources", resource);
        AltiumConstraintsFile.Constraints result = AltiumConstraintsFile.read(new File(resource.getPath()));
        assertNotNull("the sample should parse", result);
        return result;
    }

    private static Double find_pair(AltiumConstraintsFile.Constraints p_constraints, String p_first, String p_second)
    {
        for (AltiumConstraintsFile.Pair pair : p_constraints.pairs)
        {
            boolean forward = pair.first_scope.equals(p_first) && pair.second_scope.equals(p_second);
            boolean backward = pair.first_scope.equals(p_second) && pair.second_scope.equals(p_first);
            if (forward || backward)
            {
                return pair.clearance_mil;
            }
        }
        return null;
    }

    @Test
    public void reads_self_clearances_including_the_default_scope()
    {
        AltiumConstraintsFile.Constraints constraints = read_sample();
        assertEquals(25.0, constraints.self_clearance_mil.get("HIGH_A"), TOLERANCE);
        assertEquals(25.0, constraints.self_clearance_mil.get("HIGH_B"), TOLERANCE);
        // The all-zeros GUID must land on the default scope, not on a class named after it.
        assertEquals(8.0, constraints.self_clearance_mil.get(AltiumConstraintsFile.DEFAULT_SCOPE), TOLERANCE);
    }

    @Test
    public void reads_class_pair_clearances()
    {
        AltiumConstraintsFile.Constraints constraints = read_sample();
        assertEquals(75.0, find_pair(constraints, "HIGH_A", "HIGH_B"), TOLERANCE);
        assertEquals(200.0, find_pair(constraints, AltiumConstraintsFile.DEFAULT_SCOPE, "HIGH_A"), TOLERANCE);
    }

    @Test
    public void a_pair_stored_in_both_directions_is_reported_once()
    {
        AltiumConstraintsFile.Constraints constraints = read_sample();
        int matches = 0;
        for (AltiumConstraintsFile.Pair pair : constraints.pairs)
        {
            if ((pair.first_scope.equals("HIGH_A") && pair.second_scope.equals("HIGH_B"))
                    || (pair.first_scope.equals("HIGH_B") && pair.second_scope.equals("HIGH_A")))
            {
                ++matches;
            }
        }
        assertEquals("HIGH_A <-> HIGH_B is stored in both directions and must collapse to one cell",
                1, matches);
    }

    @Test
    public void converts_a_millimetre_gap_to_mil()
    {
        AltiumConstraintsFile.Constraints constraints = read_sample();
        // 0.5 mm = 19.685 mil
        assertEquals(19.685, find_pair(constraints, "HIGH_A", "LOW"), TOLERANCE);
    }

    @Test
    public void disagreeing_layer_type_copies_resolve_to_the_larger()
    {
        AltiumConstraintsFile.Constraints constraints = read_sample();
        // LOW's self clearance is stored as 30 mil on one layer type and 40 on the other; the
        // wider clearance is the safe reading on a high-voltage board.
        assertEquals(40.0, constraints.self_clearance_mil.get("LOW"), TOLERANCE);
    }

    @Test
    public void a_net_scoped_row_is_kept_and_named_after_the_net()
    {
        AltiumConstraintsFile.Constraints constraints = read_sample();
        assertEquals(10.0, find_pair(constraints, AltiumConstraintsFile.DEFAULT_SCOPE, "NET_GROUND"), TOLERANCE);
    }

    @Test
    public void an_unresolvable_scope_is_skipped_rather_than_guessed()
    {
        AltiumConstraintsFile.Constraints constraints = read_sample();
        for (AltiumConstraintsFile.Pair pair : constraints.pairs)
        {
            assertTrue("a cell whose scope GUID resolves to nothing must not be kept: "
                            + pair.first_scope + " <-> " + pair.second_scope,
                    pair.clearance_mil != 999.0);
        }
    }

    @Test
    public void scope_names_covers_every_referenced_scope()
    {
        AltiumConstraintsFile.Constraints constraints = read_sample();
        assertTrue(constraints.scope_names().containsAll(java.util.Arrays.asList(
                "HIGH_A", "HIGH_B", "LOW", "NET_GROUND", AltiumConstraintsFile.DEFAULT_SCOPE)));
    }

    @Test
    public void parses_length_units()
    {
        assertEquals(25.0, AltiumConstraintsFile.parse_length_mil("25mil"), TOLERANCE);
        assertEquals(19.685, AltiumConstraintsFile.parse_length_mil("0.5mm"), TOLERANCE);
        assertEquals(1000.0, AltiumConstraintsFile.parse_length_mil("1in"), TOLERANCE);
        // A bare number is taken as mil, matching how Altium writes unitless values.
        assertEquals(12.0, AltiumConstraintsFile.parse_length_mil("12"), TOLERANCE);
        assertNull(AltiumConstraintsFile.parse_length_mil("not a length"));
    }

    @Test
    public void parses_a_gap_out_of_the_attributes_string()
    {
        assertEquals(150.0, AltiumConstraintsFile.parse_gap_mil(
                "NETSCOPE=DifferentNets|LAYERKIND=SameLayer|GAP=150mil|GENERICCLEARANCE=150mil"), TOLERANCE);
        // GENERICCLEARANCE must not be mistaken for GAP.
        assertNull(AltiumConstraintsFile.parse_gap_mil("NETSCOPE=DifferentNets|GENERICCLEARANCE=150mil"));
        assertNull(AltiumConstraintsFile.parse_gap_mil(""));
    }

    @Test
    public void a_missing_file_is_an_error_not_an_empty_result()
    {
        assertNull(AltiumConstraintsFile.read(new File("does-not-exist-Constraints.xml")));
    }
}
