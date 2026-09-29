/*
 *   FRPCB interchange format reader.
 *
 *   See docs/frpcb-format.md for the format specification. This class parses
 *   an FRPCB JSON file directly into the same internal board/rules model that
 *   eu.mihosoft.freerouting.designforms.specctra.DsnFile populates from a
 *   Specctra dsn-file, using the same board-construction APIs
 *   (IBoardHandling.create_board, BasicBoard.insert_*, BoardRules) so an
 *   imported FRPCB board behaves identically to one imported from DSN.
 */
package eu.mihosoft.freerouting.designforms.frpcb;

import eu.mihosoft.freerouting.board.BasicBoard;
import eu.mihosoft.freerouting.board.Communication;
import eu.mihosoft.freerouting.board.FixedState;
import eu.mihosoft.freerouting.board.Layer;
import eu.mihosoft.freerouting.board.LayerStructure;
import eu.mihosoft.freerouting.board.RoutingBoard;
import eu.mihosoft.freerouting.board.TestLevel;
import eu.mihosoft.freerouting.board.Unit;
import eu.mihosoft.freerouting.designforms.specctra.CoordinateTransform;
import eu.mihosoft.freerouting.geometry.planar.Area;
import eu.mihosoft.freerouting.geometry.planar.Circle;
import eu.mihosoft.freerouting.geometry.planar.ConvexShape;
import eu.mihosoft.freerouting.geometry.planar.IntBox;
import eu.mihosoft.freerouting.geometry.planar.IntPoint;
import eu.mihosoft.freerouting.geometry.planar.IntVector;
import eu.mihosoft.freerouting.geometry.planar.Line;
import eu.mihosoft.freerouting.geometry.planar.Point;
import eu.mihosoft.freerouting.geometry.planar.Polygon;
import eu.mihosoft.freerouting.geometry.planar.PolygonShape;
import eu.mihosoft.freerouting.geometry.planar.Polyline;
import eu.mihosoft.freerouting.geometry.planar.PolylineShape;
import eu.mihosoft.freerouting.geometry.planar.Simplex;
import eu.mihosoft.freerouting.geometry.planar.TileShape;
import eu.mihosoft.freerouting.geometry.planar.Vector;
import eu.mihosoft.freerouting.interactive.IBoardHandling;
import eu.mihosoft.freerouting.library.Padstack;
import eu.mihosoft.freerouting.library.Padstacks;
import eu.mihosoft.freerouting.logger.FRLogger;
import eu.mihosoft.freerouting.rules.BoardRules;
import eu.mihosoft.freerouting.rules.ClearanceMatrix;
import eu.mihosoft.freerouting.rules.DefaultItemClearanceClasses.ItemClass;
import eu.mihosoft.freerouting.rules.Net;
import eu.mihosoft.freerouting.rules.NetClass;
import eu.mihosoft.freerouting.rules.ViaInfo;
import eu.mihosoft.freerouting.rules.ViaRule;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Reads an FRPCB json-file into a freerouting board, in place of a Specctra
 * dsn-file. See docs/frpcb-format.md.
 */
public class FrpcbFile
{
    public enum ReadResult
    {
        OK, OUTLINE_MISSING, ERROR
    }

    private static final int SUPPORTED_VERSION = 1;

    /** Clearance-matrix class used for the board outline when the file gives an edge clearance. */
    private static final String OUTLINE_CLEARANCE_CLASS = "outline";

    private FrpcbFile()
    {
    }

