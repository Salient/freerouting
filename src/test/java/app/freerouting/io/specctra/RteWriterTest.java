package app.freerouting.io.specctra;

import app.freerouting.Freerouting;
import app.freerouting.board.RoutingBoard;
import app.freerouting.settings.GlobalSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RteWriter} (Specctra routing-only route files).
 */
class RteWriterTest {

  @BeforeEach
  void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
  }

  /**
   * Verifies that {@link RteWriter} produces a top-level {@code (routes ...)} scope — i.e. a
   * routing-only file with no {@code (session ...)} wrapper, placement or {@code was_is} data.
   */
  @Test
  void rteWriterProducesTopLevelRoutesScope() throws Exception {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue026-J2_reference.dsn");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    RteWriter.write(board, out);

    String content = out.toString(StandardCharsets.UTF_8);
    assertTrue(content.startsWith("(routes"),
        "RTE output must start with '(routes'; got: "
            + content.substring(0, Math.min(50, content.length())));
    assertFalse(content.contains("(session"),
        "RTE output must not contain a '(session' wrapper");
    assertFalse(content.contains("(placement"),
        "RTE output must not contain a '(placement' scope");
    assertTrue(content.contains("(network_out"),
        "RTE output must contain a '(network_out' scope");
  }

  /**
   * Verifies that the routing payload written to an RTE file is identical to the
   * {@code (routes ...)} scope nested inside the SES file for the same board. This guarantees the
   * two formats never drift apart, since both share {@link SesWriter}'s serialisation.
   *
   * <p>The comparison is done with each line's leading indentation stripped: the SES
   * {@code (routes ...)} scope sits one level deep inside {@code (session ...)} and so is indented
   * two extra spaces per line, whereas the RTE scope is at the top level. That indentation offset
   * is the only legitimate difference; the actual wire/via/net/path tokens must match exactly.
   */
  @Test
  void rtePayloadMatchesSesRoutesScope() throws Exception {
    // Load a board with actual routing so the routes scope is non-trivial.
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue593-BBD_Mars-64.dsn");
    try (InputStream sesIn = DsnTestFixtures.openFixtureStream("Issue593-BBD_Mars-64.ses")) {
      SesImportSummary imported = SesReader.read(sesIn, board);
      assertTrue(imported.wiresImported() > 0, "Fixture SES must contain at least one wire");
    }

    ByteArrayOutputStream rteOut = new ByteArrayOutputStream();
    RteWriter.write(board, rteOut);
    String rteContent = rteOut.toString(StandardCharsets.UTF_8);

    ByteArrayOutputStream sesOut = new ByteArrayOutputStream();
    SesWriter.write(board, sesOut, "compare.dsn");
    String sesContent = sesOut.toString(StandardCharsets.UTF_8);

    // Extract the (routes ...) scope from the SES output. It begins at "(routes" and runs to the
    // end of the file minus the single trailing ")" that closes the (session ...) wrapper.
    int routesStart = sesContent.indexOf("(routes");
    assertTrue(routesStart >= 0, "SES output must contain a (routes scope");
    String sesRoutesScope = sesContent.substring(routesStart);
    int lastParen = sesRoutesScope.lastIndexOf(')');
    sesRoutesScope = sesRoutesScope.substring(0, lastParen);

    assertEquals(stripPerLineIndent(sesRoutesScope), stripPerLineIndent(rteContent),
        "RTE routing payload must match the SES (routes ...) scope (ignoring indentation depth)");
  }

  /** Strips leading whitespace from every line and drops trailing blank lines. */
  private static String stripPerLineIndent(String s) {
    String[] lines = s.split("\n", -1);
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      sb.append(line.stripLeading()).append('\n');
    }
    return sb.toString().stripTrailing();
  }

  /**
   * Verifies that {@link RteWriter} flushes data to the stream (non-zero output size).
   */
  @Test
  void rteWriterOutputIsNonEmpty() throws Exception {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue143-rpi_splitter.dsn");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    RteWriter.write(board, out);
    assertTrue(out.size() > 0, "RteWriter must write data to the stream");
  }
}
