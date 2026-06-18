package app.freerouting.io.specctra;

import app.freerouting.Freerouting;
import app.freerouting.board.RoutingBoard;
import app.freerouting.io.BoardReadResult;
import app.freerouting.settings.GlobalSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DsnReaderTest {

  @BeforeEach
  void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
  }

  // Happy-path

  @Test
  void readBoardReturnsSuccess() {
    InputStream in = DsnTestFixtures.openResource("Issue143-rpi_splitter.dsn");
    BoardReadResult result = DsnReader.readBoard(in, null, null);

    assertInstanceOf(BoardReadResult.Success.class, result,
        "Expected Success for a well-formed DSN file");
    BoardReadResult.Success success = (BoardReadResult.Success) result;
    assertNotNull(success.board(), "Board must not be null on success");
    assertEquals(2, success.board().get_layer_count(),
        "Issue143-rpi_splitter.dsn is a 2-layer board");
  }

  @Test
  void readBoardSetsHostCad() {
    InputStream in = DsnTestFixtures.openResource("Issue143-rpi_splitter.dsn");
    BoardReadResult result = DsnReader.readBoard(in, null, null);

    assertInstanceOf(BoardReadResult.Success.class, result);
    RoutingBoard board = (RoutingBoard) ((BoardReadResult.Success) result).board();
    assertNotNull(board.communication.specctra_parser_info,
        "SpecctraParserInfo must be populated");
  }

  // Parse-error path

  @Test
  void readBoardReturnsParseErrorForGarbage() {
    InputStream in = new ByteArrayInputStream("not a dsn file".getBytes(StandardCharsets.UTF_8));
    BoardReadResult result = DsnReader.readBoard(in, null, null);

    assertInstanceOf(BoardReadResult.ParseError.class, result,
        "Garbage input must produce ParseError");
  }

  @Test
  void readBoardReturnsParseErrorForNullStream() {
    BoardReadResult result = DsnReader.readBoard(null, null, null);
    assertInstanceOf(BoardReadResult.ParseError.class, result);
  }

  // OutlineMissing path — synthetic DSN with no (boundary ...) scope

  private static final String DSN_NO_BOUNDARY =
      "(pcb test\n"
          + "  (parser (string_quote \"))\n"
          + "  (resolution um 10)\n"
          + "  (unit um)\n"
          + "  (structure\n"
          + "    (layer F.Cu (type signal) (property (index 0)))\n"
          + "    (layer B.Cu (type signal) (property (index 1)))\n"
          + "  )\n"
          + ")\n";

  @Test
  void readBoardReturnsOutlineMissingWhenBoundaryAbsent() {
    InputStream in = new ByteArrayInputStream(DSN_NO_BOUNDARY.getBytes(StandardCharsets.UTF_8));
    BoardReadResult result = DsnReader.readBoard(in, null, null);

    assertInstanceOf(BoardReadResult.OutlineMissing.class, result,
        "A DSN file with no (boundary ...) scope must produce OutlineMissing");
  }

  // empty_board.dsn has a boundary and should succeed

  @Test
  void readBoardSucceedsForEmptyBoard() {
    InputStream in = DsnTestFixtures.openResource("empty_board.dsn");
    BoardReadResult result = DsnReader.readBoard(in, null, null);

    assertInstanceOf(BoardReadResult.Success.class, result,
        "empty_board.dsn has a valid boundary and must succeed");
  }

  // Shapeless-padstack path — some CAD tools (e.g. Altium) export padstacks with no (shape ...)
  // scope for mounting holes / NPTH / fiducials. A component pin referencing such a padstack used
  // to abort the whole parse ("DSN structure parsing failed"). It must now load successfully, with
  // the shapeless pin simply skipped during board item insertion.

  private static final String DSN_EMPTY_PADSTACK =
      "(pcb test\n"
          + "  (parser (string_quote \"))\n"
          + "  (resolution um 10)\n"
          + "  (unit um)\n"
          + "  (structure\n"
          + "    (layer F.Cu (type signal) (property (index 0)))\n"
          + "    (layer B.Cu (type signal) (property (index 1)))\n"
          + "    (boundary (path pcb 0  0 0  100000 0  100000 100000  0 100000  0 0))\n"
          + "  )\n"
          + "  (placement\n"
          + "    (component MOUNT\n"
          + "      (place H1 50000 50000 front 0)\n"
          + "    )\n"
          + "  )\n"
          + "  (library\n"
          + "    (image MOUNT\n"
          + "      (pin EmptyPad 1 0 0)\n"
          + "    )\n"
          + "    (padstack EmptyPad\n"   // no (shape ...) — the Altium failure mode
          + "    )\n"
          + "  )\n"
          + "  (network\n"
          + "    (net GND\n"
          + "      (pins H1-1)\n"
          + "    )\n"
          + "  )\n"
          + ")\n";

  @Test
  void readBoardSucceedsWhenPinReferencesShapelessPadstack() {
    InputStream in = new ByteArrayInputStream(DSN_EMPTY_PADSTACK.getBytes(StandardCharsets.UTF_8));
    BoardReadResult result = DsnReader.readBoard(in, null, null);

    assertInstanceOf(BoardReadResult.Success.class, result,
        "A pin referencing a shapeless padstack must not abort the parse; "
            + "the shapeless pin should be skipped and the board loaded");
    assertNotNull(((BoardReadResult.Success) result).board(),
        "Board must not be null when a shapeless padstack is tolerated");
  }

  // Malformed-but-recoverable constructs that sloppy CAD exporters emit. Each used to abort the
  // parse (or crash); they must now load successfully with a warning and a sensible default.

  /**
   * Builds a minimal but complete, parseable DSN file. {@code resolutionLine} and {@code extras}
   * let individual tests inject the malformation under test.
   */
  private static String dsn(String resolutionLine, String extras) {
    return "(pcb test\n"
        + "  (parser (string_quote \"))\n"
        + "  " + resolutionLine + "\n"
        + "  (unit um)\n"
        + "  (structure\n"
        + "    (layer F.Cu (type signal) (property (index 0)))\n"
        + "    (layer B.Cu (type signal) (property (index 1)))\n"
        + "    (boundary (path pcb 0  0 0  100000 0  100000 100000  0 100000  0 0))\n"
        + extras
        + "  )\n"
        + ")\n";
  }

  private static BoardReadResult read(String dsn) {
    return DsnReader.readBoard(
        new ByteArrayInputStream(dsn.getBytes(StandardCharsets.UTF_8)), null, null);
  }

  @Test
  void readBoardToleratesZeroResolution() {
    // resolution is used as a divisor in coordinate transforms; 0 must not corrupt the board.
    BoardReadResult result = read(dsn("(resolution um 0)", ""));
    assertInstanceOf(BoardReadResult.Success.class, result,
        "A resolution of 0 must be defaulted, not abort the parse");
  }

  @Test
  void readBoardToleratesUnknownUnit() {
    BoardReadResult result = read(dsn("(resolution parsecs 10)", ""));
    assertInstanceOf(BoardReadResult.Success.class, result,
        "An unrecognised unit must default to mil, not abort the parse");
  }

  @Test
  void readBoardToleratesNonNumericRuleWidth() {
    // (width foo) — a value carrying a stray unit suffix used to NPE on unboxing in next_double().
    BoardReadResult result = read(dsn("(resolution um 10)",
        "    (rule (width foo) (clearance 8.0))\n"));
    assertInstanceOf(BoardReadResult.Success.class, result,
        "A non-numeric rule width must be skipped, not crash the parse");
  }

  @Test
  void readBoardToleratesPinWithoutClearanceClass() {
    // A bare (pin <name>) inside a (place ...) with no (clearance_class ...) used to discard the
    // entire component placement. The component must survive.
    String extras = "";
    String dsn = "(pcb test\n"
        + "  (parser (string_quote \"))\n"
        + "  (resolution um 10)\n"
        + "  (unit um)\n"
        + "  (structure\n"
        + "    (layer F.Cu (type signal) (property (index 0)))\n"
        + "    (layer B.Cu (type signal) (property (index 1)))\n"
        + "    (boundary (path pcb 0  0 0  100000 0  100000 100000  0 100000  0 0))\n"
        + "  )\n"
        + "  (placement\n"
        + "    (component CMP\n"
        + "      (place C1 50000 50000 front 0\n"
        + "        (pin P1)\n"   // no (clearance_class ...) sub-scope
        + "      )\n"
        + "    )\n"
        + "  )\n"
        + "  (library\n"
        + "    (image CMP\n"
        + "      (pin RoundPad P1 0 0)\n"
        + "    )\n"
        + "    (padstack RoundPad\n"
        + "      (shape (circle F.Cu 200 0 0))\n"
        + "    )\n"
        + "  )\n"
        + "  (network\n"
        + "    (net GND (pins C1-P1))\n"
        + "  )\n"
        + ")\n";
    BoardReadResult result = read(dsn);
    assertInstanceOf(BoardReadResult.Success.class, result,
        "A pin without a clearance class must not drop its component or abort the parse");
  }

  // Sealed-switch exhaustiveness check (compile-time guarantee)

  @Test
  void patternSwitchIsExhaustive() {
    InputStream in = new ByteArrayInputStream("not dsn".getBytes(StandardCharsets.UTF_8));
    BoardReadResult result = DsnReader.readBoard(in, null, null);

    String label = switch (result) {
      case BoardReadResult.Success _        -> "success";
      case BoardReadResult.OutlineMissing _ -> "outline";
      case BoardReadResult.ParseError _     -> "parse";
      case BoardReadResult.IoError _        -> "io";
    };
    assertNotNull(label);
  }
}