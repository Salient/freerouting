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
 * Panel at the lower border of the board frame: cursor position, the message line, the net
 * under the cursor, and the two fields the autorouter reports progress through.
 *
 * <p>Layout, left to right:
 * <pre>
 *   x= .. y= ..  net under cursor | message (slack) | Pass n | route info | route info
 *   \___________ fixed __________/                   \__________ fixed ______________/
 * </pre>
 * The cursor position and the net readout sit on the left, away from the autorouter's
 * counters, so the two groups can never disturb each other.
 *
 * <p>Two sizing rules, both learned from getting it wrong:
 *
 * <p>Never pin a label's HEIGHT. It used to be hard-coded to 14 pixels, which is less than the
 * ascent plus descent of the default font on a modern desktop, so every field was clipped
 * however wide the window was. Heights come from font metrics.
 *
 * <p>Pin the WIDTH of the short fields only. Giving every field a fixed width truncated the
 * message to "400 incomplete connections to ro..."; giving none of them one let the autorouter's
 * counters resize their fields on every update, shoving the neighbouring text sideways several
 * times a second. So the numeric and short fields are fixed and the message absorbs the
 * remainder. Those fields are also monospaced, so a digit changing width cannot nudge the rest
 * of the line.
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
        // GridBagLayout, not BorderLayout. BorderLayout always hands WEST and EAST their
        // preferred width and gives CENTER only what is left, so once the window got narrow
        // the message was starved to nothing while the fixed-width fields beside it still held
        // their full reservation open - hundreds of pixels of visibly EMPTY space next to
        // clipped text. GridBag shrinks components toward their minimum instead, so the fields
        // give back the room they are not using before the message loses any.
        this.setLayout(new java.awt.GridBagLayout());

        // Cursor position and the net under it, both on the left so they sit still while
        // everything else changes, and so neither ends up beside the autorouter's counters.
        javax.swing.JPanel left_panel = new javax.swing.JPanel();
        left_panel.setLayout(new java.awt.BorderLayout());
        left_panel.setOpaque(false);

        mouse_position = new javax.swing.JLabel();
        mouse_position.setText("x= 0  y= 0");
        mouse_position.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        mouse_position.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 6, 0, 8));
        left_panel.add(mouse_position, java.awt.BorderLayout.WEST);

        // The net under the cursor. Its own field, so the autorouter's progress fields never
        // overwrite it and it never shoves them about.
        net_info = new javax.swing.JLabel();
        net_info.setText("");
        net_info.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        net_info.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
        left_panel.add(net_info, java.awt.BorderLayout.EAST);

        java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
        gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        this.add(left_panel, gbc);

        status_message = new javax.swing.JLabel();
        status_message.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        status_message.setText(resources.getString("status_line"));
        // The only component with weight: all spare width goes here, and it is the last to be
        // squeezed when width runs short.
        gbc.weightx = 1.0;
        this.add(status_message, gbc);
        gbc.weightx = 0.0;

        javax.swing.JPanel right_panel = new javax.swing.JPanel();
        right_panel.setLayout(new javax.swing.BoxLayout(right_panel, javax.swing.BoxLayout.X_AXIS));
        right_panel.setOpaque(false);

        // The autorouter's pass number, kept out of the prose message. It used to be
        // concatenated onto the end of "Batch Autorouter running, press left button to stop",
        // which both pushed the end of that sentence out of view and made the whole label
        // change width every pass.
        pass_message = new javax.swing.JLabel();
        pass_message.setText("");
        pass_message.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        pass_message.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 4));
        right_panel.add(pass_message);

        // The two fields the autorouter writes its counters into; current_layer holds the
        // layer name when nothing is routing.
        add_message = new javax.swing.JLabel();
        add_message.setText(resources.getString("additional_text_field"));
        add_message.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        add_message.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
        right_panel.add(add_message);

        current_layer = new javax.swing.JLabel();
        current_layer.setText(resources.getString("current_layer"));
        current_layer.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        current_layer.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
        right_panel.add(current_layer);

        this.add(right_panel, gbc);

        monospace(mouse_position);
        monospace(pass_message);
        monospace(add_message);
        monospace(current_layer);

        // Sized to the longest text each field actually shows, not rounded up: every pixel
        // reserved here is a pixel the message line loses, and over-reserving truncated it to
        // "Batch Autorouter running, press left button to st...".
        // Widths measured with FontMetrics against the longest string each field actually
        // shows, plus its padding - not estimated. Under-reserving clips the field ("to route:
        // 532, routed..."), over-reserving steals room from the message.
        // Preferred width is what the longest real text needs, so at a comfortable window
        // size the layout is identical to before and a changing digit still cannot nudge its
        // neighbours. Minimum width is deliberately much smaller: it is the floor the field
        // may be compressed to when the window is too narrow for everyone's preference. That
        // pair is the whole fix - pinning minimum == maximum == preferred, as this used to,
        // made the fields incompressible and forced the message to absorb every shortfall.
        flex_width(mouse_position, 175, 70);   // "x= 14432.5  y= 13880.0"      mono 154
        flex_width(pass_message, 70, 32);      // "Pass 12"                     mono  49
        flex_width(add_message, 210, 60);      // "to route: 532, routed: 10, " mono 189
        flex_width(current_layer, 200, 60);    // "current layer: Sig [Horiz]"  mono 182
        // A floor for the message too, so the fields cannot take everything from it either.
        status_message.setMinimumSize(new java.awt.Dimension(
                80, status_message.getPreferredSize().height));
        // net_info is sized on demand by ScreenMessages: it is empty whenever the autorouter
        // is running, which is precisely when the long "press left button to stop" message
        // needs the room, so holding 210 pixels open for it the rest of the time does not fit.
        net_info.setPreferredSize(new java.awt.Dimension(0, net_info.getPreferredSize().height));

        int text_height = status_message.getPreferredSize().height;
        this.setPreferredSize(new java.awt.Dimension(300, text_height + 6));
        this.setMinimumSize(new java.awt.Dimension(200, text_height + 6));
    }

    /**
     * Uses a monospaced font at the label's current size, so text whose digits change does
     * not change width.
     */
    private static void monospace(javax.swing.JLabel p_label)
    {
        p_label.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN,
                p_label.getFont().getSize()));
    }

    /**
     * Gives a label a preferred width and a smaller floor it may be compressed to, leaving
     * its height to the font so text is never clipped vertically.
     *
     * <p>Maximum is pinned to the preferred width so a field never grows past what its text
     * needs and steals room from the message; minimum is what lets it shrink when the window
     * cannot satisfy everyone.
     */
    private static void flex_width(javax.swing.JLabel p_label, int p_preferred, int p_minimum)
    {
        // Height from FONT METRICS, not from getPreferredSize(). A JLabel whose text is empty
        // reports a preferred height of zero, and pass_message is constructed empty - so
        // pinning its maximum height to its preferred height pinned it to ZERO, and BoxLayout
        // honours maximum, meaning the pass-number field was laid out zero pixels tall and
        // never became visible even after set_pass_number gave it text. Deriving the height
        // from the font makes a field's height independent of whether it happens to be empty
        // at construction.
        java.awt.FontMetrics fm = p_label.getFontMetrics(p_label.getFont());
        java.awt.Insets insets = p_label.getInsets();
        int height = Math.max(p_label.getPreferredSize().height,
                fm.getAscent() + fm.getDescent() + insets.top + insets.bottom);
        p_label.setPreferredSize(new java.awt.Dimension(p_preferred, height));
        p_label.setMinimumSize(new java.awt.Dimension(p_minimum, height));
        p_label.setMaximumSize(new java.awt.Dimension(p_preferred, height));
    }

    final javax.swing.JLabel status_message;
    final javax.swing.JLabel add_message;
    final javax.swing.JLabel pass_message;
    final javax.swing.JLabel net_info;
    final javax.swing.JLabel current_layer;
    final javax.swing.JLabel mouse_position;
}