    /**
     * Reads an FRPCB file. p_constraints_file is an optional Altium Constraints.xml whose
     * clearance matrix overrides the one carried in the json -- see AltiumConstraintsFile
     * for why that is necessary. Pass null to use the json's own clearance_matrix.
     */
    public static ReadResult read(java.io.InputStream p_input_stream, IBoardHandling p_board_handling,
                                  eu.mihosoft.freerouting.board.BoardObservers p_observers,
                                  eu.mihosoft.freerouting.datastructures.IdNoGenerator p_item_id_no_generator, TestLevel p_test_level,
                                  java.io.File p_constraints_file)
    {
        JSONObject root;
        try
        {
            root = new JSONObject(new JSONTokener(p_input_stream));
        }
        catch (Exception e)
        {
            FRLogger.error("FrpcbFile.read: not a valid JSON file", e);
            return ReadResult.ERROR;
        }

        int version = root.optInt("frpcb_version", -1);
        if (version != SUPPORTED_VERSION)
        {
            FRLogger.error("FrpcbFile.read: unsupported frpcb_version '" + version + "'; expected " + SUPPORTED_VERSION, null);
            return ReadResult.ERROR;
        }

        String unit_name = root.optString("unit", "mil");
        double units_per_mil = unit_name.equalsIgnoreCase("mm") ? 1.0 / 0.0254 : 1.0;
        if (!unit_name.equalsIgnoreCase("mil") && !unit_name.equalsIgnoreCase("mm"))
        {
            FRLogger.warn("FrpcbFile.read: unknown unit '" + unit_name + "', defaulting to mil");
        }

        JSONObject board_obj = root.optJSONObject("board");
        if (board_obj == null)
        {
            FRLogger.error("FrpcbFile.read: 'board' section missing", null);
            return ReadResult.ERROR;
        }
        JSONArray layers_arr = board_obj.optJSONArray("layers");
        if (layers_arr == null || layers_arr.isEmpty())
        {
            FRLogger.error("FrpcbFile.read: 'board.layers' missing or empty", null);
            return ReadResult.ERROR;
        }
        JSONArray outline_arr = board_obj.optJSONArray("outline");
        if (outline_arr == null || outline_arr.isEmpty())
        {
            FRLogger.warn("FrpcbFile.read: 'board.outline' missing or empty");
            return ReadResult.OUTLINE_MISSING;
        }

        // Build the layer structure.
        Layer[] board_layers = new Layer[layers_arr.length()];
        Map<String, Integer> layer_no_by_name = new HashMap<>();
        for (int i = 0; i < layers_arr.length(); ++i)
        {
            JSONObject layer_obj = layers_arr.getJSONObject(i);
            String layer_name = layer_obj.getString("name");
            boolean is_signal = layer_obj.optBoolean("signal", true);
            board_layers[i] = new Layer(layer_name, is_signal);
            layer_no_by_name.put(layer_name, i);
        }
        LayerStructure layer_structure = new LayerStructure(board_layers);

        // Scale factor: FRPCB coordinates are already in the file's declared unit (mil or mm).
        // Mirror Structure.create_board's approach of choosing a scale factor from the board
        // extent so that scaled coordinates fit safely inside the internal integer coordinate
        // space, then reuse the same CoordinateTransform / Communication path DSN import uses,
        // so an FRPCB-imported board is numerically identical in scale to a DSN-imported one.
        double[] outline_bounds = polygon_list_bounds(outline_arr);
        if (outline_bounds == null)
        {
            FRLogger.warn("FrpcbFile.read: 'board.outline' has no usable polygon");
            return ReadResult.OUTLINE_MISSING;
        }
        int resolution = 100; // 1/100 mil internal resolution, matching Specctra's common default.
        int scale_factor = resolution;
        double max_coor = 0;
        for (double coor : outline_bounds)
        {
            max_coor = Math.max(max_coor, Math.abs(coor * units_per_mil) * scale_factor);
        }
        while (max_coor > 0 && 5 * max_coor >= eu.mihosoft.freerouting.geometry.planar.Limits.CRIT_INT)
        {
            scale_factor /= 10;
            max_coor /= 10;
        }
        CoordinateTransform coordinate_transform = new CoordinateTransform(scale_factor, 0, 0);

        // A JSON length value is in the file's unit; convert to mils, then let
        // CoordinateTransform.dsn_to_board apply scale_factor exactly once - mirroring
        // Structure.java's DSN scaling (dsn_to_board already IS the resolution multiply;
        // multiplying by resolution again before calling it double-applies the factor and
        // overflows the internal integer coordinate space on real board-sized inputs).
        LengthConverter len = new LengthConverter(coordinate_transform, units_per_mil);

        List<PolylineShape> outline_shapes = new LinkedList<>();
        for (int i = 0; i < outline_arr.length(); ++i)
        {
            PolygonShape shape = polygon_to_polyline_shape(outline_arr.getJSONArray(i), len);
            if (shape == null || shape.is_empty())
            {
                FRLogger.warn("FrpcbFile.read: skipping an unusable outline polygon at index " + i);
                continue;
            }
            outline_shapes.add(shape);
        }
        if (outline_shapes.isEmpty())
        {
            FRLogger.warn("FrpcbFile.read: no usable outline polygons found");
            return ReadResult.OUTLINE_MISSING;
        }

        IntBox bounding_box = outline_shapes.get(0).bounding_box();
        for (PolylineShape shape : outline_shapes)
        {
            bounding_box = bounding_box.union(shape.bounding_box());
        }
        bounding_box = bounding_box.offset(1000);

        ClearanceMatrix clearance_matrix = ClearanceMatrix.get_default_instance(layer_structure, 0);
        BoardRules board_rules = new BoardRules(layer_structure, clearance_matrix);
        Communication.SpecctraParserInfo specctra_parser_info =
                new Communication.SpecctraParserInfo("\"", "frpcb", null, null, null, true);
        Communication communication = new Communication(Unit.MIL, resolution, specctra_parser_info,
                coordinate_transform, p_item_id_no_generator, p_observers);

        // Give the board outline its own clearance class when the file specifies an
        // edge clearance, so the router keeps copper away from the board edge. Passing
        // null here (as this used to) leaves the outline on the default class, which
        // lets the autorouter route right up to the edge.
        String outline_clearance_class_name = null;
        double outline_clearance = board_obj.optDouble("outline_clearance", 0);
        if (outline_clearance > 0)
        {
            outline_clearance_class_name = OUTLINE_CLEARANCE_CLASS;
            clearance_matrix.append_class(OUTLINE_CLEARANCE_CLASS);
            int outline_class_no = clearance_matrix.get_no(OUTLINE_CLEARANCE_CLASS);
            int clearance = (int) Math.round(len.to_board(outline_clearance));
            // Every other class has to keep this clearance to the outline, so set the whole
            // row and column -- but NOT the diagonal. The diagonal is the class's self
            // clearance, and ClearanceMatrix.clearance_compensation_value is half of it, used
            // to pre-enlarge every shape of the class in the search tree. An edge clearance is
            // a distance from other copper, not a spacing the outline owes itself, so putting
            // it on the diagonal is wrong in principle.
            //
            // Note this is currently inert: SearchTreeManager initialises
            // clearance_compensation_used to false, so nothing reads the diagonal. It was
            // changed while testing whether compensation explained freerouting reporting 931
            // board-outline violations against copper that is genuinely 58 to 150 mil from the
            // outline. It does not - the count is identical either way - and that over-report
            // is still unexplained.
            // Start at 1, NOT 0. Class 0 is the "null" class (see
            // ClearanceMatrix.get_default_instance), and the search tree uses it as its
            // compensation reference: ShapeSearchTree.clearance_compensation_value returns
            // value(this_class, compensated_clearance_class_no), which for the default tree is
            // value(outline, 0). Setting that cell made the tree bake the whole edge clearance
            // into the outline's stored shape - probed at 202 mil wide for a 100 mil rule, i.e.
            // 101 mil per side off a 1 mil centreline - and Item.clearance_violations then
            // enlarged both shapes by half the clearance on top of that. The edge rule was
            // effectively enforced at around 150 mil, which is what produced 931 violations
            // against copper genuinely 58 to 150 mil from the outline when only 5 pieces of
            // non-CHASSIS copper are truly inside 100 mil.
            for (int i = 1; i < clearance_matrix.get_class_count(); ++i)
            {
                if (i == outline_class_no)
                {
                    continue;
                }
                clearance_matrix.set_value(outline_class_no, i, clearance);
                clearance_matrix.set_value(i, outline_class_no, clearance);
            }
        }

        p_board_handling.create_board(bounding_box, layer_structure,
                outline_shapes.toArray(new PolylineShape[0]), outline_clearance_class_name,
                board_rules, communication, p_test_level);

        RoutingBoard board = p_board_handling.get_routing_board();
        if (board == null)
        {
            FRLogger.error("FrpcbFile.read: board could not be created", null);
            return ReadResult.ERROR;
        }

        // Keepouts.
        JSONArray keepouts_arr = board_obj.optJSONArray("keepouts");
        if (keepouts_arr != null)
        {
            read_keepouts(keepouts_arr, board, layer_no_by_name, len);
        }

        // Padstacks.
        board.library.padstacks = new Padstacks(layer_structure);
        board.library.packages = new eu.mihosoft.freerouting.library.Packages(board.library.padstacks);
        Map<String, Padstack> padstacks_by_name = new HashMap<>();
        JSONArray padstacks_arr = root.optJSONArray("padstacks");
        if (padstacks_arr != null)
        {
            for (int i = 0; i < padstacks_arr.length(); ++i)
            {
                JSONObject ps_obj = padstacks_arr.getJSONObject(i);
                Padstack padstack = read_padstack(ps_obj, board.library.padstacks, layer_structure, layer_no_by_name, len);
                if (padstack != null)
                {
                    padstacks_by_name.put(padstack.name, padstack);
                }
            }
        }

        // Vias (named via definitions referencing a padstack).
        Map<String, ViaRule> via_rules_by_name = new HashMap<>();
        JSONArray vias_arr = root.optJSONArray("vias");
        if (vias_arr != null)
        {
            // Collected in order and registered as the board's via padstacks. Without this
            // BoardLibrary.via_padstacks stays null, and SessionFile.write_library iterates
            // via_padstack_count() -- so a written .ses or .rte carried an EMPTY (library_out),
            // while its (via ...) entries still referenced padstacks by name. Altium's route
            // import then failed with "List index out of bounds (0)", dropped every trace, and
            // left a handful of zero-width net-less objects at the coordinate origin. Only the
            // DSN reader (specctra/Library) was calling this.
            java.util.LinkedHashMap<String, Padstack> via_padstacks = new java.util.LinkedHashMap<>();
            for (int i = 0; i < vias_arr.length(); ++i)
            {
                JSONObject via_obj = vias_arr.getJSONObject(i);
                read_via(via_obj, board, padstacks_by_name, via_rules_by_name);
                String via_padstack_name = via_obj.optString("padstack", null);
                Padstack via_padstack = via_padstack_name == null ? null : padstacks_by_name.get(via_padstack_name);
                if (via_padstack != null)
                {
                    via_padstacks.put(via_padstack.name, via_padstack);
                }
            }
            if (!via_padstacks.isEmpty())
            {
                board.library.set_via_padstacks(via_padstacks.values().toArray(new Padstack[0]));
                FRLogger.info("Registered " + via_padstacks.size()
                        + " via padstack(s) in the board library: " + via_padstacks.keySet());
            }
            else
            {
                FRLogger.warn("FrpcbFile: no via padstack could be registered; a written .ses or"
                        + " .rte will have an empty library_out and Altium will refuse it");
            }
        }

        // Nets (create empty Net entries first so net_classes/components can reference them).
        JSONArray nets_arr = root.optJSONArray("nets");
        if (nets_arr != null)
        {
            for (int i = 0; i < nets_arr.length(); ++i)
            {
                JSONObject net_obj = nets_arr.getJSONObject(i);
                String net_name = net_obj.optString("name", null);
                if (net_name == null)
                {
                    FRLogger.warn("FrpcbFile.read: skipping a net with no 'name'");
                    continue;
                }
                if (board.rules.nets.get(net_name, 1) == null)
                {
                    board.rules.nets.add(net_name, 1, false);
                }
            }
        }

        // Net classes (must exist before per-net rule overrides and before components,
        // since a pin's default clearance class comes from its net's class).
        Map<String, NetClass> net_classes_by_name = new HashMap<>();
        JSONArray net_classes_arr = root.optJSONArray("net_classes");
        if (net_classes_arr != null)
        {
            for (int i = 0; i < net_classes_arr.length(); ++i)
            {
                JSONObject nc_obj = net_classes_arr.getJSONObject(i);
                NetClass net_class = read_net_class(nc_obj, board, layer_no_by_name, via_rules_by_name, len);
                if (net_class != null)
                {
                    net_classes_by_name.put(net_class.get_name(), net_class);
                }
            }
            assign_net_classes(net_classes_arr, board, net_classes_by_name);
            apply_default_trace_width(net_classes_arr, board, len);
        }

        // Every net class needs a via rule, including the default class, which no entry in the
        // file describes. Network.read_scope does the same blanket pass for DSN. Without it a
        // class can be left with a null via rule, which the Specctra writer dereferences
        // unguarded -- File > Export Specctra Design File died on it.
        ensure_via_rules(board);

        // Clearance matrix: the pairwise class-to-class rules this format exists to carry.
        JSONArray clearance_matrix_arr = root.optJSONArray("clearance_matrix");
        if (clearance_matrix_arr != null)
        {
            read_clearance_matrix(clearance_matrix_arr, board.rules.clearance_matrix, net_classes_by_name, len);
        }

        // An Altium Constraints.xml, when supplied, is the authoritative source of the
        // clearance matrix and overrides whatever the json carried.
        if (p_constraints_file != null)
        {
            AltiumConstraintsFile.Constraints constraints = AltiumConstraintsFile.read(p_constraints_file);
            if (constraints == null)
            {
                FRLogger.error("FrpcbFile.read: '" + p_constraints_file
                        + "' could not be used as a clearance source; refusing to import rather than"
                        + " routing on the json's clearances, which are known to understate"
                        + " high-voltage spacing", null);
                return ReadResult.ERROR;
            }
            apply_altium_constraints(constraints, board, net_classes_by_name, len);
        }

        // Per-net rule overrides (width / clearance), with class-dedup-by-value.
        if (nets_arr != null)
        {
            for (int i = 0; i < nets_arr.length(); ++i)
            {
                read_net_rule(nets_arr.getJSONObject(i), board, len);
            }
        }

        // Components (placement + pins, which attach nets to board items).
        JSONArray components_arr = root.optJSONArray("components");
        if (components_arr != null)
        {
            for (int i = 0; i < components_arr.length(); ++i)
            {
                read_component(components_arr.getJSONObject(i), board, padstacks_by_name, len);
            }
        }

        // Copper pours / planes, as ConductionArea items. These are what let a
        // plane-connected net (GND, power rails) count as routed; without them such
        // nets show up entirely as ratsnest lines. Read before the routing section so
        // normalize_traces below sees the pours too.
        JSONArray pours_arr = root.optJSONArray("pours");
        if (pours_arr != null)
        {
            for (int i = 0; i < pours_arr.length(); ++i)
            {
                read_pour(pours_arr.getJSONObject(i), board, layer_no_by_name, len, i);
            }
        }

        // Pre-existing routed geometry.
        JSONObject routing_obj = root.optJSONObject("routing");
        if (routing_obj != null)
        {
            JSONArray wires_arr = routing_obj.optJSONArray("wires");
            if (wires_arr != null)
            {
                // Vias are inserted BEFORE wires on purpose: read_wire snaps a wire's
                // endpoints onto the centre of any same-net pad or via they already sit
                // inside, and it can only do that for vias that exist by then.
                JSONArray vias_first = routing_obj.optJSONArray("vias");
                if (vias_first != null)
                {
                    for (int i = 0; i < vias_first.length(); ++i)
                    {
                        read_routed_via(vias_first.getJSONObject(i), board, padstacks_by_name, len);
                    }
                }
                int zero_length_wires = 0;
                snapped_wire_ends = 0;
                for (int i = 0; i < wires_arr.length(); ++i)
                {
                    if (!read_wire(wires_arr.getJSONObject(i), board, layer_no_by_name, len))
                    {
                        ++zero_length_wires;
                    }
                }
                if (snapped_wire_ends > 0)
                {
                    FRLogger.info("Snapped " + snapped_wire_ends + " wire end(s) onto the centre of"
                            + " a pad or via they already overlapped, so the connection registers");
                }
                if (zero_length_wires > 0)
                {
                    // One line, not one per wire. These are Altium tracks whose two endpoints
                    // are identical - leftovers at junctions and single-click stubs. They carry
                    // no copper and connect nothing, so dropping them is correct, and warning
                    // about each one just buries the warnings that matter.
                    FRLogger.info("Skipped " + zero_length_wires + " of " + wires_arr.length()
                            + " routed wires whose start and end points are identical"
                            + " (zero-length Altium tracks, which connect nothing)");
                }
            }
            // (routing vias were inserted above, before the wires)
            for (int net_no = 1; net_no <= board.rules.nets.max_net_no(); ++net_no)
            {
                try
                {
                    board.normalize_traces(net_no);
                }
                catch (Exception e)
                {
                    FRLogger.warn("FrpcbFile.read: normalization of net '" + board.rules.nets.get(net_no).name + "' failed");
                }
            }
        }

        p_board_handling.initialize_manual_trace_half_widths();
        return ReadResult.OK;
    }

