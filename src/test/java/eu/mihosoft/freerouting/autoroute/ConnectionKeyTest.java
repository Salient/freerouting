package eu.mihosoft.freerouting.autoroute;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers ConnectionKey's equals/hashCode contract with synthetic (net_no, item_id_no) pairs --
 * no real Item or board needed, which is the point of keeping this key to two primitives.
 * <p>
 * This is deliberately about the KEY only. BatchAutorouter's actual skip-on-known-blocked
 * behaviour (see its blocked_connections field and autoroute_item) needs a real Item, a real
 * AutorouteControl and a real board to exercise end to end, which is exactly the kind of setup
 * the "measure on real sample boards" part of this task covers instead; duplicating that with
 * mocks here would test the mocks, not the router.
 */
public class ConnectionKeyTest
{
    @Test
    public void same_net_and_item_are_equal()
    {
        ConnectionKey a = new ConnectionKey(3, 42);
        ConnectionKey b = new ConnectionKey(3, 42);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void different_net_same_item_are_not_equal()
    {
        ConnectionKey a = new ConnectionKey(3, 42);
        ConnectionKey b = new ConnectionKey(4, 42);
        assertFalse(a.equals(b));
    }

    @Test
    public void same_net_different_item_are_not_equal()
    {
        ConnectionKey a = new ConnectionKey(3, 42);
        ConnectionKey b = new ConnectionKey(3, 43);
        assertFalse(a.equals(b));
    }

    @Test
    public void not_equal_to_null_or_other_types()
    {
        ConnectionKey a = new ConnectionKey(3, 42);
        assertFalse(a.equals(null));
        assertFalse(a.equals("net 3, item 42"));
    }

    @Test
    public void usable_as_a_hash_set_key_across_repeated_lookups()
    {
        // This is the exact usage BatchAutorouter.blocked_connections relies on: add once,
        // then repeatedly ask "have we already seen this (net, item) pair" with a freshly
        // constructed key each time (mirroring a new ConnectionKey being built fresh on every
        // pass in BatchAutorouter.autoroute_item).
        Set<ConnectionKey> blocked = new HashSet<ConnectionKey>();
        blocked.add(new ConnectionKey(7, 100));

        assertTrue(blocked.contains(new ConnectionKey(7, 100)));
        assertFalse(blocked.contains(new ConnectionKey(7, 101)));
        assertFalse(blocked.contains(new ConnectionKey(8, 100)));
    }

    @Test
    public void usable_as_a_hash_map_key_for_per_connection_counters()
    {
        // Mirrors BatchAutorouter.not_routed_pass_counts: merge(key, 1, Integer::sum) called
        // once per pass with a fresh key instance for the same underlying connection.
        Map<ConnectionKey, Integer> counts = new HashMap<ConnectionKey, Integer>();
        ConnectionKey net5_item9 = new ConnectionKey(5, 9);
        counts.merge(new ConnectionKey(5, 9), 1, Integer::sum);
        counts.merge(new ConnectionKey(5, 9), 1, Integer::sum);
        counts.merge(new ConnectionKey(5, 10), 1, Integer::sum);

        assertEquals(Integer.valueOf(2), counts.get(net5_item9));
        assertEquals(Integer.valueOf(1), counts.get(new ConnectionKey(5, 10)));
        assertEquals(2, counts.size());
    }

    @Test
    public void toString_reports_both_fields()
    {
        ConnectionKey a = new ConnectionKey(3, 42);
        String text = a.toString();
        assertTrue(text.contains("3"));
        assertTrue(text.contains("42"));
    }
}
