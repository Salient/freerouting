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
 * ScreenMessageFields.java
 *
 * Created on 8. August 2003, 19:10
 */

package eu.mihosoft.freerouting.interactive;

import javax.swing.JLabel;

/**
 * Text fields to display messages on the screen.
 *
 * @author Alfons Wirtz
 */
public class ScreenMessages
{
    
    /** Creates a new instance of ScreenMessageFields */
    public ScreenMessages(JLabel p_status_field, JLabel p_add_field,
            JLabel p_layer_field, JLabel p_mouse_position, java.util.Locale p_locale)
    {
        this(p_status_field, p_add_field, p_layer_field, p_mouse_position, null, null, p_locale);
    }

    /** As above, with fields for the net under the cursor and the autorouter pass number. */
    public ScreenMessages(JLabel p_status_field, JLabel p_add_field,
            JLabel p_layer_field, JLabel p_mouse_position, JLabel p_net_field, JLabel p_pass_field,
            java.util.Locale p_locale)
    {
        net_field = p_net_field;
        pass_field = p_pass_field;
        resources = java.util.ResourceBundle.getBundle("eu.mihosoft.freerouting.interactive.ScreenMessages", p_locale);
        locale = p_locale;
        active_layer_string = resources.getString("current_layer") + " ";
        target_layer_string = resources.getString("target_layer") + " ";
        status_field = p_status_field;
        add_field = p_add_field;
        layer_field = p_layer_field;
        mouse_position = p_mouse_position;
        add_field.setText(empty_string);
        
        this.number_format =  java.text.NumberFormat.getInstance(p_locale);
        this.number_format.setMaximumFractionDigits(4);
    }
    
    /**
     * Shows the autorouter's pass number in its own fixed field; a number {@literal <=} 0
     * clears it. Kept out of the status message so that prose does not change length, and get
     * truncated, on every pass.
     */
    public void set_pass_number(int p_pass_no)
    {
        if (pass_field != null)
        {
            pass_field.setText(p_pass_no > 0
                    ? resources.getString("pass") + " " + p_pass_no
                    : empty_string);
        }
    }

    /**
     * Shows the net under the cursor, or clears the field when p_text is null. Not
     * write-protect aware on purpose: this is a passive readout, not a prompt, so it should
     * keep tracking the cursor even while another operation owns the status line.
     */
    public void set_net_under_cursor(String p_text)
    {
        if (net_field == null)
        {
            return;
        }
        String text = p_text == null ? "" : p_text;
        net_field.setText(text);
        // Collapse to nothing when there is no net, so the field only takes space while it has
        // something to say. Fixed at NET_FIELD_WIDTH otherwise, so a longer or shorter net name
        // cannot shove the message line about.
        int height = net_field.getPreferredSize().height;
        int width = text.isEmpty() ? 0 : NET_FIELD_WIDTH;
        net_field.setPreferredSize(new java.awt.Dimension(width, height));
        net_field.setMinimumSize(new java.awt.Dimension(width, height));
        net_field.setMaximumSize(new java.awt.Dimension(width, height));
        if (net_field.getParent() != null)
        {
            net_field.getParent().revalidate();
        }
    }

    /**
     * Sets the message in the status field.
     */
    public void set_status_message(String p_message)
    {
        if (!this.write_protected)
        {
            status_field.setText(p_message);
        }
    }
    
    /**
     * Sets the displayed layer number on the screen.
     */
    public void set_layer(String p_layer_name)
    {
        if (!this.write_protected)
        {
            layer_field.setText(active_layer_string + p_layer_name);
        }
    }
    
    public void set_interactive_autoroute_info(int p_found, int p_not_found, int p_items_to_go)
    {
        Integer found = p_found;
        Integer failed = p_not_found;
        Integer items_to_go = p_items_to_go;
        add_field.setText(resources.getString("to_route") + " " + items_to_go.toString());
        layer_field.setText(resources.getString("found") + " " + found.toString() + ", "
                + resources.getString("failed") + " " + failed.toString());
    }