    // ---- board / keepouts -------------------------------------------------

    private static double[] polygon_list_bounds(JSONArray p_polygons)
    {
        double min_x = Double.POSITIVE_INFINITY, min_y = Double.POSITIVE_INFINITY;
        double max_x = Double.NEGATIVE_INFINITY, max_y = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (int i = 0; i < p_polygons.length(); ++i)
        {
            JSONArray polygon = p_polygons.optJSONArray(i);
            if (polygon == null)
            {
                continue;
            }
            for (int j = 0; j < polygon.length(); ++j)
            {
                JSONArray point = polygon.getJSONArray(j);
                double x = point.getDouble(0);
                double y = point.getDouble(1);
                min_x = Math.min(min_x, x);
                min_y = Math.min(min_y, y);
                max_x = Math.max(max_x, x);
                max_y = Math.max(max_y, y);
                found = true;
            }
        }
        if (!found)
        {
            return null;
        }
        return new double[]{min_x, min_y, max_x, max_y};
    }

    /**
     * Builds an arbitrary (possibly non-convex, possibly concave) polygon shape from a
     * boundary point list, for the board outline and keepout areas - these commonly have
     * notches/cutouts and must not be silently reduced to their convex hull. Mirrors
     * Polygon.transform_to_board in the Specctra DSN parser, which builds the same
     * PolygonShape class for the identical purpose.
     */
    private static PolygonShape polygon_to_polyline_shape(JSONArray p_polygon, LengthConverter p_len)
    {
        int n = p_polygon.length();
        if (n < 3)
        {
            return null;
        }
        IntPoint[] corners = new IntPoint[n];
        for (int i = 0; i < n; ++i)
        {
            JSONArray point = p_polygon.getJSONArray(i);
            corners[i] = p_len.to_board_point(point.getDouble(0), point.getDouble(1));
        }
        return new PolygonShape(corners);
    }

    /**
     * Builds a convex Simplex from a polygon's boundary lines, for padstack shapes only -
     * unlike the board outline/keepout case above, a padstack's per-layer copper shape must
     * be a ConvexShape (see Padstack.shapes), so an arbitrary/concave polygon shape class
     * cannot be used here. The polygon is assumed to be convex and wound counter-clockwise
     * (the Specctra/freerouting convention for the positive side of a directed boundary line
     * to be the shape's interior). A non-convex input silently yields the convex hull's worth
     * of half-planes cut away by Simplex.get_instance's redundant line removal, same as the
     * DSN padstack-shape path (Library.read_padstack_scope) does for a non-convex pad shape.
     */
    private static Simplex polygon_to_simplex(JSONArray p_polygon, LengthConverter p_len)
    {
        int n = p_polygon.length();
        if (n < 3)
        {
            return null;
        }
        IntPoint[] corners = new IntPoint[n];
        for (int i = 0; i < n; ++i)
        {
            JSONArray point = p_polygon.getJSONArray(i);
            corners[i] = p_len.to_board_point(point.getDouble(0), point.getDouble(1));
        }
        Line[] lines = new Line[n];
        for (int i = 0; i < n; ++i)
        {
            IntPoint a = corners[i];
            IntPoint b = corners[(i + 1) % n];
            lines[i] = new Line(a, b);
        }
        return Simplex.get_instance(lines);
    }

    private static void read_keepouts(JSONArray p_keepouts, RoutingBoard p_board, Map<String, Integer> p_layer_no_by_name, LengthConverter p_len)
    {
        for (int i = 0; i < p_keepouts.length(); ++i)
        {
            JSONObject keepout_obj = p_keepouts.getJSONObject(i);
            String type = keepout_obj.optString("type", "keepout");
            JSONArray polygon = keepout_obj.optJSONArray("polygon");
            if (polygon == null)
            {
                FRLogger.warn("FrpcbFile.read_keepouts: keepout #" + i + " has no 'polygon', skipping it");
                continue;
            }
            Area area = polygon_to_polyline_shape(polygon, p_len);
            if (area == null)
            {
                FRLogger.warn("FrpcbFile.read_keepouts: keepout #" + i + " has an unusable polygon, skipping it");
                continue;
            }
            List<Integer> target_layers = new LinkedList<>();
            Object layers_field = keepout_obj.opt("layers");
            if (layers_field instanceof String && "all".equalsIgnoreCase((String) layers_field))
            {
                for (int l = 0; l < p_board.get_layer_count(); ++l)
                {
                    target_layers.add(l);
                }
            }
            else if (layers_field instanceof JSONArray)
            {
                JSONArray layers_arr = (JSONArray) layers_field;
                for (int l = 0; l < layers_arr.length(); ++l)
                {
                    String layer_name = layers_arr.getString(l);
                    Integer layer_no = p_layer_no_by_name.get(layer_name);
                    if (layer_no == null)
                    {
                        FRLogger.warn("FrpcbFile.read_keepouts: keepout #" + i + " references unknown layer '" + layer_name + "', skipping that layer");
                        continue;
                    }
                    target_layers.add(layer_no);
                }
            }
            else
            {
                FRLogger.warn("FrpcbFile.read_keepouts: keepout #" + i + " has no usable 'layers', skipping it");
                continue;
            }
            int clearance_class = p_board.rules.get_default_net_class().default_item_clearance_classes.get(ItemClass.AREA);
            for (int layer_no : target_layers)
            {
                if ("via_keepout".equalsIgnoreCase(type))
                {
                    p_board.insert_via_obstacle(area, layer_no, clearance_class, FixedState.SYSTEM_FIXED);
                }
                else if ("place_keepout".equalsIgnoreCase(type))
                {
                    p_board.insert_component_obstacle(area, layer_no, clearance_class, FixedState.SYSTEM_FIXED);
                }
                else
                {
                    p_board.insert_obstacle(area, layer_no, clearance_class, FixedState.SYSTEM_FIXED);
                }
            }
        }
    }

