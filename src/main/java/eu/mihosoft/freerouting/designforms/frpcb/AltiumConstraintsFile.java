package eu.mihosoft.freerouting.designforms.frpcb;

import eu.mihosoft.freerouting.logger.FRLogger;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the pairwise net-class clearance matrix out of Altium's Constraint Manager
 * store, <code>Constraints.xml</code>, which Altium keeps next to the .PcbDoc in the
 * project directory.
 *
 * <p>This exists because the legacy <code>PCB_Rule</code> objects an Altium DelphiScript
 * can iterate do <em>not</em> hold the matrix on a Constraint-Manager project. On the
 * board this was developed against they held only a stale 8/10 mil default matrix, while
 * the real design rules -- up to 200 mil between unclassified copper and a 1300 V net --
 * lived only here. Reading the wrong store understated every high-voltage clearance by
 * between 2.5x and 25x.
 *
 * <p>The relevant shape of the file is:
 * <pre>
 * CCMFromScope
 *   ConstraintObject @Id                 &lt;- the "from" scope (a net class or a net)
 *   CCMToScopes
 *     CCMToScope
 *       ConstraintObject @Id             &lt;- the "to" scope
 *       CCMConstraints
 *         CCMConstraint @Attributes      &lt;- "...|GAP=25mil|..."
 * </pre>
 * Both <code>CCMFromScope</code> and <code>CCMToScope</code> carry a
 * <code>ConstraintObject</code>, so these must be read as <em>direct children</em>; a
 * descendant search from the "from" scope would find every "to" scope's object as well.
 *
 * <p>Scopes are GUIDs, resolved against <code>CNetClass</code> (classes) and
 * <code>FlattenedNetName2ConstraintNetIdEntry</code> (nets).
 *
 * <p>This reader deliberately covers only clearance. It is not a general Constraint
 * Manager parser, and it is not the whole rule picture either: a rule scoped to a single
 * net rather than a class -- on the reference board, CHASSIS to the HV classes at 150 mil
 * -- exists only as a legacy <code>PCB_Rule</code> and has to keep coming from the
 * DelphiScript. Neither store alone is complete.
 */
public class AltiumConstraintsFile
{
    /**
     * Scope name standing for Altium's implicit default/unclassified bucket, stored in the
     * file as the all-zeros GUID. Deliberately equal to the name of freerouting's own
     * default clearance class (see ClearanceMatrix.get_default_instance) so it maps
     * straight across. Note this is NOT Altium's "All Nets" class, which is a real class
     * with a real GUID of its own.
     */
    public static final String DEFAULT_SCOPE = "default";

    private static final String NULL_GUID = "00000000-0000-0000-0000-000000000000";

    private AltiumConstraintsFile()
    {
    }

    /** One class-to-class (or class-to-default) clearance, in mil. */
    public static final class Pair
    {
        public final String first_scope;
        public final String second_scope;
        public final double clearance_mil;

        Pair(String p_first_scope, String p_second_scope, double p_clearance_mil)
        {
            first_scope = p_first_scope;
            second_scope = p_second_scope;
            clearance_mil = p_clearance_mil;
        }
    }

    /** The clearance rules found in a Constraints.xml. All lengths are in mil. */
    public static final class Constraints
    {
        /** Scope name -> its self clearance (the matrix diagonal). */
        public final Map<String, Double> self_clearance_mil = new LinkedHashMap<>();
        /** The off-diagonal cells, each appearing once in canonical order. */
        public final List<Pair> pairs = new ArrayList<>();

        /** Every scope name referenced by either a diagonal or an off-diagonal cell. */
        public Set<String> scope_names()
        {
            Set<String> result = new LinkedHashSet<>(self_clearance_mil.keySet());
            for (Pair pair : pairs)
            {
                result.add(pair.first_scope);
                result.add(pair.second_scope);
            }
            return result;
        }

        public boolean is_empty()
        {
            return self_clearance_mil.isEmpty() && pairs.isEmpty();
        }
    }

