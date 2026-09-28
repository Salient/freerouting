/*
 *  Copyright (C) 2014  Alfons Wirtz
 *   website www.freerouting.net
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
 * BoardPanelStatus.java
 *
 * Created on 16. Februar 2005, 06:10
 */

package eu.mihosoft.freerouting.gui;

/**
 * Panel at the lower border of the board frame containing amongst others the message line
 * and the current layer and cursor position.
 *
 * <p>Sizing note: this panel used to pin itself to 20 pixels tall and every label inside it
 * to exactly 14, with fixed pixel widths besides. 14 pixels is less than the ascent plus
 * descent of the default font on a modern desktop, so the text was clipped however much room
 * the window had. Heights now come from the labels' own font metrics, and widths are hints
 * rather than hard caps.
 *
 * @author Alfons Wirtz
 */
class BoardPanelStatus extends javax.swing.JPanel
{

    /** Creates a new instance of BoardStatusPanel */
    BoardPanelStatus(java.util.Locale p_locale)
    {
        java.util.ResourceBundle resources =
                java.util.ResourceBundle.getBundle("eu.mihosoft.freerouting.gui.BoardPanelStatus", p_locale);
        this.setLayout(new java.awt.BorderLayout());

        javax.swing.JPanel left_message_panel = new javax.swing.JPanel();
        left_message_panel.setLayout(new java.awt.BorderLayout());

        status_message = new javax.swing.JLabel();
        status_message.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        status_message.setText(resources.getString("status_line"));
        left_message_panel.add(status_message, java.awt.BorderLayout.CENTER);

        add_message = new javax.swing.JLabel();
        add_message.setText(resources.getString("additional_text_field"));
        left_message_panel.add(add_message, java.awt.BorderLayout.EAST);

        this.add(left_message_panel, java.awt.BorderLayout.CENTER);

        javax.swing.JPanel right_message_panel = new javax.swing.JPanel();
        right_message_panel.setLayout(new java.awt.BorderLayout());
        right_message_panel.setOpaque(false);

        // The net under the cursor. Given its own field rather than sharing add_message,
        // which the autorouter overwrites with progress counts while it runs.
        net_info = new javax.swing.JLabel();
        net_info.setText("");
        net_info.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        right_message_panel.add(net_info, java.awt.BorderLayout.WEST);

        current_layer = new javax.swing.JLabel();
        current_layer.setText(resources.getString("current_layer"));
        current_layer.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        right_message_panel.add(current_layer, java.awt.BorderLayout.CENTER);

        javax.swing.JPanel cursor_panel = new javax.swing.JPanel();
        cursor_panel.setLayout(new java.awt.BorderLayout());

        javax.swing.JLabel cursor = new javax.swing.JLabel();
        cursor.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        cursor.setText(resources.getString("cursor"));
        cursor_panel.add(cursor, java.awt.BorderLayout.WEST);

        mouse_position = new javax.swing.JLabel();
        mouse_position.setText("(0,0)");
        cursor_panel.add(mouse_position, java.awt.BorderLayout.EAST);

        right_message_panel.add(cursor_panel, java.awt.BorderLayout.EAST);

        this.add(right_message_panel, java.awt.BorderLayout.EAST);

        // No fixed widths anywhere. Reserving pixels for each field is what truncated the
        // status line to "400 incomplete connections to ro..." even on a wide window: the
        // right-hand fields claimed a fixed share whether they needed it or not, and
        // BorderLayout gave the message whatever was left. Sizing to content instead lets
        // the message use the real remaining space, and an empty net field costs nothing.
        // A little horizontal padding keeps the groups from touching.
        add_message.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
        net_info.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
        current_layer.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
        mouse_position.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 8));

        int text_height = status_message.getPreferredSize().height;
        this.setPreferredSize(new java.awt.Dimension(300, text_height + 6));
        this.setMinimumSize(new java.awt.Dimension(200, text_height + 6));
    }

    final javax.swing.JLabel status_message;
    final javax.swing.JLabel add_message;
    final javax.swing.JLabel net_info;
    final javax.swing.JLabel current_layer;
    final javax.swing.JLabel mouse_position;
}