    // ---- padstacks ---------------------------------------------------------

    private static Padstack read_padstack(JSONObject p_obj, Padstacks p_padstacks, LayerStructure p_layer_structure,
                                          Map<String, Integer> p_layer_no_by_name, LengthConverter p_len)
    {
        String name = p_obj.optString("name", null);
        if (name == null)
        {
            FRLogger.warn("FrpcbFile.read_padstack: a padstack has no 'name', skipping it");
            return null;
        }
        JSONObject shapes_obj = p_obj.optJSONObject("shapes");
        ConvexShape[] shapes = new ConvexShape[p_layer_structure.arr.length];
        if (shapes_obj == null || shapes_obj.isEmpty())
        {
            // Shapeless padstack (Altium mounting hole / NPTH / fiducial). Register it with an
            // all-null shape array so pin references resolve without a shape, mirroring
            // Library.read_padstack_scope's handling of the same case in the DSN parser.
            return p_padstacks.add(name, shapes, true, false);
        }
        for (String layer_name : shapes_obj.keySet())
        {
            Integer layer_no = p_layer_no_by_name.get(layer_name);
            if (layer_no == null)
            {
                FRLogger.warn("FrpcbFile.read_padstack: padstack '" + name + "' references unknown layer '" + layer_name + "', skipping that layer's shape");
                continue;
            }
            JSONObject shape_obj = shapes_obj.getJSONObject(layer_name);
            ConvexShape shape = read_shape(shape_obj, p_len);
            if (shape == null)
            {
                FRLogger.warn("FrpcbFile.read_padstack: padstack '" + name + "' has an unusable shape on layer '" + layer_name + "', skipping that layer's shape");
                continue;
            }
            shapes[layer_no] = shape;
        }
        return p_padstacks.add(name, shapes, true, false);
    }

    private static ConvexShape read_shape(JSONObject p_shape_obj, LengthConverter p_len)
    {
        String type = p_shape_obj.optString("type", null);
        if ("circle".equalsIgnoreCase(type))
        {
            double diameter = p_shape_obj.optDouble("diameter", 0);
            int radius = (int) Math.round(p_len.to_board(diameter / 2));
            if (radius <= 0)
            {
                return null;
            }
            return new Circle(new IntPoint(0, 0), radius);
        }
        else if ("rect".equalsIgnoreCase(type))
        {
            double width = p_shape_obj.optDouble("width", 0);
            double height = p_shape_obj.optDouble("height", 0);
            int half_w = (int) Math.round(p_len.to_board(width / 2));
            int half_h = (int) Math.round(p_len.to_board(height / 2));
            if (half_w <= 0 || half_h <= 0)
            {
                return null;
            }
            return new IntBox(-half_w, -half_h, half_w, half_h);
        }
        else if ("polygon".equalsIgnoreCase(type))
        {
            JSONArray points = p_shape_obj.optJSONArray("points");
            if (points == null)
            {
                return null;
            }
            Simplex simplex = polygon_to_simplex(points, p_len);
            if (simplex == null || simplex.is_empty())
            {
                return null;
            }
            return simplex.simplify();
        }
        FRLogger.warn("FrpcbFile.read_shape: unknown shape type '" + type + "'");
        return null;
    }

    // ---- vias ---------------------------------------------------------------

    private static void read_via(JSONObject p_via_obj, RoutingBoard p_board, Map<String, Padstack> p_padstacks_by_name,
                                 Map<String, ViaRule> p_via_rules_by_name)
    {
        String name = p_via_obj.optString("name", null);
        String padstack_name = p_via_obj.optString("padstack", null);
        if (name == null || padstack_name == null)
        {
            FRLogger.warn("FrpcbFile.read_via: a via entry is missing 'name' or 'padstack', skipping it");
            return;
        }
        Padstack padstack = p_padstacks_by_name.get(padstack_name);
        if (padstack == null)
        {
            FRLogger.warn("FrpcbFile.read_via: via '" + name + "' references unknown padstack '" + padstack_name + "', skipping it");
            return;
        }
        int clearance_class = BoardRules.default_clearance_class();
        String clearance_class_name = p_via_obj.optString("clearance_class", null);
        if (clearance_class_name != null)
        {
            int found = p_board.rules.clearance_matrix.get_no(clearance_class_name);
            if (found >= 0)
            {
                clearance_class = found;
            }
            else
            {
                FRLogger.warn("FrpcbFile.read_via: via '" + name + "' references unknown clearance_class '" + clearance_class_name + "', using default");
            }
        }
        ViaInfo via_info = new ViaInfo(name, padstack, clearance_class, false, p_board.rules);
        p_board.rules.via_infos.add(via_info);
        ViaRule via_rule = new ViaRule(name);
        via_rule.append_via(via_info);
        p_board.rules.via_rules.add(via_rule);
        p_via_rules_by_name.put(name, via_rule);
    }

    // ---- net classes / clearance matrix / per-net rules ---------------------

    private static NetClass read_net_class(JSONObject p_nc_obj, RoutingBoard p_board, Map<String, Integer> p_layer_no_by_name,
                                           Map<String, ViaRule> p_via_rules_by_name, LengthConverter p_len)
    {
        String name = p_nc_obj.optString("name", null);
        if (name == null)
        {
            FRLogger.warn("FrpcbFile.read_net_class: a net class has no 'name', skipping it");
            return null;
        }
        NetClass net_class = p_board.rules.append_net_class(name);

        if (p_nc_obj.has("width"))
        {
            int half_width = (int) Math.round(p_len.to_board(p_nc_obj.getDouble("width") / 2));
            net_class.set_trace_half_width(half_width);
        }
        if (p_nc_obj.has("clearance"))
        {
            add_self_clearance_rule(p_board.rules.clearance_matrix, net_class, p_nc_obj.getDouble("clearance"), p_len);
        }
        if (p_nc_obj.has("via"))
        {
            String via_name = p_nc_obj.getString("via");
            ViaRule via_rule = p_via_rules_by_name.get(via_name);
            if (via_rule != null)
            {
                net_class.set_via_rule(via_rule);
            }
            else
            {
                FRLogger.warn("FrpcbFile.read_net_class: net class '" + name + "' references unknown via '" + via_name + "'");
            }
        }
        if (p_nc_obj.has("max_length") && p_nc_obj.getDouble("max_length") > 0)
        {
            net_class.set_maximum_trace_length(p_len.to_board(p_nc_obj.getDouble("max_length")));
        }
        if (p_nc_obj.has("min_length") && p_nc_obj.getDouble("min_length") > 0)
        {
            net_class.set_minimum_trace_length(p_len.to_board(p_nc_obj.getDouble("min_length")));
        }
        JSONArray active_layers = p_nc_obj.optJSONArray("active_layers");
        if (active_layers != null)
        {
            net_class.set_all_layers_active(false);
            for (int i = 0; i < active_layers.length(); ++i)
            {
                String layer_name = active_layers.getString(i);
                Integer layer_no = p_layer_no_by_name.get(layer_name);
                if (layer_no == null)
                {
                    FRLogger.warn("FrpcbFile.read_net_class: net class '" + name + "' references unknown layer '" + layer_name + "'");
                    continue;
                }
                net_class.set_active_routing_layer(layer_no, true);
            }
        }

        // Class membership is assigned by assign_net_classes, not here: it depends on how
        // many nets every OTHER class claims, so it cannot be decided one class at a time.
        return net_class;
    }