    /**
     * Parses p_file. Returns null when it cannot be read or contains no clearance data at
     * all -- the caller is expected to treat that as a hard failure rather than route on a
     * default clearance, since on a high-voltage board the difference is not cosmetic.
     */
    public static Constraints read(File p_file)
    {
        if (p_file == null || !p_file.isFile())
        {
            FRLogger.error("AltiumConstraintsFile.read: '" + p_file + "' does not exist", null);
            return null;
        }

        Document document;
        try
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            try
            {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            }
            catch (Exception e)
            {
                // Not fatal: secure processing is already on, this is belt-and-braces.
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(p_file);
        }
        catch (Exception e)
        {
            FRLogger.error("AltiumConstraintsFile.read: '" + p_file + "' is not parseable XML", e);
            return null;
        }

        Map<String, String> name_by_id = read_scope_names(document);
        Constraints result = new Constraints();
        // Canonical pair key -> the values seen for it, so a disagreement between the
        // LayerType 1 and LayerType 2 copies of a cell (or between the (i,j) and (j,i)
        // directions) is reported rather than silently resolved by iteration order.
        Map<String, List<Double>> values_by_pair = new LinkedHashMap<>();
        Map<String, String[]> scopes_by_pair = new LinkedHashMap<>();
        int unresolved_scopes = 0;
        int gapless_constraints = 0;

        NodeList from_scopes = document.getElementsByTagName("CCMFromScope");
        for (int i = 0; i < from_scopes.getLength(); ++i)
        {
            Element from_scope = (Element) from_scopes.item(i);
            Element from_object = first_child(from_scope, "ConstraintObject");
            String from_name = resolve_scope(from_object, name_by_id);

            Element to_scopes = first_child(from_scope, "CCMToScopes");
            if (to_scopes == null)
            {
                continue;
            }
            for (Element to_scope : children(to_scopes, "CCMToScope"))
            {
                Element to_object = first_child(to_scope, "ConstraintObject");
                String to_name = resolve_scope(to_object, name_by_id);

                Element constraints = first_child(to_scope, "CCMConstraints");
                if (constraints == null)
                {
                    continue;
                }
                for (Element constraint : children(constraints, "CCMConstraint"))
                {
                    String attributes = constraint.getAttribute("Attributes");
                    Double gap_mil = parse_gap_mil(attributes);
                    if (gap_mil == null)
                    {
                        ++gapless_constraints;
                        continue;
                    }
                    if (from_name == null || to_name == null)
                    {
                        ++unresolved_scopes;
                        continue;
                    }
                    // freerouting's clearance matrix is a same-layer clearance per class
                    // pair; anything else would mean something this importer is not
                    // modelling, so say so rather than quietly flattening it.
                    String layer_kind = parse_attribute(attributes, "LAYERKIND");
                    if (layer_kind != null && !layer_kind.equalsIgnoreCase("SameLayer"))
                    {
                        FRLogger.warn("AltiumConstraintsFile: clearance '" + from_name + "' <-> '"
                                + to_name + "' has LAYERKIND=" + layer_kind
                                + ", which this importer treats as a same-layer clearance");
                    }
                    String key = pair_key(from_name, to_name);
                    values_by_pair.computeIfAbsent(key, k -> new ArrayList<>()).add(gap_mil);
                    scopes_by_pair.putIfAbsent(key, new String[]{from_name, to_name});
                }
            }
        }

        for (Map.Entry<String, List<Double>> entry : values_by_pair.entrySet())
        {
            String[] scopes = scopes_by_pair.get(entry.getKey());
            double clearance = resolve_duplicate_values(scopes[0], scopes[1], entry.getValue());
            if (scopes[0].equals(scopes[1]))
            {
                result.self_clearance_mil.put(scopes[0], clearance);
            }
            else
            {
                result.pairs.add(new Pair(scopes[0], scopes[1], clearance));
            }
        }

        if (unresolved_scopes > 0)
        {
            // Seen on the reference board: one orphan ConstraintObject with neither a name
            // nor any gap. Harmless, but worth counting in case it ever is not.
            FRLogger.warn("AltiumConstraintsFile: skipped " + unresolved_scopes
                    + " clearance cell(s) whose scope GUID resolved to no net or net class");
        }
        if (result.is_empty())
        {
            FRLogger.error("AltiumConstraintsFile.read: '" + p_file
                    + "' contains no clearance constraints (" + gapless_constraints
                    + " constraint(s) had no GAP); refusing to treat this as a valid rule source", null);
            return null;
        }

        FRLogger.info("AltiumConstraintsFile: read " + result.self_clearance_mil.size()
                + " self clearance(s) and " + result.pairs.size() + " class-pair clearance(s) from "
                + p_file.getName());
        return result;
    }

