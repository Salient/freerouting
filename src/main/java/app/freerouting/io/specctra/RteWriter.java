package app.freerouting.io.specctra;

import app.freerouting.board.BasicBoard;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes a Specctra route (.rte) file from a {@link BasicBoard}.
 *
 * <p>A route file carries the routing solution as a top-level {@code (routes ...)} scope
 * (resolution, parser, {@code library_out} and {@code network_out}) <em>without</em> the
 * {@code (session ...)} wrapper, component placement or {@code was_is} data that a Specctra
 * session (.ses) file adds. Host CAD tools that import results as {@code .rte} expect this
 * narrower, routing-only form so the traces and vias merge back onto an otherwise unchanged
 * board.
 *
 * <p>The routing payload is produced by the same serialisation code as {@link SesWriter}, so the
 * {@code (routes ...)} scope written here is byte-for-byte identical to the one nested inside the
 * corresponding {@code .ses} file.
 *
 * <p>This class has no dependency on {@code BoardManager}, {@code RoutingJob}, or any GUI class.
 * It operates purely on the board's data model.
 */
public final class RteWriter {

  private RteWriter() {
  }

  /**
   * Serialises the routing result from {@code board} to Specctra route (.rte) format on the given
   * stream.
   *
   * <p>The stream is <em>flushed</em> after writing but is <strong>not closed</strong> — the
   * caller retains ownership of the stream lifecycle.
   *
   * @param board the board whose routing data is serialised (must not be {@code null})
   * @param out   target stream (caller owns lifecycle; must not be {@code null})
   * @throws IOException if an I/O error occurs during writing
   * @see SesWriter#write
   */
  public static void write(BasicBoard board, OutputStream out) throws IOException {
    SesWriter.writeRoutesOnly(board, out);
  }
}