    /**
     * Assigns every net to exactly one class, most specific class winning.
     *
     * <p>freerouting's Net holds a single NetClass, while a net is routinely a member of
     * several Altium classes at once -- on the reference board every one of the 438 nets is
     * in "All Nets" as well as, for two of them, "1300V". Assigning in file order let the
     * broadest class overwrite the narrowest, which left the high-voltage classes with no
     * members and made the whole pairwise clearance matrix inert no matter how correct its
     * values were. Classes are therefore applied largest-first so the smallest class
     * containing a net is applied last and wins.
     */
    private static void assign_net_classes(JSONArray p_net_classes, RoutingBoard p_board,
                                           Map<String, NetClass> p_net_classes_by_name)
    {
        List<JSONObject> by_descending_size = new ArrayList<>();
        for (int i = 0; i < p_net_classes.length(); ++i)
        {
            JSONObject nc_obj = p_net_classes.getJSONObject(i);
            if (nc_obj.optJSONArray("nets") != null && nc_obj.optString("name", null) != null)
            {
                by_descending_size.add(nc_obj);
            }
        }
        by_descending_size.sort(Comparator.comparingInt(
                (JSONObject nc_obj) -> nc_obj.getJSONArray("nets").length()).reversed());

        for (JSONObject nc_obj : by_descending_size)
        {
            NetClass net_class = p_net_classes_by_name.get(nc_obj.getString("name"));
            if (net_class == null)
            {
                continue;
            }
            JSONArray member_nets = nc_obj.getJSONArray("nets");
            for (int i = 0; i < member_nets.length(); ++i)
            {
                for (Net net : p_board.rules.nets.get(member_nets.getString(i)))
                {
                    net.set_class(net_class);
                }
            }
        }
    }

    /**
     * Gives every net class a via rule, falling back to the board default, and creates that
     * default when the file defined no vias at all.
     *
     * <p>read_net_class only sets one when the class carries a resolvable "via", and nothing
     * describes the default net class, so classes could be left with none. A null via rule is
     * not merely cosmetic: it also means the autorouter has no via to place for that class, so
     * it can only route on a single layer.
     */
    private static void ensure_via_rules(RoutingBoard p_board)
    {
        if (p_board.rules.get_default_via_rule() == null)
        {
            p_board.rules.create_default_via_rule(p_board.rules.get_default_net_class(), "default");
        }
        eu.mihosoft.freerouting.rules.ViaRule default_rule = p_board.rules.get_default_via_rule();
        if (default_rule == null)
        {
            FRLogger.warn("FrpcbFile: no via rule could be established; the autorouter will be"
                    + " unable to change layers and Specctra export will be incomplete");
            return;
        }
        int repaired = 0;
        for (int i = 0; i < p_board.rules.net_classes.count(); ++i)
        {
            NetClass curr_class = p_board.rules.net_classes.get(i);
            if (curr_class.get_via_rule() == null)
            {
                curr_class.set_via_rule(default_rule);
                ++repaired;
            }
        }
        if (repaired > 0)
        {
            FRLogger.info("Gave " + repaired + " net class(es) the default via rule '"
                    + default_rule.name + "'");
        }
    }

    /**
     * Pushes the narrowest net-class trace width through BoardRules, not just onto the
     * individual NetClass objects.
     *
     * <p>This is not cosmetic bookkeeping. BoardRules.min_trace_half_width starts at a
     * sentinel 100000 internal units -- 1000 mil -- and is only ever narrowed by
     * set_default_trace_half_width(s). read_net_class calls NetClass.set_trace_half_width
     * directly, which does not touch it, so before this the sentinel survived import.
     *
     * <p>That value is then used as a DRAWING scale. The ratsnest's layer-change marker is a
     * 45-degree line 2x it, and a clearance-violation circle is 5x it, so on a 5000 mil board
     * every air line between two layers drew a 5657 mil diagonal: the board vanished under
     * criss-crossed lines running well past its edges. It also feeds the autorouter's own
     * width decisions, so leaving it at 1000 mil was wrong quite apart from the display.
     */
    private static void apply_default_trace_width(JSONArray p_net_classes, RoutingBoard p_board, LengthConverter p_len)
    {
        int min_half_width = Integer.MAX_VALUE;
        for (int i = 0; i < p_net_classes.length(); ++i)
        {
            JSONObject nc_obj = p_net_classes.getJSONObject(i);
            if (!nc_obj.has("width"))
            {
                continue;
            }
            int half_width = (int) Math.round(p_len.to_board(nc_obj.getDouble("width") / 2));
            if (half_width > 0)
            {
                min_half_width = Math.min(min_half_width, half_width);
            }
        }
        if (min_half_width == Integer.MAX_VALUE)
        {
            FRLogger.warn("FrpcbFile: no net class declares a usable width; leaving the default"
                    + " trace width alone, which will leave ratsnest and violation markers"
                    + " drawn at board scale");
            return;
        }
        p_board.rules.set_default_trace_half_widths(min_half_width);
        FRLogger.info("Default trace half width set to " + min_half_width
                + " internal units (" + p_len.to_file_mil(min_half_width) + " mil)");
    }

    /**
     * Overwrites the clearance matrix with Altium's Constraint Manager values.
     *
     * <p>This does not go through read_clearance_matrix, for two reasons. First, a scope
     * here may be the default clearance class rather than a net class, which that method
     * cannot express. Second, a class only actually uses a dedicated clearance row if
     * something called NetClass.set_trace_clearance_class for it -- read_net_class does
     * that only when the json gave the class a "clearance" -- so a matrix cell written for
     * a class that was left on the default row would have had no effect at all.
     */
    private static void apply_altium_constraints(AltiumConstraintsFile.Constraints p_constraints, RoutingBoard p_board,
                                                 Map<String, NetClass> p_net_classes_by_name, LengthConverter p_len)
    {
        ClearanceMatrix clearance_matrix = p_board.rules.clearance_matrix;

        // The default self-clearance has to be set before any class row is appended:
        // ClearanceMatrix.append_class seeds a new row from the default class's values.
        Double default_self = p_constraints.self_clearance_mil.get(AltiumConstraintsFile.DEFAULT_SCOPE);
        if (default_self != null)
        {
            int value = (int) Math.round(p_len.to_board_mil(default_self));
            int default_no = clearance_matrix.get_no(AltiumConstraintsFile.DEFAULT_SCOPE);
            clearance_matrix.set_value(default_no, default_no, value);
            FRLogger.info("Altium clearance: " + AltiumConstraintsFile.DEFAULT_SCOPE + " <-> "
                    + AltiumConstraintsFile.DEFAULT_SCOPE + " = " + default_self + " mil");
        }

        for (String scope : p_constraints.scope_names())
        {
            ensure_clearance_class(clearance_matrix, scope, p_net_classes_by_name);
        }

        for (Map.Entry<String, Double> entry : p_constraints.self_clearance_mil.entrySet())
        {
            if (AltiumConstraintsFile.DEFAULT_SCOPE.equals(entry.getKey()))
            {
                continue;
            }
            int class_no = ensure_clearance_class(clearance_matrix, entry.getKey(), p_net_classes_by_name);
            if (class_no < 0)
            {
                continue;
            }
            int value = (int) Math.round(p_len.to_board_mil(entry.getValue()));
            clearance_matrix.set_value(class_no, class_no, value);
            FRLogger.info("Altium clearance: " + entry.getKey() + " <-> " + entry.getKey()
                    + " = " + entry.getValue() + " mil");
        }

        for (AltiumConstraintsFile.Pair pair : p_constraints.pairs)
        {
            int first_no = ensure_clearance_class(clearance_matrix, pair.first_scope, p_net_classes_by_name);
            int second_no = ensure_clearance_class(clearance_matrix, pair.second_scope, p_net_classes_by_name);
            if (first_no < 0 || second_no < 0)
            {
                FRLogger.warn("FrpcbFile.apply_altium_constraints: no clearance class for '"
                        + pair.first_scope + "' <-> '" + pair.second_scope + "', skipping it");
                continue;
            }
            int value = (int) Math.round(p_len.to_board_mil(pair.clearance_mil));
            // Both directions, as elsewhere in this file: ClearanceMatrix does not enforce
            // symmetry structurally.
            clearance_matrix.set_value(first_no, second_no, value);
            clearance_matrix.set_value(second_no, first_no, value);
            FRLogger.info("Altium clearance: " + pair.first_scope + " <-> " + pair.second_scope
                    + " = " + pair.clearance_mil + " mil");
        }
    }

