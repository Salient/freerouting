package eu.mihosoft.freerouting.gui;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.util.Locale;
import javax.swing.JLabel;

import static org.junit.Assert.assertTrue;

/**
 * Layout regression tests for the status bar.
 *
 * <p>The bug these exist for: the short fields used to pin minimum == maximum == preferred
 * width, totalling 655 pixels, and BorderLayout always satisfies WEST and EAST before giving
 * CENTER the remainder. So on a narrow window the message was squeezed to nothing -- or to 45
 * pixels -- while the fields beside it still held their full reservation open, showing visibly
 * empty space next to clipped text. Compressible minimums plus GridBagLayout let the fields
 * give back what they are not using first.
 *
 * <p>These lay the panel out at real pixel widths and inspect the resulting bounds, so they
 * fail on the actual defect rather than on the shape of the code.
 */
public class BoardPanelStatusLayoutTest
{
    private BoardPanelStatus panel;

    @Before
    public void setUp()
    {
        // Constructing Swing components needs font metrics, which needs a font configuration.
        // Skip rather than fail where none is available, so this cannot turn into a spurious
        // CI failure on a bare container.
        try
        {
            panel = new BoardPanelStatus(Locale.ENGLISH);
        }
        catch (Throwable e)
        {
            // JUnit 4.10's assumeNoException takes only the Throwable.
            Assume.assumeNoException(e);
        }
    }

    /** Lays the panel out at the given width and lets every child get its bounds. */
    private void layout_at(int p_width)
    {
        Dimension pref = panel.getPreferredSize();
        panel.setSize(p_width, Math.max(pref.height, 20));
        panel.doLayout();
        for (java.awt.Component c : panel.getComponents())
        {
            if (c instanceof javax.swing.JPanel)
            {
                c.doLayout();
            }
        }
    }

    /** Pixels this label's current text actually needs, including its border insets. */
    private static int needed_width(JLabel p_label)
    {
        FontMetrics fm = p_label.getFontMetrics(p_label.getFont());
        java.awt.Insets in = p_label.getInsets();
        return fm.stringWidth(p_label.getText()) + in.left + in.right;
    }

    /**
     * The regression itself. At a width too narrow for every field's preference, the message
     * must still get a usable share. Under the old fixed-width layout it got 45 pixels at this
     * width, and zero below about 655.
     */
    @Test
    public void message_keeps_room_when_the_window_is_narrow()
    {
        layout_at(700);
        int message = panel.status_message.getWidth();
        assertTrue("status message collapsed to " + message + "px at a 700px window; the short"
                + " fields are not giving back space they are not using", message >= 120);
    }

    /** Even at an unreasonably small width the message must not vanish entirely. */
    @Test
    public void message_never_collapses_to_nothing()
    {
        for (int width : new int[] { 620, 500, 420, 340 })
        {
            layout_at(width);
            assertTrue("status message collapsed to zero width at a " + width + "px window",
                    panel.status_message.getWidth() > 0);
        }
    }

    /**
     * The point of the fix: a field must not sit on space it is not using while the message is
     * clipped. Checks the two widest reservations, which are also the two most often short.
     */
    @Test
    public void fields_do_not_hoard_space_while_the_message_is_clipped()
    {
        panel.current_layer.setText("L1");
        panel.add_message.setText("");
        layout_at(700);

        boolean message_clipped = panel.status_message.getWidth() < needed_width(panel.status_message);
        if (!message_clipped)
        {
            return; // nothing to starve it of; the layout is comfortable.
        }
        for (JLabel field : new JLabel[] { panel.current_layer, panel.add_message })
        {
            int slack = field.getWidth() - needed_width(field);
            assertTrue("'" + field.getText() + "' is holding " + slack + "px of unused width"
                    + " while the status message is clipped", slack <= 24);
        }
    }

    /**
     * The property the old fixed widths existed to protect, which must survive the fix: at a
     * comfortable width every short field still gets its full preferred width, so a changing
     * digit cannot resize a field and shove its neighbours sideways.
     */
    @Test
    public void wide_window_still_gives_every_field_its_full_width()
    {
        layout_at(1600);
        for (JLabel field : new JLabel[] { panel.mouse_position, panel.pass_message,
                panel.add_message, panel.current_layer })
        {
            int want = field.getPreferredSize().width;
            assertTrue("field got " + field.getWidth() + "px but prefers " + want
                    + "px at a 1600px window", field.getWidth() >= want);
        }
    }

    /** Heights come from font metrics; a pinned height clipped every field at any width. */
    @Test
    public void no_field_is_shorter_than_its_font_needs()
    {
        layout_at(1200);
        String[] names = { "status_message", "mouse_position", "pass_message", "add_message",
                "current_layer", "net_info" };
        JLabel[] fields = { panel.status_message, panel.mouse_position, panel.pass_message,
                panel.add_message, panel.current_layer, panel.net_info };
        for (int i = 0; i < fields.length; ++i)
        {
            JLabel field = fields[i];
            FontMetrics fm = field.getFontMetrics(field.getFont());
            int needed = fm.getAscent() + fm.getDescent();
            assertTrue(names[i] + " height " + field.getHeight() + " (width "
                    + field.getWidth() + ") is less than the font's " + needed,
                    field.getHeight() >= needed);
        }
    }
}
