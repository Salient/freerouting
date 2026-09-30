package eu.mihosoft.freerouting.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * StartupOptions matches flags with String.startsWith in a single if/else chain, so a new flag
 * must not be a prefix of, or prefixed by, an existing one -- and if it is a prefix relationship
 * (like "-sp" and "-s"), the longer flag must be checked first or it is silently swallowed by
 * the shorter one. This covers exactly that hazard for "-sp" (start pass) and "-il" (inner
 * layer preference), added alongside "-s" (session file) and "-l" (locale).
 */
public class StartupOptionsTest
{
    @Test
    public void sp_alone_sets_start_pass_no_and_does_not_touch_session_file_option()
    {
        StartupOptions options = StartupOptions.parse(new String[]{"-sp", "5"});
        assertEquals(5, options.start_pass_no);
        assertFalse(options.session_file_option);
    }

    @Test
    public void s_alone_sets_session_file_option_and_leaves_start_pass_no_unset()
    {
        StartupOptions options = StartupOptions.parse(new String[]{"-s"});
        assertTrue(options.session_file_option);
        assertEquals(-1, options.start_pass_no);
    }

    @Test
    public void sp_and_s_together_do_not_shadow_each_other_regardless_of_order()
    {
        StartupOptions a = StartupOptions.parse(new String[]{"-s", "-sp", "7"});
        assertTrue(a.session_file_option);
        assertEquals(7, a.start_pass_no);

        StartupOptions b = StartupOptions.parse(new String[]{"-sp", "7", "-s"});
        assertTrue(b.session_file_option);
        assertEquals(7, b.start_pass_no);
    }

    @Test
    public void start_pass_no_defaults_to_the_unset_sentinel()
    {
        StartupOptions options = StartupOptions.parse(new String[]{"-de", "board.dsn"});
        assertEquals(-1, options.start_pass_no);
    }

    @Test
    public void sp_ignores_a_value_that_looks_like_another_flag()
    {
        // Documented behaviour of every value flag here: a following value starting with '-'
        // is not consumed, so an accidentally-omitted value doesn't eat the next real flag.
        StartupOptions options = StartupOptions.parse(new String[]{"-sp", "-mp", "5"});
        assertEquals(-1, options.start_pass_no);
        assertEquals(5, options.max_passes);
    }

    @Test
    public void il_alone_sets_inner_layer_preference_and_does_not_touch_locale_or_session()
    {
        StartupOptions options = StartupOptions.parse(new String[]{"-il", "42"});
        assertEquals(42, options.inner_layer_preference);
        assertFalse(options.session_file_option);
        assertEquals(java.util.Locale.ENGLISH, options.current_locale);
    }

    @Test
    public void inner_layer_preference_defaults_to_zero()
    {
        StartupOptions options = StartupOptions.parse(new String[]{"-de", "board.dsn"});
        assertEquals(0, options.inner_layer_preference);
    }

    @Test
    public void vr_alone_sets_via_reduction_effort()
    {
        StartupOptions options = StartupOptions.parse(new String[]{"-vr", "77"});
        assertEquals(77, options.via_reduction_effort);
    }

    @Test
    public void via_reduction_effort_defaults_to_zero()
    {
        StartupOptions options = StartupOptions.parse(new String[]{"-de", "board.dsn"});
        assertEquals(0, options.via_reduction_effort);
    }

    @Test
    public void il_sp_and_vr_together_do_not_shadow_each_other()
    {
        StartupOptions options = StartupOptions.parse(new String[]{"-il", "10", "-vr", "20", "-sp", "3", "-s"});
        assertEquals(10, options.inner_layer_preference);
        assertEquals(20, options.via_reduction_effort);
        assertEquals(3, options.start_pass_no);
        assertTrue(options.session_file_option);
    }
}