    /**
     * Returns the clearance-matrix row for a scope, creating it and binding the matching
     * net class to it when needed. Returns the default row for the default scope.
     */
    private static int ensure_clearance_class(ClearanceMatrix p_clearance_matrix, String p_scope,
                                              Map<String, NetClass> p_net_classes_by_name)
    {
        if (AltiumConstraintsFile.DEFAULT_SCOPE.equals(p_scope))
        {
            return p_clearance_matrix.get_no(AltiumConstraintsFile.DEFAULT_SCOPE);
        }
        int class_no = p_clearance_matrix.get_no(p_scope);
        if (class_no < 0)
        {
            p_clearance_matrix.append_class(p_scope);
            class_no = p_clearance_matrix.get_no(p_scope);
        }
        NetClass net_class = p_net_classes_by_name.get(p_scope);
        if (net_class != null && class_no >= 0)
        {
            // Without both of these the row exists but nothing routes against it: items read
            // their clearance class from default_item_clearance_classes, while the router's
            // trace rules read trace_clearance_class.
            net_class.set_trace_clearance_class(class_no);
            net_class.default_item_clearance_classes.set_all(class_no);
        }
        else if (net_class == null)
        {
            // A scope Altium matrixes by net rather than by class (e.g. CHASSIS). The row
            // is created so the value is not lost, but only a net-scoped rule from the
            // DelphiScript can actually attach a net to it.
            FRLogger.warn("FrpcbFile: Altium clearance scope '" + p_scope
                    + "' is not a net class in this file; its clearance row will not be attached to any net");
        }
        return class_no;
    }

    /**
     * Sets the self-clearance (class <-> class) of p_net_class, creating a dedicated clearance
     * matrix row for it, mirroring Network.add_clearance_rule in the specctra DSN parser.
     */
    private static void add_self_clearance_rule(ClearanceMatrix p_clearance_matrix, NetClass p_net_class, double p_clearance, LengthConverter p_len)
    {
        int clearance = (int) Math.round(p_len.to_board(p_clearance));
        String class_name = p_net_class.get_name();
        int class_no = p_clearance_matrix.get_no(class_name);
        if (class_no < 0)
        {
            p_clearance_matrix.append_class(class_name);
            class_no = p_clearance_matrix.get_no(class_name);
            // Seed the new class against every existing one, as Network.add_clearance_rule
            // does for DSN. append_class copies the default row, which starts at 0 (see
            // ClearanceMatrix.get_default_instance in read), so without this every pair that
            // no explicit rule mentions would end up with ZERO required clearance -- items
            // free to touch -- rather than falling back to the class's own clearance.
            for (int i = 1; i < p_clearance_matrix.get_class_count(); ++i)
            {
                for (int layer = 0; layer < p_clearance_matrix.get_layer_count(); ++layer)
                {
                    int value = Math.max(p_clearance_matrix.value(class_no, i, layer), clearance);
                    p_clearance_matrix.set_value(class_no, i, layer, value);
                    p_clearance_matrix.set_value(i, class_no, layer, value);
                }
            }
        }
        p_net_class.set_trace_clearance_class(class_no);
        // set_trace_clearance_class alone is not enough: every item this importer inserts
        // takes its clearance class from default_item_clearance_classes (see read_wire,
        // read_component, read_routed_via, read_pour), not from trace_clearance_class. Without
        // this, traces and pads stay on the default class and the entire pairwise clearance
        // matrix is inert. Network.add_clearance_rule does the same for the DSN path.
        p_net_class.default_item_clearance_classes.set_all(class_no);
        p_clearance_matrix.set_value(class_no, class_no, clearance);
    }

    /**
     * Populates the pairwise class-to-class clearance matrix -- the feature this format exists
     * to carry, since Altium's Specctra DSN exporter drops it. Both (i,j) and (j,i) are written
     * explicitly: ClearanceMatrix does not enforce symmetry structurally, it is only a
     * convention every existing writer in this codebase (e.g. Network.add_mixed_clearance_rule)
     * follows, so this importer follows it too rather than relying on the matrix to
     * symmetrize itself.
     */
    private static void read_clearance_matrix(JSONArray p_entries, ClearanceMatrix p_clearance_matrix,
                                              Map<String, NetClass> p_net_classes_by_name, LengthConverter p_len)
    {
        for (int i = 0; i < p_entries.length(); ++i)
        {
            JSONObject entry = p_entries.getJSONObject(i);
            JSONArray classes = entry.optJSONArray("classes");
            if (classes == null || classes.length() != 2 || !entry.has("clearance"))
            {
                FRLogger.warn("FrpcbFile.read_clearance_matrix: entry #" + i + " is malformed, skipping it");
                continue;
            }
            String first_name = classes.getString(0);
            String second_name = classes.getString(1);
            NetClass first_class = p_net_classes_by_name.get(first_name);
            NetClass second_class = p_net_classes_by_name.get(second_name);
            if (first_class == null || second_class == null)
            {
                FRLogger.warn("FrpcbFile.read_clearance_matrix: entry #" + i + " references unknown class ('"
                        + first_name + "' or '" + second_name + "'), skipping it");
                continue;
            }
            int first_no = p_clearance_matrix.get_no(first_class.get_name());
            if (first_no < 0)
            {
                p_clearance_matrix.append_class(first_class.get_name());
                first_no = p_clearance_matrix.get_no(first_class.get_name());
            }
            int second_no = p_clearance_matrix.get_no(second_class.get_name());
            if (second_no < 0)
            {
                p_clearance_matrix.append_class(second_class.get_name());
                second_no = p_clearance_matrix.get_no(second_class.get_name());
            }
            int clearance = (int) Math.round(p_len.to_board(entry.getDouble("clearance")));
            p_clearance_matrix.set_value(first_no, second_no, clearance);
            p_clearance_matrix.set_value(second_no, first_no, clearance);
        }
    }

    /**
     * Applies a per-net (rule (width ...)) / (rule (clearance ...)) override, matching
     * Network.read_net_scope's dedup-by-value approach: nets sharing an identical clearance
     * value reuse one NetClass (named deterministically from the value) instead of minting a
     * new anonymous class per net.
     */
    private static void read_net_rule(JSONObject p_net_obj, RoutingBoard p_board, LengthConverter p_len)
    {
        JSONObject rule_obj = p_net_obj.optJSONObject("rule");
        if (rule_obj == null)
        {
            return;
        }
        String net_name = p_net_obj.optString("name", null);
        if (net_name == null)
        {
            return;
        }
        Net board_net = p_board.rules.nets.get(net_name, 1);
        if (board_net == null)
        {
            FRLogger.warn("FrpcbFile.read_net_rule: net '" + net_name + "' not found, skipping its rule");
            return;
        }
        NetClass net_class = null;
        if (rule_obj.has("width"))
        {
            double width = rule_obj.getDouble("width");
            int trace_halfwidth = (int) Math.round(p_len.to_board(width) / 2);
            NetClass default_net_class = p_board.rules.get_default_net_class();
            net_class = p_board.rules.net_classes.find(trace_halfwidth, default_net_class.get_trace_clearance_class(),
                    default_net_class.get_via_rule());
            if (net_class == null)
            {
                net_class = p_board.rules.get_new_net_class(java.util.Locale.ENGLISH);
            }
            net_class.set_trace_half_width(trace_halfwidth);
        }
        if (rule_obj.has("clearance"))
        {
            double clearance_value = rule_obj.getDouble("clearance");
            if (net_class == null)
            {
                // Reuse a net class already created for this clearance value instead of minting
                // a new "classN" for every net that shares the same value.
                String shared_class_name = "clearance_" + clearance_value;
                net_class = p_board.rules.net_classes.get(shared_class_name);
                if (net_class == null)
                {
                    net_class = p_board.rules.get_new_net_class(shared_class_name);
                }
            }
            add_self_clearance_rule(p_board.rules.clearance_matrix, net_class, clearance_value, p_len);
        }
        if (net_class != null)
        {
            board_net.set_class(net_class);
        }
    }

    // ---- components -----------------------------------------------------------