    public void set_batch_autoroute_info(int p_items_to_go, int p_routed, int p_ripped, int p_failed)
    {
        Integer ripped = p_ripped;
        Integer routed = p_routed;
        Integer items_to_go = p_items_to_go;
        Integer failed = p_failed;
        add_field.setText( resources.getString("to_route") + " " + items_to_go.toString() + ", "
                + resources.getString("routed") + " " + routed.toString() + ", ");
        layer_field.setText(resources.getString("ripped") + " " + ripped.toString() + ", "
                + resources.getString("failed") + " " + failed.toString());
    }
    
    public void set_batch_fanout_info(int p_pass_no, int p_components_to_go)
    {
        Integer components_to_go = p_components_to_go;
        Integer pass_no = Integer.valueOf(p_pass_no);
        add_field.setText(resources.getString("fanout_pass") + " " +  pass_no.toString() + ": ");
        layer_field.setText(resources.getString("still") + " " 
                + components_to_go.toString() + " " + resources.getString("components"));
    }
    
    public void set_post_route_info( int p_via_count, double p_trace_length)
    {
        Integer via_count = p_via_count;
        add_field.setText(resources.getString("via_count") + " " + via_count.toString());
        layer_field.setText(resources.getString("trace_length") + " " + this.number_format.format(p_trace_length));
    }
    
    /**
     * Sets the displayed layer of the nearest target item
     * in interactive routing.
     */
    public void set_target_layer(String p_layer_name)
    {
        if (!(p_layer_name.equals(prev_target_layer_name) || this.write_protected))
        {
            add_field.setText(target_layer_string +  p_layer_name);
            prev_target_layer_name = p_layer_name;
        }
    }
    
    public void set_mouse_position(eu.mihosoft.freerouting.geometry.planar.FloatPoint p_pos)
    {
        if (p_pos == null || this.mouse_position == null || this.write_protected)
        {
            return;
        }
        // "x= 1234.5  y= 987.6" rather than FloatPoint.to_string's "(1234.5, 987.6)": shorter,
        // and it says which number is which.
        this.mouse_position.setText("x= " + this.number_format.format(p_pos.x)
                + "  y= " + this.number_format.format(p_pos.y));
    }
    
    /**
     * Clears the additional field, which is among others used to
     * display the layer of the nearest target item.
     */
    public void clear_add_field()
    {
        if (!this.write_protected)
        {
            add_field.setText(empty_string);
            prev_target_layer_name = empty_string;
        }
    }
    
    /**
     * Clears the status field and the additional field.
     */
    public void clear()
    {
        if (!this.write_protected)
        {
            status_field.setText(empty_string);
            clear_add_field();
            layer_field.setText(empty_string);
            set_pass_number(0);
        }
    }
    
    /**
     * As long as write_protected is set to true, the set functions in this class will do nothing.
     */
    public void set_write_protected(boolean p_value)
    {
        write_protected = p_value;
    }
    
    private final java.util.ResourceBundle resources;
    private final java.util.Locale locale;
    private final String active_layer_string;
    private final String target_layer_string;
    static private final String empty_string = "            ";
    /** Room for "5V0 SAFETY (63 incomplete)", measured at 191 pixels plus padding. */
    static private final int NET_FIELD_WIDTH = 210;
    
    private JLabel add_field;
    private JLabel status_field;
    private JLabel layer_field;
    private JLabel mouse_position;
    /** Shows the net under the cursor; null when the host did not supply a field for it. */
    private final JLabel net_field;
    /** Shows the autorouter pass number; null when the host supplied no field for it. */
    private final JLabel pass_field;
    private String prev_target_layer_name = empty_string;
    private boolean write_protected = false;
    
    /** The number format for displaying the trace lengtht */
    private final java.text.NumberFormat number_format;
}