    /**
     * Maps every scope GUID to a name: net classes from CNetClass, individual nets from the
     * flattened-net-name table. A net class and a net cannot collide here because Altium
     * gives them distinct GUIDs.
     */
    private static Map<String, String> read_scope_names(Document p_document)
    {
        Map<String, String> result = new LinkedHashMap<>();
        NodeList net_entries = p_document.getElementsByTagName("FlattenedNetName2ConstraintNetIdEntry");
        for (int i = 0; i < net_entries.getLength(); ++i)
        {
            Element entry = (Element) net_entries.item(i);
            String id = entry.getAttribute("ConstraintNetId");
            String name = entry.getAttribute("FlattenedNetName");
            if (!id.isEmpty() && !name.isEmpty())
            {
                result.put(id, name);
            }
        }
        NodeList class_entries = p_document.getElementsByTagName("CNetClass");
        for (int i = 0; i < class_entries.getLength(); ++i)
        {
            Element entry = (Element) class_entries.item(i);
            String id = entry.getAttribute("Id");
            String name = entry.getAttribute("Name");
            if (!id.isEmpty() && !name.isEmpty())
            {
                result.put(id, name);
            }
        }
        return result;
    }

    private static String resolve_scope(Element p_constraint_object, Map<String, String> p_name_by_id)
    {
        if (p_constraint_object == null)
        {
            return null;
        }
        String id = p_constraint_object.getAttribute("Id");
        if (id.isEmpty())
        {
            return null;
        }
        if (NULL_GUID.equals(id))
        {
            return DEFAULT_SCOPE;
        }
        return p_name_by_id.get(id);
    }

    /**
     * Altium stores each cell once per layer type (1 and 2) and once per direction. On the
     * reference board every copy agreed; if they ever disagree the largest is taken, since
     * on a high-voltage board the wider clearance is the safe reading, and the conflict is
     * reported.
     */
    private static double resolve_duplicate_values(String p_first_scope, String p_second_scope, List<Double> p_values)
    {
        double result = p_values.get(0);
        boolean disagreement = false;
        for (double value : p_values)
        {
            if (value != result)
            {
                disagreement = true;
            }
            result = Math.max(result, value);
        }
        if (disagreement)
        {
            FRLogger.warn("AltiumConstraintsFile: clearance '" + p_first_scope + "' <-> '" + p_second_scope
                    + "' is stored with conflicting values " + p_values + "; using the largest (" + result + " mil)");
        }
        return result;
    }

    /** Canonical, order-independent key for an unordered scope pair. */
    private static String pair_key(String p_first_scope, String p_second_scope)
    {
        return p_first_scope.compareTo(p_second_scope) <= 0
                ? p_first_scope + " " + p_second_scope
                : p_second_scope + " " + p_first_scope;
    }

    /** Pulls GAP out of the pipe-delimited Attributes string, converted to mil. */
    static Double parse_gap_mil(String p_attributes)
    {
        String raw = parse_attribute(p_attributes, "GAP");
        return raw == null ? null : parse_length_mil(raw);
    }

    /** One field out of "NAME=value|NAME=value|..."; null when absent. */
    static String parse_attribute(String p_attributes, String p_name)
    {
        if (p_attributes == null)
        {
            return null;
        }
        for (String field : p_attributes.split("\\|"))
        {
            int separator = field.indexOf('=');
            if (separator < 0)
            {
                continue;
            }
            if (field.substring(0, separator).trim().equalsIgnoreCase(p_name))
            {
                return field.substring(separator + 1).trim();
            }
        }
        return null;
    }

    /** Parses "25mil" / "0.5mm" / "0.02in" / a bare number (assumed mil) into mil. */
    static Double parse_length_mil(String p_value)
    {
        if (p_value == null)
        {
            return null;
        }
        String value = p_value.trim().toLowerCase();
        double factor = 1.0;
        if (value.endsWith("mil"))
        {
            value = value.substring(0, value.length() - 3);
        }
        else if (value.endsWith("mm"))
        {
            value = value.substring(0, value.length() - 2);
            factor = 1.0 / 0.0254;
        }
        else if (value.endsWith("in"))
        {
            value = value.substring(0, value.length() - 2);
            factor = 1000.0;
        }
        try
        {
            return Double.parseDouble(value.trim()) * factor;
        }
        catch (NumberFormatException e)
        {
            FRLogger.warn("AltiumConstraintsFile: could not parse the length '" + p_value + "'");
            return null;
        }
    }

    private static Element first_child(Element p_parent, String p_tag)
    {
        if (p_parent == null)
        {
            return null;
        }
        for (Node node = p_parent.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if (node.getNodeType() == Node.ELEMENT_NODE && p_tag.equals(node.getNodeName()))
            {
                return (Element) node;
            }
        }
        return null;
    }

    private static List<Element> children(Element p_parent, String p_tag)
    {
        List<Element> result = new ArrayList<>();
        if (p_parent == null)
        {
            return result;
        }
        for (Node node = p_parent.getFirstChild(); node != null; node = node.getNextSibling())
        {
            if (node.getNodeType() == Node.ELEMENT_NODE && p_tag.equals(node.getNodeName()))
            {
                result.add((Element) node);
            }
        }
        return result;
    }
}