    private static void read_component(JSONObject p_comp_obj, RoutingBoard p_board, Map<String, Padstack> p_padstacks_by_name, LengthConverter p_len)
    {
        String comp_name = p_comp_obj.optString("name", null);
        if (comp_name == null)
        {
            FRLogger.warn("FrpcbFile.read_component: a component has no 'name', skipping it");
            return;
        }
        JSONArray pins_arr = p_comp_obj.optJSONArray("pins");
        boolean is_front = !"bottom".equalsIgnoreCase(p_comp_obj.optString("side", "top"));
        boolean fixed = p_comp_obj.optBoolean("fixed", false);
        double rotation = p_comp_obj.optDouble("rotation", 0);
        IntPoint location = p_len.to_board_point(p_comp_obj.optDouble("x", 0), p_comp_obj.optDouble("y", 0));

        List<eu.mihosoft.freerouting.library.Package.Pin> pin_list = new LinkedList<>();
        if (pins_arr != null)
        {
            for (int i = 0; i < pins_arr.length(); ++i)
            {
                JSONObject pin_obj = pins_arr.getJSONObject(i);
                String pin_name = pin_obj.optString("pin", null);
                String padstack_name = pin_obj.optString("padstack", null);
                if (pin_name == null || padstack_name == null)
                {
                    FRLogger.warn("FrpcbFile.read_component: component '" + comp_name + "' has a pin missing 'pin' or 'padstack', skipping it");
                    continue;
                }
                Padstack padstack = p_padstacks_by_name.get(padstack_name);
                if (padstack == null)
                {
                    FRLogger.warn("FrpcbFile.read_component: component '" + comp_name + "' pin '" + pin_name + "' references unknown padstack '" + padstack_name + "', skipping it");
                    continue;
                }
                IntPoint pin_location = p_len.to_board_point(pin_obj.optDouble("x", 0), pin_obj.optDouble("y", 0));
                // FRPCB pin x/y are ABSOLUTE board coordinates (the exporter writes
                // Pad.X/Pad.Y straight from Altium), but Package.Pin wants the offset
                // relative to the component origin - Component placement adds the origin
                // back on top. Without subtracting it here every pin lands at roughly
                // twice its true coordinate, scattering the board into a starburst.
                Vector rel_coor = new IntVector(pin_location.x - location.x, pin_location.y - location.y);
                pin_list.add(new eu.mihosoft.freerouting.library.Package.Pin(pin_name, padstack.no, rel_coor, 0));
            }
        }
        String package_name = p_comp_obj.optString("package", comp_name);
        eu.mihosoft.freerouting.library.Package.Pin[] pin_arr = pin_list.toArray(new eu.mihosoft.freerouting.library.Package.Pin[0]);
        // Deliberately NOT cached/shared by package_name across components, unlike the
        // Specctra DSN path (where a library "image" scope is parsed once and multiple
        // placements reference the same Package by construction). FRPCB's "package" field
        // is just a documentation label - each component's own "pins" array is the
        // authoritative per-instance pin/padstack/net data, and two components can share a
        // nominal package name while differing in per-pin padstack or net (e.g. Altium
        // fiducials sharing "FIDUCIAL_200X100" but with different per-instance padstacks).
        // Caching by name here previously caused a real crash: the second component sharing
        // a name would look up the FIRST component's cached Package, so
        // board_package.pin_count() and pin-index-based padstack resolution (used inside
        // insert_pin) silently used the wrong component's pin/padstack data - the local
        // shapeless-padstack check below could pass while insert_pin's own lookup still
        // resolved a genuinely shapeless padstack, producing a negative tile_shape_count
        // and crashing calculate_tree_shapes. Keying by comp_name instead guarantees each
        // component gets its own Package built from its own pins.
        String unique_package_name = package_name + "#" + comp_name;
        // Registered and looked up as a FRONT-side package to stay consistent with the
        // front-side placement below (see the note on components.add) - a package
        // registered for the back side but placed as front would not resolve.
        eu.mihosoft.freerouting.library.Package board_package = p_board.library.packages.get(unique_package_name, true);
        if (board_package == null)
        {
            board_package = p_board.library.packages.add(unique_package_name, pin_arr,
                    new eu.mihosoft.freerouting.geometry.planar.Shape[0],
                    new eu.mihosoft.freerouting.library.Package.Keepout[0],
                    new eu.mihosoft.freerouting.library.Package.Keepout[0],
                    new eu.mihosoft.freerouting.library.Package.Keepout[0], true);
        }

        // Rotation is deliberately passed as 0 and the component is always placed as if on
        // the FRONT side, regardless of the file's "rotation"/"side" values: FRPCB pin
        // coordinates are absolute board coordinates with rotation AND back-side mirroring
        // already baked in by Altium, so the relative offsets computed above are already
        // transformed. Components.add applies its own rotation and mirrors back-side
        // components, which would transform them a second time - that double-mirroring put
        // a through-hole connector half off the board and left bottom-side rectangular SMD
        // pads rotated 90 degrees on a live board. The true side still reaches the router
        // through each padstack's per-layer shapes, which is what actually constrains
        // routing; "side"/"rotation" remain in the file for round-trip/documentation.
        eu.mihosoft.freerouting.board.Component new_component = p_board.components.add(comp_name, location, 0,
                true, board_package, board_package, fixed);

        FixedState fixed_state = fixed ? FixedState.SYSTEM_FIXED : FixedState.UNFIXED;
        if (pins_arr != null)
        {
            for (int i = 0; i < pins_arr.length() && i < board_package.pin_count(); ++i)
            {
                JSONObject pin_obj = pins_arr.getJSONObject(i);
                String padstack_name = pin_obj.optString("padstack", null);
                Padstack padstack = padstack_name == null ? null : p_padstacks_by_name.get(padstack_name);
                if (padstack == null)
                {
                    continue; // already warned above while building pin_arr.
                }
                if (padstack.from_layer() > padstack.to_layer())
                {
                    // Shapeless padstack; nothing to insert as a board item, matching
                    // Network.insert_component's handling of the same case for DSN.
                    FRLogger.warn("FrpcbFile.read_component: skipping pin '" + pin_obj.optString("pin") + "' of component '" + comp_name + "' because its padstack '" + padstack_name + "' has no shape.");
                    continue;
                }
                String net_name = pin_obj.optString("net", null);
                int[] net_no_arr;
                NetClass net_class = p_board.rules.get_default_net_class();
                // An empty net is not an unknown net: Altium writes "" for a pad that is
                // simply not connected to anything (fiducials, mechanical pads, unused IC
                // pins), and the exporter passes that through. Treating it as a lookup
                // failure produced a warning per unconnected pad - 106 of them on the
                // reference board - which buried the warnings that do mean something.
                if (net_name != null && net_name.trim().isEmpty())
                {
                    net_name = null;
                }
                if (net_name != null)
                {
                    Net board_net = p_board.rules.nets.get(net_name, 1);
                    if (board_net == null)
                    {
                        FRLogger.warn("FrpcbFile.read_component: pin '" + pin_obj.optString("pin") + "' of component '" + comp_name + "' references unknown net '" + net_name + "'");
                        net_no_arr = new int[0];
                    }
                    else
                    {
                        net_no_arr = new int[]{board_net.net_number};
                        net_class = board_net.get_class();
                    }
                }
                else
                {
                    net_no_arr = new int[0];
                }
                int clearance_class = padstack.from_layer() == padstack.to_layer()
                        ? net_class.default_item_clearance_classes.get(ItemClass.SMD)
                        : net_class.default_item_clearance_classes.get(ItemClass.PIN);
                p_board.insert_pin(new_component.no, i, net_no_arr, clearance_class, fixed_state);
            }
        }
    }

    // ---- routing (pre-existing traces / vias) ----------------------------------

    /**
     * Reads one copper pour / plane as a ConductionArea. A pour is copper belonging to a
     * net that already covers an area, so a trace of that same net reaching the pour is
     * connected - this is what keeps plane-connected nets (GND, power rails) from showing
     * up as unrouted ratsnest lines. Inserted as a non-obstacle area for its own net
     * (BasicBoard.insert_conduction_area's p_is_obstacle = false), so the router may route
     * that net into it while foreign nets still have to keep clearance.
     */
    private static void read_pour(JSONObject p_pour_obj, RoutingBoard p_board,
                                  Map<String, Integer> p_layer_no_by_name, LengthConverter p_len, int p_index)
    {
        String net_name = p_pour_obj.optString("net", null);
        String layer_name = p_pour_obj.optString("layer", null);
        JSONArray polygon = p_pour_obj.optJSONArray("polygon");
        if (net_name == null || layer_name == null || polygon == null)
        {
            FRLogger.warn("FrpcbFile.read_pour: pour #" + p_index + " is missing 'net', 'layer' or 'polygon', skipping it");
            return;
        }
        Integer layer_no = p_layer_no_by_name.get(layer_name);
        if (layer_no == null)
        {
            FRLogger.warn("FrpcbFile.read_pour: pour #" + p_index + " on net '" + net_name
                    + "' references unknown layer '" + layer_name + "', skipping it");
            return;
        }
        Net board_net = p_board.rules.nets.get(net_name, 1);
        if (board_net == null)
        {
            FRLogger.warn("FrpcbFile.read_pour: pour #" + p_index + " references unknown net '" + net_name + "', skipping it");
            return;
        }
        PolygonShape shape = polygon_to_polyline_shape(polygon, p_len);
        if (shape == null || shape.is_empty())
        {
            FRLogger.warn("FrpcbFile.read_pour: pour #" + p_index + " on net '" + net_name
                    + "' has an unusable polygon, skipping it");
            return;
        }
        int clearance_class = board_net.get_class().default_item_clearance_classes.get(ItemClass.AREA);
        p_board.insert_conduction_area(shape, layer_no, new int[]{board_net.net_number},
                clearance_class, false, FixedState.SYSTEM_FIXED);
    }


