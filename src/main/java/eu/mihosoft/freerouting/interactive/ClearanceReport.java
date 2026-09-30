package eu.mihosoft.freerouting.interactive;

import eu.mihosoft.freerouting.board.BasicBoard;
import eu.mihosoft.freerouting.board.ClearanceViolation;
import eu.mihosoft.freerouting.board.Item;
import eu.mihosoft.freerouting.logger.FRLogger;
import eu.mihosoft.freerouting.rules.Net;

import java.io.PrintWriter;
import java.util.Collection;

/**
 * Writes a clearance-violation report for a whole board.
 *
 * <p>Used by the -rm verify mode, which imports a design and audits it against the rules
 * without routing anything. On a high-voltage board this is the cheap way to ask whether the
 * copper that is already there actually meets the spacing the design calls for.
 *
 * <p>All of the geometry work is done by Item.clearance_violations(), which already resolves
 * each pair against BoardRules.clearance_matrix. The de-duplication (each overlap is found
 * from both items' point of view) and the filtering (same-net pairs are connected, not too
 * close; sub-{@link ClearanceViolations#MIN_REPORTED_OVERLAP_MIL}-mil overlaps are rounding
 * noise from the integer clearance check) live in ClearanceViolations, so the interactive
 * display and this report are always looking at the same set of violations. This class only
 * formats that set, so the report is only as correct as the clearance matrix that was
 * imported -- which is the entire reason AltiumConstraintsFile exists.
 */
public class ClearanceReport
{
    private ClearanceReport()
    {
    }

    /**
     * Writes one line per violation to p_output, plus a summary. Returns the number of
     * violations found, so a caller can use it as an exit status.
     */
    public static int write(BasicBoard p_board, PrintWriter p_output)
    {
        Collection<Item> items = p_board.get_items();
        ClearanceViolations violations = new ClearanceViolations(items);
        java.util.List<String> lines = new java.util.ArrayList<>();

        for (ClearanceViolation violation : violations.list)
        {
            // The violation shape is the overlap of the two clearance-enlarged outlines, so
            // its width is how far short of the required clearance the pair falls.
            double shortfall = p_board.communication.coordinate_transform.board_to_dsn(
                    2 * violation.shape.smallest_radius());
            lines.add(format(p_board, violation, shortfall));
        }

        int below_tolerance = violations.excluded_below_tolerance_count;
        java.util.Collections.sort(lines);
        p_output.println("# freerouting clearance verification");
        p_output.println("# layer\trequired\tactual_overlap\tnet_a\titem_a\tnet_b\titem_b\tat_x\tat_y");
        for (String line : lines)
        {
            p_output.println(line);
        }
        p_output.println("# " + lines.size() + " clearance violation(s) over " + items.size() + " item(s)");
        p_output.println("# " + below_tolerance + " further overlap(s) under "
                + ClearanceViolations.MIN_REPORTED_OVERLAP_MIL
                + " mil not reported (rounding in the clearance check, not design violations)");
        p_output.flush();

        String suppressed = below_tolerance == 0 ? ""
                : " (" + below_tolerance + " more under " + ClearanceViolations.MIN_REPORTED_OVERLAP_MIL
                    + " mil not reported)";
        if (lines.isEmpty())
        {
            FRLogger.info("Clearance verification: no violations over " + items.size() + " items" + suppressed + ".");
        }
        else
        {
            FRLogger.warn("Clearance verification: " + lines.size() + " violation(s) over "
                    + items.size() + " items" + suppressed + ".");
        }
        return lines.size();
    }

    private static String format(BasicBoard p_board, ClearanceViolation p_violation, double p_shortfall_mil)
    {
        int layer = p_violation.layer;
        String layer_name = layer >= 0 && layer < p_board.layer_structure.arr.length
                ? p_board.layer_structure.arr[layer].name
                : String.valueOf(layer);
        int required = p_board.rules.clearance_matrix.value(
                p_violation.first_item.clearance_class_no(),
                p_violation.second_item.clearance_class_no(),
                layer);
        // Positions need the point overload: the scalar one only rescales, it does not apply
        // the base offset, so using it for a coordinate would report the wrong location.
        double[] at = p_board.communication.coordinate_transform.board_to_dsn(
                p_violation.shape.centre_of_gravity());
        return layer_name
                + "\t" + round(p_board.communication.coordinate_transform.board_to_dsn(required))
                + "\t" + round(p_shortfall_mil)
                + "\t" + net_names(p_violation.first_item)
                + "\t" + item_description(p_violation.first_item)
                + "\t" + net_names(p_violation.second_item)
                + "\t" + item_description(p_violation.second_item)
                + "\t" + round(at[0])
                + "\t" + round(at[1]);
    }

    private static double round(double p_value)
    {
        return Math.round(p_value * 1000.0) / 1000.0;
    }

    private static String net_names(Item p_item)
    {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < p_item.net_count(); ++i)
        {
            Net net = p_item.board.rules.nets.get(p_item.get_net_no(i));
            if (net == null)
            {
                continue;
            }
            if (result.length() > 0)
            {
                result.append(',');
            }
            result.append(net.name);
        }
        return result.length() == 0 ? "(no net)" : result.toString();
    }

    private static String item_description(Item p_item)
    {
        String type = p_item.getClass().getSimpleName();
        String clearance_class = p_item.board.rules.clearance_matrix.get_name(p_item.clearance_class_no());
        return type + "#" + p_item.get_id_no() + "[" + clearance_class + "]";
    }
}
