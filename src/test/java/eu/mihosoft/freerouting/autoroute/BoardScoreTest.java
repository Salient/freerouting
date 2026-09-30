package eu.mihosoft.freerouting.autoroute;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers BoardScore's lexicographic ordering (incomplete count, then via count, then weighted
 * trace length), and specifically that it reproduces the accept/reject decision
 * BatchOptRoute.opt_route_item used to compute inline, before the two were unified.
 * <p>
 * BatchOptRoute's decision used to read (see git history):
 * <pre>
 *   route_improved = incomplete_after &lt; incomplete_before ||
 *       incomplete_after == incomplete_before &amp;&amp;
 *       (via_after &lt; via_before ||
 *        via_after == via_before &amp;&amp; length_before &gt; length_after);
 * </pre>
 * {@link #matches_the_original_inline_expression()} sweeps a grid of values through both that
 * formula and {@code BoardScore.is_better_than} and asserts they always agree, which is the
 * "verify by comparing accept/reject decisions before and after on the same input" step called
 * for by the task -- done as a deterministic unit test rather than by diffing two non-
 * deterministic autorouter runs (the maze search uses an unseeded java.util.Random, so two runs
 * of the real router are never byte-identical to begin with).
 */
public class BoardScoreTest
{
    private static boolean original_inline_expression(int incomplete_before, int via_before, double length_before,
                                                        int incomplete_after, int via_after, double length_after)
    {
        return incomplete_after < incomplete_before ||
                incomplete_after == incomplete_before &&
                        (via_after < via_before ||
                                via_after == via_before && length_before > length_after);
    }

    @Test
    public void fewer_incompletes_always_wins_regardless_of_via_count_or_length()
    {
        BoardScore fewer_incompletes = BoardScore.of(1, 100, 100000.0, 0);
        BoardScore more_incompletes_fewer_vias_shorter = BoardScore.of(2, 1, 1.0, 0);
        assertTrue(fewer_incompletes.is_better_than(more_incompletes_fewer_vias_shorter));
        assertFalse(more_incompletes_fewer_vias_shorter.is_better_than(fewer_incompletes));
    }

    @Test
    public void tied_incompletes_fall_through_to_via_count_regardless_of_length()
    {
        BoardScore fewer_vias = BoardScore.of(5, 2, 100000.0, 0);
        BoardScore more_vias_shorter_length = BoardScore.of(5, 3, 1.0, 0);
        assertTrue(fewer_vias.is_better_than(more_vias_shorter_length));
        assertFalse(more_vias_shorter_length.is_better_than(fewer_vias));
    }

    @Test
    public void tied_incompletes_and_vias_fall_through_to_weighted_trace_length()
    {
        BoardScore shorter = BoardScore.of(5, 2, 100.0, 0);
        BoardScore longer = BoardScore.of(5, 2, 200.0, 0);
        assertTrue(shorter.is_better_than(longer));
        assertFalse(longer.is_better_than(shorter));
    }

    @Test
    public void identical_scores_are_neither_better_nor_worse()
    {
        BoardScore a = BoardScore.of(5, 2, 100.0, 7);
        BoardScore b = BoardScore.of(5, 2, 100.0, 42);
        assertEquals(0, a.compareTo(b));
        assertFalse(a.is_better_than(b));
        assertFalse(b.is_better_than(a));
    }

    @Test
    public void corner_count_never_affects_the_comparison()
    {
        // Same incomplete/via/length, wildly different corner counts: still a tie.
        BoardScore few_corners = BoardScore.of(3, 4, 500.0, 0);
        BoardScore many_corners = BoardScore.of(3, 4, 500.0, 1_000_000);
        assertEquals(0, few_corners.compareTo(many_corners));
    }

    @Test
    public void matches_the_original_inline_expression()
    {
        int[] incompletes = {0, 1, 2, 5};
        int[] vias = {0, 1, 2, 10};
        double[] lengths = {0.0, 1.0, 99.5, 1000.0};

        int compared = 0;
        for (int incomplete_before : incompletes)
        {
            for (int via_before : vias)
            {
                for (double length_before : lengths)
                {
                    BoardScore before = BoardScore.of(incomplete_before, via_before, length_before, 0);
                    for (int incomplete_after : incompletes)
                    {
                        for (int via_after : vias)
                        {
                            for (double length_after : lengths)
                            {
                                BoardScore after = BoardScore.of(incomplete_after, via_after, length_after, 0);
                                boolean expected = original_inline_expression(incomplete_before, via_before, length_before,
                                        incomplete_after, via_after, length_after);
                                boolean actual = after.is_better_than(before);
                                assertEquals("before=(" + incomplete_before + "," + via_before + "," + length_before
                                                + ") after=(" + incomplete_after + "," + via_after + "," + length_after + ")",
                                        expected, actual);
                                ++compared;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(compared > 0);
    }
}
