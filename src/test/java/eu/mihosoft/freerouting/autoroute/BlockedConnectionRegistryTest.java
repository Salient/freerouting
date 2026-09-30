package eu.mihosoft.freerouting.autoroute;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers BlockedConnectionRegistry's bookkeeping with synthetic ConnectionKeys -- no real
 * board, Item, or AutorouteEngine involved, exercising exactly the same operations
 * BatchAutorouter.autoroute_item performs each pass:
 * <ul>
 * <li>is_blocked before attempting a connection (skip it if already known unroutable);
 * <li>mark_blocked when AutorouteEngine.AutorouteResult.BLOCKED comes back, and that it
 * reports "first time" correctly so the caller logs each blocked connection exactly once;
 * <li>record_not_routed / repeatedly_failed_count as pure reporting that is never conflated
 * with blocked_count.
 * </ul>
 */
public class BlockedConnectionRegistryTest
{
    @Test
    public void unknown_key_is_not_blocked()
    {
        BlockedConnectionRegistry registry = new BlockedConnectionRegistry();
        assertFalse(registry.is_blocked(new ConnectionKey(1, 1)));
        assertEquals(0, registry.blocked_count());
    }

    @Test
    public void mark_blocked_makes_is_blocked_true_and_counts_it()
    {
        BlockedConnectionRegistry registry = new BlockedConnectionRegistry();
        ConnectionKey key = new ConnectionKey(2, 5);

        registry.mark_blocked(key);

        assertTrue(registry.is_blocked(key));
        assertEquals(1, registry.blocked_count());
    }

    @Test
    public void mark_blocked_reports_first_time_only_once()
    {
        // This return value is exactly what BatchAutorouter.note_blocked_connection uses to
        // decide whether to log: the task requires logging each newly-identified blocked
        // connection exactly once, never re-logging it on a later pass.
        BlockedConnectionRegistry registry = new BlockedConnectionRegistry();
        ConnectionKey key = new ConnectionKey(2, 5);

        assertTrue("first mark should report first-time", registry.mark_blocked(key));
        assertFalse("second mark of the same key should not", registry.mark_blocked(key));
        assertFalse("nor should a third", registry.mark_blocked(key));
        assertEquals(1, registry.blocked_count());
    }

    @Test
    public void different_keys_are_independent()
    {
        BlockedConnectionRegistry registry = new BlockedConnectionRegistry();
        registry.mark_blocked(new ConnectionKey(1, 1));
        registry.mark_blocked(new ConnectionKey(1, 2));
        registry.mark_blocked(new ConnectionKey(2, 1));

        assertEquals(3, registry.blocked_count());
        assertFalse(registry.is_blocked(new ConnectionKey(1, 3)));
    }

    @Test
    public void not_routed_counts_are_independent_of_blocked_status()
    {
        BlockedConnectionRegistry registry = new BlockedConnectionRegistry();
        ConnectionKey struggling = new ConnectionKey(3, 9);

        registry.record_not_routed(struggling);
        registry.record_not_routed(struggling);
        registry.record_not_routed(struggling);

        // Repeatedly failing (NOT_ROUTED) must never, by itself, become "blocked": that would
        // conflate "the search has not found it yet" with "provably impossible", which is
        // exactly the distinction the task requires keeping.
        assertFalse(registry.is_blocked(struggling));
        assertEquals(0, registry.blocked_count());
    }

    @Test
    public void repeatedly_failed_count_uses_the_given_threshold()
    {
        BlockedConnectionRegistry registry = new BlockedConnectionRegistry();
        ConnectionKey failed_once = new ConnectionKey(1, 1);
        ConnectionKey failed_thrice = new ConnectionKey(1, 2);

        registry.record_not_routed(failed_once);
        registry.record_not_routed(failed_thrice);
        registry.record_not_routed(failed_thrice);
        registry.record_not_routed(failed_thrice);

        assertEquals(2, registry.repeatedly_failed_count(1));
        assertEquals(1, registry.repeatedly_failed_count(2));
        assertEquals(1, registry.repeatedly_failed_count(3));
        assertEquals(0, registry.repeatedly_failed_count(4));
    }

    @Test
    public void blocking_a_connection_does_not_affect_its_not_routed_count_or_vice_versa()
    {
        BlockedConnectionRegistry registry = new BlockedConnectionRegistry();
        ConnectionKey key = new ConnectionKey(4, 4);

        registry.record_not_routed(key);
        registry.record_not_routed(key);
        registry.mark_blocked(key);

        assertTrue(registry.is_blocked(key));
        assertEquals(1, registry.blocked_count());
        assertEquals(1, registry.repeatedly_failed_count(2));
    }
}
