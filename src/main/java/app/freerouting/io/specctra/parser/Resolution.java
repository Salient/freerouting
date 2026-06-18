package app.freerouting.io.specctra.parser;

import app.freerouting.board.Communication;
import app.freerouting.board.Unit;
import app.freerouting.datastructures.IndentFileWriter;
import app.freerouting.logger.FRLogger;
import java.io.IOException;

/**
 * Class for reading resolution scopes from dsn-files.
 */
public class Resolution extends ScopeKeyword {

  /**
   * Creates a new instance of Resolution
   */
  public Resolution() {
    super("resolution");
  }

  public static void write_scope(IndentFileWriter p_file, Communication p_board_communication) throws IOException {
    p_file.new_line();
    p_file.write("(resolution ");
    p_file.write(p_board_communication.unit.toString());
    p_file.write(" ");
    p_file.write(String.valueOf(p_board_communication.resolution));
    p_file.write(")");
  }

  @Override
  public boolean read_scope(ReadScopeParameter p_par) {
    try {
      // read the unit
      Object next_token = p_par.scanner.next_token();
      if (!(next_token instanceof String)) {
        FRLogger.warn("Resolution.read_scope: string expected at '" + p_par.scanner.get_scope_identifier() + "'");
        return false;
      }
      p_par.unit = Unit.from_string((String) next_token);
      if (p_par.unit == null) {
        // An unrecognised unit string should not abort the whole parse. Default to mil (the SPECCTRA
        // default) and keep going; the integer value and closing bracket are still consumed below.
        FRLogger.warn("Resolution.read_scope: unrecognised unit '" + next_token + "'; defaulting to mil at '" + p_par.scanner.get_scope_identifier() + "'");
        p_par.unit = Unit.MIL;
      }
      // read the scale factor
      next_token = p_par.scanner.next_token();
      if (!(next_token instanceof Integer)) {
        FRLogger.warn("Resolution.read_scope: integer expected at '" + p_par.scanner.get_scope_identifier() + "'");
        return false;
      }
      int resolution_value = (Integer) next_token;
      if (resolution_value <= 0) {
        // The resolution is used as a divisor in coordinate transforms; a value of 0 (or negative)
        // would produce Infinity/NaN coordinates and silently corrupt the whole board. Fall back to
        // the SPECCTRA default of 100 instead.
        FRLogger.warn("Resolution.read_scope: resolution must be positive, got " + resolution_value + "; defaulting to 100 at '" + p_par.scanner.get_scope_identifier() + "'");
        resolution_value = 100;
      }
      p_par.resolution = resolution_value;
      // overread the closing bracket
      next_token = p_par.scanner.next_token();
      if (next_token != CLOSED_BRACKET) {
        FRLogger.warn("Resolution.read_scope: closing bracket expected at '" + p_par.scanner.get_scope_identifier() + "'");
        return false;
      }
      return true;
    } catch (IOException e) {
      FRLogger.error("Resolution.read_scope: IO error scanning file", e);
      return false;
    }
  }
}