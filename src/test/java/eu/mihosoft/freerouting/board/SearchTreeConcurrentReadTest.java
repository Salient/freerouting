package eu.mihosoft.freerouting.board;

import eu.mihosoft.freerouting.designforms.specctra.DsnFile;
import eu.mihosoft.freerouting.geometry.planar.IntBox;
import eu.mihosoft.freerouting.geometry.planar.TileShape;
import eu.mihosoft.freerouting.interactive.BoardHandlingImpl;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertNull;

/**
 * Concurrent read-only queries against one search tree must agree with the serial answer.
 *
 * <p>MinAreaTree.node_stack used to be a shared instance field, so every traversal of a tree --
 * including pure reads like overlapping_objects -- took turns writing to the same scratch
 * stack. Two interleaved traversals corrupted each other silently: wrong results, or an
 * ArrayIndexOutOfBoundsException, never a clean failure. That is why no concurrent search-tree
 * read was safe, which blocked every parallel routing scheme, and it was a latent bug even
 * single-threaded for any two traversals that interleaved.
 *
 * <p>This test fails against the shared-field version and passes once the stack is a local. It
 * asserts agreement with a serial baseline rather than merely "no exception thrown", because
 * the corruption mode is wrong answers at least as often as it is a crash.
 */
public class SearchTreeConcurrentReadTest
{
    private static final int THREADS = 8;
    private static final int ROUNDS_PER_THREAD = 40;

    @Test
    public void concurrent_reads_agree_with_serial_reads() throws Exception
    {
        File design = new File("tests/pic_programmer.dsn");
        Assume.assumeTrue(design.isFile());

        BoardHandlingImpl handling = new BoardHandlingImpl();
        DsnFile.ReadResult read_result;
        try (java.io.InputStream in = new java.io.FileInputStream(design))
        {
            // A real id generator and observer: the board assigns an id to every item it
            // inserts (BoardOutline included), so passing null here NPEs during construction.
            read_result = DsnFile.read(in, handling, new BoardObserverAdaptor(),
                    new ItemIdNoGenerator(), TestLevel.RELEASE_VERSION);
        }
        Assume.assumeTrue(read_result == DsnFile.ReadResult.OK);

        final BasicBoard board = handling.get_routing_board();
        Assume.assumeNotNull(board);
        final ShapeSearchTree tree = board.search_tree_manager.get_default_tree();
        Assume.assumeNotNull(tree);

        // Probe boxes spread over the board, each a realistic overlap query.
        IntBox bounds = board.get_bounding_box();
        final List<TileShape> probes = new ArrayList<>();
        int x_step = Math.max(1, bounds.width() / 10);
        int y_step = Math.max(1, bounds.height() / 10);
        for (int x = bounds.ll.x; x < bounds.ur.x; x += x_step)
        {
            for (int y = bounds.ll.y; y < bounds.ur.y; y += y_step)
            {
                // IntBox already implements TileShape here, so no conversion is needed.
                probes.add(new IntBox(x, y, x + x_step, y + y_step));
            }
        }
        Assume.assumeTrue(probes.size() >= 4);

        // Serial baseline, computed with nothing else running.
        final List<Integer> expected = new ArrayList<>();
        for (TileShape probe : probes)
        {
            expected.add(tree.overlapping_objects(probe, -1).size());
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try
        {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int t = 0; t < THREADS; ++t)
            {
                jobs.add(new Callable<String>()
                {
                    public String call()
                    {
                        for (int round = 0; round < ROUNDS_PER_THREAD; ++round)
                        {
                            for (int i = 0; i < probes.size(); ++i)
                            {
                                Set<SearchTreeObject> hit =
                                        tree.overlapping_objects(probes.get(i), -1);
                                if (hit.size() != expected.get(i))
                                {
                                    return "probe " + i + " returned " + hit.size()
                                            + " concurrently but " + expected.get(i)
                                            + " serially";
                                }
                            }
                        }
                        return null;
                    }
                });
            }
            for (Future<String> f : pool.invokeAll(jobs))
            {
                // get() rethrows anything the worker threw, e.g. the
                // ArrayIndexOutOfBoundsException a shared scratch stack produces.
                assertNull("concurrent read disagreed with the serial answer", f.get());
            }
        }
        finally
        {
            pool.shutdownNow();
        }
    }
}