    /** Counts the wire ends moved onto a pad centre, for one summary line per import. */
    private static int snapped_wire_ends = 0;

    /**
     * Returns the centre of a same-net pin or via on p_layer whose shape contains p_corner, or
     * p_corner unchanged when there is none.
     *
     * <p>Deliberately conservative: it only moves an endpoint that is ALREADY inside the pad,
     * so a wire is never dragged onto something it did not touch. That is exactly the case
     * Altium produces and freerouting's exact-coincidence contact test rejects.
     */
    private static IntPoint snap_to_pad_center(IntPoint p_corner, int p_layer, Net p_net, RoutingBoard p_board)
    {
        if (p_corner == null || p_net == null)
        {
            return p_corner;
        }
        try
        {
            eu.mihosoft.freerouting.board.ItemSelectionFilter filter =
                    new eu.mihosoft.freerouting.board.ItemSelectionFilter(
                            new eu.mihosoft.freerouting.board.ItemSelectionFilter.SelectableChoices[]{
                                    eu.mihosoft.freerouting.board.ItemSelectionFilter.SelectableChoices.PINS,
                                    eu.mihosoft.freerouting.board.ItemSelectionFilter.SelectableChoices.VIAS});
            for (eu.mihosoft.freerouting.board.Item curr_item : p_board.pick_items(p_corner, p_layer, filter))
            {
                if (!(curr_item instanceof eu.mihosoft.freerouting.board.DrillItem)
                        || !curr_item.contains_net(p_net.net_number))
                {
                    continue;
                }
                Point center = ((eu.mihosoft.freerouting.board.DrillItem) curr_item).get_center();
                if (center instanceof IntPoint && !center.equals(p_corner))
                {
                    ++snapped_wire_ends;
                    return (IntPoint) center;
                }
                return p_corner;
            }
        }
        catch (Exception e)
        {
            // A failed lookup must not stop the wire being imported.
        }
        return p_corner;
    }

    /**
     * Inserts one routed wire. Returns false when the wire was a zero-length Altium track and
     * was therefore skipped, so the caller can report those once rather than individually.
     */
    private static boolean read_wire(JSONObject p_wire_obj, RoutingBoard p_board, Map<String, Integer> p_layer_no_by_name, LengthConverter p_len)
    {
        String net_name = p_wire_obj.optString("net", null);
        String layer_name = p_wire_obj.optString("layer", null);
        JSONArray path = p_wire_obj.optJSONArray("path");
        if (net_name == null || layer_name == null || path == null || path.length() < 2)
        {
            FRLogger.warn("FrpcbFile.read_wire: a routed wire is missing 'net', 'layer' or a usable 'path', skipping it");
            return true;
        }
        Integer layer_no = p_layer_no_by_name.get(layer_name);
        if (layer_no == null)
        {
            FRLogger.warn("FrpcbFile.read_wire: routed wire on net '" + net_name + "' references unknown layer '" + layer_name + "', skipping it");
            return true;
        }
        Net board_net = p_board.rules.nets.get(net_name, 1);
        if (board_net == null)
        {
            FRLogger.warn("FrpcbFile.read_wire: routed wire references unknown net '" + net_name + "', skipping it");
            return true;
        }
        double width = p_wire_obj.optDouble("width", 0);
        int half_width = (int) Math.round(p_len.to_board(width) / 2);
        FixedState fixed = parse_fixed_state(p_wire_obj.optString("fixed", "unfixed"));
        IntPoint[] corners = new IntPoint[path.length()];
        for (int i = 0; i < corners.length; ++i)
        {
            JSONArray point = path.getJSONArray(i);
            corners[i] = p_len.to_board_point(point.getDouble(0), point.getDouble(1));
        }
        // Snap the two ends onto the centre of any same-net pad or via they already sit
        // inside. Trace.get_normal_contacts requires a trace endpoint to EQUAL the centre of
        // a pin or via, or the endpoint of another trace, before it counts as connected -
        // overlapping the pad is not enough. Altium's connectivity is geometric, so its
        // routes legitimately stop short of, or run past, the pad centre, and every one of
        // those arrived here as a phantom incomplete connection.
        corners[0] = snap_to_pad_center(corners[0], layer_no, board_net, p_board);
        corners[corners.length - 1] =
                snap_to_pad_center(corners[corners.length - 1], layer_no, board_net, p_board);

        Polygon polygon = new Polygon(corners);
        if (polygon.corner_array().length < 2)
        {
            // A zero-length track: both endpoints are the same point. Counted by the caller
            // and reported once, not warned about individually.
            return false;
        }
        Polyline trace_polyline = new Polyline(polygon);
        int clearance_class = board_net.get_class().default_item_clearance_classes.get(ItemClass.TRACE);
        p_board.insert_trace_without_cleaning(trace_polyline, layer_no, half_width, new int[]{board_net.net_number}, clearance_class, fixed);
        return true;
    }

    private static void read_routed_via(JSONObject p_via_obj, RoutingBoard p_board, Map<String, Padstack> p_padstacks_by_name, LengthConverter p_len)
    {
        String net_name = p_via_obj.optString("net", null);
        String padstack_name = p_via_obj.optString("padstack", null);
        if (net_name == null || padstack_name == null)
        {
            FRLogger.warn("FrpcbFile.read_routed_via: a routed via is missing 'net' or 'padstack', skipping it");
            return;
        }
        Padstack padstack = p_padstacks_by_name.get(padstack_name);
        if (padstack == null)
        {
            FRLogger.warn("FrpcbFile.read_routed_via: routed via on net '" + net_name + "' references unknown padstack '" + padstack_name + "', skipping it");
            return;
        }
        Net board_net = p_board.rules.nets.get(net_name, 1);
        if (board_net == null)
        {
            FRLogger.warn("FrpcbFile.read_routed_via: routed via references unknown net '" + net_name + "', skipping it");
            return;
        }
        FixedState fixed = parse_fixed_state(p_via_obj.optString("fixed", "unfixed"));
        int clearance_class = board_net.get_class().default_item_clearance_classes.get(ItemClass.VIA);
        Point center = p_len.to_board_point(p_via_obj.optDouble("x", 0), p_via_obj.optDouble("y", 0));
        p_board.insert_via(padstack, center, new int[]{board_net.net_number}, clearance_class, fixed, false);
    }

    /**
     * Overrides the fixed state of every piece of routing the file carries; null leaves each
     * wire and via with whatever the file declared.
     *
     * <p>This is how -rm finish and -rm reroute are implemented. Reinterpreting the state at
     * import time rather than at export time matters practically: producing the export needs a
     * live Altium session driven by hand, so one exported file has to serve every mode.
     */
    public static FixedState imported_routing_fixed_state = null;

    private static FixedState parse_fixed_state(String p_value)
    {
        if (imported_routing_fixed_state != null)
        {
            return imported_routing_fixed_state;
        }
        if ("shove_fixed".equalsIgnoreCase(p_value))
        {
            return FixedState.SHOVE_FIXED;
        }
        if ("user_fixed".equalsIgnoreCase(p_value))
        {
            return FixedState.USER_FIXED;
        }
        if ("system_fixed".equalsIgnoreCase(p_value))
        {
            return FixedState.SYSTEM_FIXED;
        }
        return FixedState.UNFIXED;
    }

    /**
     * Converts lengths and points from the file's declared unit into freerouting's internal
     * board coordinate space, via the same CoordinateTransform.dsn_to_board step the Specctra
     * DSN parser uses, so an FRPCB-imported board lands at the same scale as a DSN-imported one.
     */
    private static final class LengthConverter
    {
        private final CoordinateTransform transform;
        private final double units_per_mil;

        LengthConverter(CoordinateTransform p_transform, double p_units_per_mil)
        {
            transform = p_transform;
            units_per_mil = p_units_per_mil;
        }

        double to_board(double p_value_in_file_unit)
        {
            return transform.dsn_to_board(p_value_in_file_unit * units_per_mil);
        }

        /**
         * For lengths that are natively in mil whatever the file's own unit is -- Altium's
         * Constraints.xml carries an explicit unit suffix per value, so those are converted
         * to mil before they get here.
         */
        double to_board_mil(double p_value_in_mil)
        {
            return transform.dsn_to_board(p_value_in_mil);
        }

        /** Internal board units back to mil, for logging. */
        double to_file_mil(double p_value_in_board_units)
        {
            return Math.round(transform.board_to_dsn(p_value_in_board_units) * 100) / 100.0;
        }

        IntPoint to_board_point(double p_x, double p_y)
        {
            double[] scaled = {p_x * units_per_mil, p_y * units_per_mil};
            return transform.dsn_to_board(scaled).round();
        }
    }
}
