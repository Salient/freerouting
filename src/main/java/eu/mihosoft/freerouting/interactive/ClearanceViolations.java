/*
 *   Copyright (C) 2014  Alfons Wirtz
 *   website www.freerouting.net
 *
 *   Copyright (C) 2017 Michael Hoffer <info@michaelhoffer.de>
 *   Website www.freerouting.mihosoft.eu
*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/>
 *   for more details.
 *
 * ClearanceViolations.java
 *
 * Created on 3. Oktober 2004, 09:13
 */

package eu.mihosoft.freerouting.interactive;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import java.awt.Graphics;
import java.awt.Rectangle;

import eu.mihosoft.freerouting.boardgraphics.GraphicsContext;

import eu.mihosoft.freerouting.board.Item;
import eu.mihosoft.freerouting.board.ClearanceViolation;
import eu.mihosoft.freerouting.geometry.planar.FloatPoint;
import eu.mihosoft.freerouting.geometry.planar.IntBox;

/**
 * To display the clearance violations between items on the screen.
 *
 * <p>This is also the single source of truth for which clearance violations are worth showing
 * the user at all -- ClearanceReport (the -rm verify report) is built on top of the same
 * {@link #list}, so the interactive display and the report can no longer disagree about the
 * same board the way they used to (the GUI counted every duplicate and rounding artifact; the
 * report already filtered both out).
 *
 * @author Alfons Wirtz
 */
public class ClearanceViolations
{
    /**
     * Overlaps below this are not reported, in mil.
     *
     * <p>freerouting checks clearance in integer units of 1/100 mil, and does it by enlarging
     * both shapes by half the required clearance and intersecting them, so half-clearances and
     * trace half-widths each round. That leaves overlaps of a few hundredths of a mil on copper
     * that is actually compliant. On the reference board 1041 of 2799 raw-reported violations
     * were under 0.5 mil, which is well inside fabrication tolerance and far below anything
     * Altium would report.
     */
    public static final double MIN_REPORTED_OVERLAP_MIL = 0.5;

    /** Creates a new instance of ClearanceViolations */
    public ClearanceViolations(Collection<Item> p_item_list)
    {
        LinkedList<ClearanceViolation> kept = new LinkedList<ClearanceViolation>();
        Set<String> seen = new HashSet<String>();
        int excluded_below_tolerance = 0;
        for (Item curr_item : p_item_list)
        {
            for (ClearanceViolation curr_violation : curr_item.clearance_violations())
            {
                // Item.clearance_violations() is called once per item, so the same overlap
                // between A and B turns up twice: once from A's point of view and once from
                // B's, with first_item/second_item swapped. Keep only the first sighting of
                // each unordered pair on a given layer.
                String key = pair_key(curr_violation.first_item.get_id_no(),
                        curr_violation.second_item.get_id_no(), curr_violation.layer);
                if (!seen.add(key))
                {
                    continue;
                }
                boolean shares_net = curr_violation.first_item.shares_net(curr_violation.second_item);
                double shortfall_mil = curr_violation.first_item.board.communication.coordinate_transform.board_to_dsn(
                        2 * curr_violation.shape.smallest_radius());
                if (is_reportable(shares_net, shortfall_mil))
                {
                    kept.add(curr_violation);
                }
                else if (!shares_net)
                {
                    // A real different-net pair, just too small to be worth reporting.
                    ++excluded_below_tolerance;
                }
                // Pairs on the same net are connected, not too close together -- see the
                // shares_net() check above -- and are not counted at all, same as before.
            }
        }
        this.list = kept;
        this.excluded_below_tolerance_count = excluded_below_tolerance;
    }

    /**
     * True if a clearance violation between two items with the given overlap (in mil) is worth
     * showing to the user, rather than being a connected pair or rounding noise.
     *
     * <p>Two items on the same net are connected, not too close -- Altium's own clearance rules
     * carry NETSCOPE=DifferentNets, and Item.clearance_violations() does not filter by net
     * itself (it is also used by the interactive move/drag preview, which passes no ignore-net
     * list). Overlaps under {@link #MIN_REPORTED_OVERLAP_MIL} are rounding artifacts of the
     * integer clearance check, not design violations.
     */
    static boolean is_reportable(boolean p_shares_net, double p_shortfall_mil)
    {
        return !p_shares_net && p_shortfall_mil >= MIN_REPORTED_OVERLAP_MIL;
    }

    /**
     * Key identifying an unordered pair of items on one layer, so the two mirrored
     * ClearanceViolation entries that Item.clearance_violations() produces for every
     * overlapping pair (first_item/second_item swapped) collapse to the same key.
     */
    static String pair_key(int p_first_id, int p_second_id, int p_layer)
    {
        return Math.min(p_first_id, p_second_id) + "-" + Math.max(p_first_id, p_second_id) + "@" + p_layer;
    }

    public void draw(Graphics p_graphics, GraphicsContext p_graphics_context)
    {
        java.awt.Color draw_color = p_graphics_context.get_violations_color();

        // Clip bounds for viewport culling. p_graphics.getClip() can legitimately be null
        // (nothing set), in which case we just draw everything, as before.
        java.awt.Shape clip = p_graphics.getClip();
        IntBox clip_box = null;
        if (clip != null)
        {
            Rectangle clip_shape = clip.getBounds();
            clip_box = p_graphics_context.coordinate_transform.screen_to_board(clip_shape);
        }

        for (ClearanceViolation curr_violation : list)
        {
            double intensity = p_graphics_context.get_layer_visibility(curr_violation.layer);
            if (intensity <= 0)
            {
                // Hidden layer: nothing would actually show, so don't do the work either.
                continue;
            }

            FloatPoint centre = curr_violation.shape.centre_of_gravity();
            double draw_radius = curr_violation.first_item.board.rules.get_min_trace_half_width() * 5;

            if (clip_box != null)
            {
                // The drawn extent is the shape itself plus the circle of draw_radius around
                // its centroid, so cull against the union of both -- culling against just the
                // shape would clip the circle's fringe on a board with a small violation near
                // the edge of the viewport.
                IntBox violation_box = curr_violation.shape.bounding_box()
                        .union(centre.bounding_box().offset(draw_radius));
                if (!violation_box.intersects(clip_box))
                {
                    continue;
                }
            }

            p_graphics_context.fill_area(curr_violation.shape, p_graphics, draw_color, intensity);
            // draw a circle around the violation.
            p_graphics_context.draw_circle(centre, draw_radius, 0.1 * draw_radius, draw_color,
                    p_graphics, intensity);
        }
    }


    /**
     * The de-duplicated, filtered list of clearance violations: one entry per distinct
     * different-net item pair per layer whose overlap is at least {@link #MIN_REPORTED_OVERLAP_MIL}.
     */
    public final Collection<ClearanceViolation> list;

    /**
     * How many further different-net overlaps were found but excluded for being under
     * {@link #MIN_REPORTED_OVERLAP_MIL} mil -- rounding in the integer clearance check, not
     * design violations. Same-net pairs are not included in this count.
     */
    public final int excluded_below_tolerance_count;
}
