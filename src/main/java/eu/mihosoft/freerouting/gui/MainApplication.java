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
 * MainApplication.java
 *
 * Created on 19. Oktober 2002, 17:58
 *
 */
package eu.mihosoft.freerouting.gui;

import eu.mihosoft.freerouting.board.TestLevel;
import eu.mihosoft.freerouting.constants.Constants;
import eu.mihosoft.freerouting.interactive.InteractiveActionThread;
import eu.mihosoft.freerouting.interactive.ThreadActionListener;
import eu.mihosoft.freerouting.logger.FRLogger;

import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 *
 * Main application for creating frames with new or existing board designs.
 *
 * @author Alfons Wirtz
 */
public class MainApplication extends javax.swing.JFrame
{
    /**
     * Main function of the Application
     * @param args
     */
    public static void main(String[] args)
    {
        FRLogger.traceEntry("MainApplication.main()");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException ex) {
            FRLogger.error(ex.getLocalizedMessage(), ex);
        } catch (InstantiationException ex) {
            FRLogger.error(ex.getLocalizedMessage(), ex);
        } catch (IllegalAccessException ex) {
            FRLogger.error(ex.getLocalizedMessage(), ex);
        } catch (UnsupportedLookAndFeelException ex) {
            FRLogger.error(ex.getLocalizedMessage(), ex);
        }

        FRLogger.info("Freerouting application is started.");

        Thread.setDefaultUncaughtExceptionHandler(new DefaultExceptionHandler());
        StartupOptions startupOptions = StartupOptions.parse(args);

        if (startupOptions.single_design_option)
        {
            java.util.ResourceBundle resources =
                    java.util.ResourceBundle.getBundle("eu.mihosoft.freerouting.gui.MainApplication", startupOptions.current_locale);
            BoardFrame.Option board_option;
            if (startupOptions.session_file_option)
            {
                board_option = BoardFrame.Option.SESSION_FILE;
            }
            else
            {
                board_option = BoardFrame.Option.SINGLE_FRAME;
            }

            FRLogger.info("Opening '"+startupOptions.design_input_filename+"'...");
            DesignFile design_file = DesignFile.get_instance(startupOptions.design_input_filename, false);
            if (design_file == null)
            {
                FRLogger.warn(resources.getString("message_6") + " " +  startupOptions.design_input_filename + " " + resources.getString("message_7"));
                // An unreadable/missing -de file used to fall through to a plain return,
                // which exits 0 -- indistinguishable from success to a scripted caller.
                System.exit(1);
                return;
            }
            String message = resources.getString("loading_design") + " "
                    + startupOptions.design_input_filename;
            WindowMessage welcome_window = WindowMessage.show(message);
            // Must happen before the frame is built: the design is read inside the
            // BoardFrame constructor chain.
            apply_rule_and_mode_options(startupOptions);
            final BoardFrame new_frame =
                    create_board_frame(design_file, null, board_option,
                            startupOptions.test_version_option,
                            startupOptions.current_locale,
                            startupOptions.design_rules_filename);
            welcome_window.dispose();
            if (new_frame == null)
            {
                FRLogger.warn("Couldn't create window frame");
                System.exit(1);
                return;
            }

            if (startupOptions.routing_mode == StartupOptions.RoutingMode.VERIFY)
            {
                int violations = run_clearance_verification(new_frame, startupOptions);
                // 0 clean, 2 violations found, 1 could not check -- so a caller can tell
                // "this board is compliant" from "this board was never checked".
                System.exit(violations < 0 ? 1 : (violations == 0 ? 0 : 2));
                return;
            }

            // -il: 0 is AutorouteSettings' own default, so this is a no-op unless the flag was
            // actually passed.
            new_frame.board_panel.board_handling.settings.autoroute_settings.set_inner_layer_preference(startupOptions.inner_layer_preference);
            // -vr: 0 is AutorouteSettings' own default, so this is a no-op unless the flag was
            // actually passed.
            new_frame.board_panel.board_handling.settings.autoroute_settings.set_via_reduction_effort(startupOptions.via_reduction_effort);
            // -sp: only override start_pass_no if the flag was actually given (sentinel -1
            // means "not specified"). The design file itself may carry its own start_pass_no
            // (e.g. resuming a freerouting-exported .dsn), and absent -sp must not stomp on it.
            if (startupOptions.start_pass_no >= 1)
            {
                new_frame.board_panel.board_handling.settings.autoroute_settings.set_start_pass_no(startupOptions.start_pass_no);
            }
            new_frame.board_panel.board_handling.settings.autoroute_settings.set_stop_pass_no(new_frame.board_panel.board_handling.settings.autoroute_settings.get_start_pass_no() + startupOptions.max_passes - 1);
            if (startupOptions.max_passes < 99999 || startupOptions.max_seconds > 0)
            {
                final InteractiveActionThread thread = new_frame.board_panel.board_handling.start_batch_autorouter();

                if (startupOptions.max_seconds > 0)
                {
                    // Stop the autorouter after the requested wall-clock interval, then let
                    // the autorouterAborted() listener export the routed-so-far board.
                    final int stop_after_ms = startupOptions.max_seconds * 1000;
                    Thread stop_timer = new Thread(new Runnable() {
                        public void run() {
                            try { Thread.sleep(stop_after_ms); } catch (InterruptedException e) { return; }
                            FRLogger.info("Maximum autorouting time of " + startupOptions.max_seconds + "s reached; stopping.");
                            thread.request_stop();
                        }
                    });
                    stop_timer.setDaemon(true);
                    stop_timer.start();
                }

                thread.addListener(new ThreadActionListener() {
                    @Override
                    public void autorouterStarted() {
                    }

                    @Override
                    public void autorouterAborted() {
                        ExportBoardToFile(startupOptions.design_output_filename);
                    }

                    @Override
                    public void autorouterFinished() {
                        ExportBoardToFile(startupOptions.design_output_filename);
                    }

                    private void ExportBoardToFile(String filename) {
                        if ((filename != null)
                                && ((filename.toLowerCase().endsWith(".dsn"))
                                || (filename.toLowerCase().endsWith(".ses"))
                                || (filename.toLowerCase().endsWith(".rte"))
                                || (filename.toLowerCase().endsWith(".scr")))) {

                            FRLogger.info("Saving '" + filename + "'...");
                            try {
                                String filename_only = new File(filename).getName();
                                String design_name = filename_only.substring(0, filename_only.length() - 4);

                                java.io.OutputStream output_stream = new java.io.FileOutputStream(filename);

                                if (filename.toLowerCase().endsWith(".dsn")) {
                                    new_frame.board_panel.board_handling.export_to_dsn_file(output_stream, design_name, false);
                                } else if (filename.toLowerCase().endsWith(".ses")) {
                                    new_frame.board_panel.board_handling.export_specctra_session_file(design_name, output_stream);
                                } else if (filename.toLowerCase().endsWith(".rte")) {
                                    new_frame.board_panel.board_handling.export_specctra_route_file(design_name, output_stream);
                                } else if (filename.toLowerCase().endsWith(".scr")) {
                                    java.io.ByteArrayOutputStream session_output_stream = new ByteArrayOutputStream();
                                    new_frame.board_panel.board_handling.export_specctra_session_file(filename, session_output_stream);
                                    java.io.InputStream input_stream = new ByteArrayInputStream(session_output_stream.toByteArray());
                                    new_frame.board_panel.board_handling.export_eagle_session_file(input_stream, output_stream);
                                }

                                Runtime.getRuntime().exit(0);
                            } catch (Exception e) {
                                // Previously fell through with the GUI still open and no exit
                                // call at all -- a batch caller waiting on this process would
                                // hang forever instead of seeing a failure.
                                FRLogger.error("Couldn't export board to file", e);
                                Runtime.getRuntime().exit(1);
                            }
                        } else {
                            // Same hang hazard as above: an unsupported -do extension (or a
                            // null filename) used to just warn and leave the GUI running.
                            FRLogger.warn("Couldn't export board to '" + filename + "'.");
                            Runtime.getRuntime().exit(1);
                        }
                    }
                });
            }

            new_frame.addWindowListener(new java.awt.event.WindowAdapter()
            {
                @Override
                public void windowClosed(java.awt.event.WindowEvent evt)
                {
                    Runtime.getRuntime().exit(0);
                }
            });
        }
        else
        {
            new MainApplication(startupOptions).setVisible(true);
        }

        FRLogger.traceExit("MainApplication.main()");
    }

    /**
     * Creates new form MainApplication
     * It takes the directory of the board designs as optional argument.
     * @param startupOptions
     */
    public MainApplication(StartupOptions startupOptions)
    {
        this.design_dir_name = startupOptions.getDesignDir();
        this.is_test_version = startupOptions.isTestVersion();
        this.is_webstart = startupOptions.getWebstartOption();
        this.locale = startupOptions.getCurrentLocale();
        this.resources =
                java.util.ResourceBundle.getBundle("eu.mihosoft.freerouting.gui.MainApplication", locale);
        main_panel = new javax.swing.JPanel();
        getContentPane().add(main_panel);
        java.awt.GridBagLayout gridbag = new java.awt.GridBagLayout();
        main_panel.setLayout(gridbag);

        java.awt.GridBagConstraints gridbag_constraints = new java.awt.GridBagConstraints();
        gridbag_constraints.insets = new java.awt.Insets(10, 10, 10, 10);
        gridbag_constraints.gridwidth = java.awt.GridBagConstraints.REMAINDER;

        demonstration_button = new javax.swing.JButton();
        sample_board_button = new javax.swing.JButton();
        open_board_button = new javax.swing.JButton();
        recent_board_button = new javax.swing.JButton();
        restore_defaults_button = new javax.swing.JButton();
        message_field = new javax.swing.JTextField();
        message_field.setText("Neither '-de <design file>' nor '-di <design directory>' are specified.");
        this.window_net_demonstrations = new WindowNetDemonstrations(locale);
        java.awt.Point location = getLocation();
        this.window_net_demonstrations.setLocation((int) location.getX() + 50, (int) location.getY() + 50);
        this.window_net_sample_designs = new WindowNetSampleDesigns(locale);
        this.window_net_sample_designs.setLocation((int) location.getX() + 90, (int) location.getY() + 90);

        setTitle(resources.getString("title") + " " + VERSION_NUMBER_STRING);
        boolean add_buttons = true;

        if (startupOptions.getWebstartOption())
        {

            if (add_buttons)
            {
                demonstration_button.setText(resources.getString("router_demonstrations"));
                demonstration_button.setToolTipText(resources.getString("router_demonstrations_tooltip"));
                demonstration_button.addActionListener((java.awt.event.ActionEvent evt) -> {
                    window_net_demonstrations.setVisible(true);
                });

                gridbag.setConstraints(demonstration_button, gridbag_constraints);
                main_panel.add(demonstration_button, gridbag_constraints);

                sample_board_button.setText(resources.getString("sample_designs"));
                sample_board_button.setToolTipText(resources.getString("sample_designs_tooltip"));
                sample_board_button.addActionListener((java.awt.event.ActionEvent evt) -> {
                    window_net_sample_designs.setVisible(true);
                });

                gridbag.setConstraints(sample_board_button, gridbag_constraints);
                main_panel.add(sample_board_button, gridbag_constraints);
            }
        }

        open_board_button.setText(resources.getString("open_own_design"));
        open_board_button.setToolTipText(resources.getString("open_own_design_tooltip"));
        open_board_button.addActionListener((java.awt.event.ActionEvent evt) -> {
            open_board_design_action(evt);
        });

        gridbag.setConstraints(open_board_button, gridbag_constraints);
        if (add_buttons)
        {
            main_panel.add(open_board_button, gridbag_constraints);
        }

        recent_board_button.setText(resources.getString("open_recent_design"));
        recent_board_button.setToolTipText(resources.getString("open_recent_design_tooltip"));
        recent_board_button.addActionListener((java.awt.event.ActionEvent evt) -> {
            open_recent_design_action(evt);
        });
        gridbag.setConstraints(recent_board_button, gridbag_constraints);
        if (add_buttons)
        {
            main_panel.add(recent_board_button, gridbag_constraints);
        }

        if (startupOptions.getWebstartOption() && add_buttons)
        {
            restore_defaults_button.setText(resources.getString("restore_defaults"));
            restore_defaults_button.setToolTipText(resources.getString("restore_defaults_tooltip"));
            restore_defaults_button.addActionListener((java.awt.event.ActionEvent evt) -> {
                if (is_webstart)
                {
                    restore_defaults_action(evt);
                }
            });

            gridbag.setConstraints(restore_defaults_button, gridbag_constraints);
            main_panel.add(restore_defaults_button, gridbag_constraints);
        }

        message_field.setPreferredSize(new java.awt.Dimension(400, 20));
        message_field.setRequestFocusEnabled(false);
        gridbag.setConstraints(message_field, gridbag_constraints);
        main_panel.add(message_field, gridbag_constraints);

        this.addWindowListener(new WindowStateListener());
        pack();
        setSize(620,300);
    }

    /**
     * Shows the recently opened designs as a popup menu under the button and opens the
     * one picked. Entries whose file has since been deleted are filtered out by
     * RecentFiles, so the menu never offers a dead path.
     */
    private void open_recent_design_action(java.awt.event.ActionEvent evt)
    {
        java.util.List<String> paths = RecentFiles.get_paths();
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        if (paths.isEmpty())
        {
            javax.swing.JMenuItem empty = new javax.swing.JMenuItem(resources.getString("no_recent_designs"));
            empty.setEnabled(false);
            menu.add(empty);
        }
        else
        {
            for (String path : paths)
            {
                final String design_path = path;
                // Show the file name, with the full path as the tooltip - the paths are
                // long enough that a menu of them would be unreadable otherwise.
                javax.swing.JMenuItem item =
                        new javax.swing.JMenuItem(new java.io.File(design_path).getName());
                item.setToolTipText(design_path);
                item.addActionListener((java.awt.event.ActionEvent e) -> {
                    open_design_file(DesignFile.get_recent_instance(design_path));
                });
                menu.add(item);
            }
        }
        menu.show(recent_board_button, 0, recent_board_button.getHeight());
    }

    /** opens a board design from a binary file or a specctra dsn file. */
    private void open_board_design_action(java.awt.event.ActionEvent evt)
    {
        open_design_file(DesignFile.open_dialog(this.design_dir_name));
    }

    /**
     * Loads p_design_file into a new board frame. Shared by the file chooser and the
     * recent-designs menu; a null p_design_file just reports the cancelled/unavailable
     * case in the message field.
     */
    private void open_design_file(DesignFile design_file)
    {
        if (design_file == null)
        {
            message_field.setText(resources.getString("message_3"));
            return;
        }

        FRLogger.info("Opening '"+design_file.get_name()+"'...");

        BoardFrame.Option option;
        if (this.is_webstart)
        {
            option = BoardFrame.Option.WEBSTART;
        }
        else
        {
            option = BoardFrame.Option.FROM_START_MENU;
        }
        String message = resources.getString("loading_design") + " " + design_file.get_name();
        message_field.setText(message);
        WindowMessage welcome_window = WindowMessage.show(message);
        welcome_window.setTitle(message);
        BoardFrame new_frame =
                create_board_frame(design_file, message_field, option, this.is_test_version, this.locale, null);
        welcome_window.dispose();
        if (new_frame == null)
        {
            return;
        }
        message_field.setText(resources.getString("message_4") + " " + design_file.get_name() + " " + resources.getString("message_5"));
        board_frames.add(new_frame);
        new_frame.addWindowListener(new BoardFrameWindowListener(new_frame));
    }

    /** Exit the Application */
    private void exitForm(java.awt.event.WindowEvent evt)
    {
        System.exit(0);
    }

    /** deletes the setting stored by the user if the application is run by Java Web Start */
    private void restore_defaults_action(java.awt.event.ActionEvent evt)
    {
        // webstart is gone, nothing to do
        // TODO maybe add alternative
    }

    /**
     * Creates a new board frame containing the data of the input design file.
     * Returns null, if an error occurred.
     */
    /**
     * Pushes the -dc and -rm options onto the import path. Both land on statics because the
     * design file is read inside the BoardFrame constructor chain, before any caller holds a
     * reference to the resulting board handling.
     */
    private static void apply_rule_and_mode_options(StartupOptions p_options)
    {
        // This is the batch path (-de): never block on a dialog, since a scripted or CI run
        // has nobody to answer it.
        eu.mihosoft.freerouting.interactive.BoardHandling.prompt_for_altium_constraints = false;
        batch_mode = true;
        if (p_options.altium_constraints_filename != null)
        {
            eu.mihosoft.freerouting.interactive.BoardHandling.altium_constraints_file =
                    new java.io.File(p_options.altium_constraints_filename);
            eu.mihosoft.freerouting.interactive.BoardHandling.altium_constraints_required = true;
        }
        if (p_options.routing_mode == StartupOptions.RoutingMode.FINISH)
        {
            // Protect what is already routed. Note this leaves the autorouter unable to
            // repair any pre-existing clearance violation, which is why -rm verify is worth
            // running first.
            eu.mihosoft.freerouting.designforms.frpcb.FrpcbFile.imported_routing_fixed_state =
                    eu.mihosoft.freerouting.board.FixedState.USER_FIXED;
            FRLogger.info("Routing mode 'finish': existing routing is protected; only incomplete"
                    + " connections will be routed.");
        }
        else if (p_options.routing_mode == StartupOptions.RoutingMode.REROUTE)
        {
            eu.mihosoft.freerouting.designforms.frpcb.FrpcbFile.imported_routing_fixed_state =
                    eu.mihosoft.freerouting.board.FixedState.UNFIXED;
            FRLogger.info("Routing mode 'reroute': existing routing may be ripped up and rerouted.");
        }
        else
        {
            // verify: report the board exactly as the file describes it. The fixed state has
            // no bearing on clearance anyway.
            FRLogger.info("Routing mode 'verify': clearances will be checked, nothing routed.");
        }
    }

    /**
     * Logs how many connections are still incomplete, and which nets account for most of
     * them. These are the diagonal "air lines" drawn over the board, and the count is the
     * single most useful number for telling a genuinely unrouted board from one whose
     * existing copper failed to import as connected.
     */
    private static void report_incomplete_connections(BoardFrame p_frame)
    {
        try
        {
            eu.mihosoft.freerouting.interactive.BoardHandling handling = p_frame.board_panel.board_handling;
            eu.mihosoft.freerouting.interactive.RatsNest ratsnest = handling.get_ratsnest();
            eu.mihosoft.freerouting.board.BasicBoard board = handling.get_routing_board();
            int total = ratsnest.incomplete_count();
            FRLogger.info("Incomplete connections (ratsnest air lines): " + total);
            if (total == 0 || board == null)
            {
                return;
            }
            java.util.List<String> worst = new java.util.ArrayList<>();
            for (int net_no = 1; net_no <= board.rules.nets.max_net_no(); ++net_no)
            {
                int count = ratsnest.incomplete_count(net_no);
                if (count > 0)
                {
                    eu.mihosoft.freerouting.rules.Net net = board.rules.nets.get(net_no);
                    worst.add(count + "\t" + (net == null ? ("net " + net_no) : net.name));
                }
            }
            worst.sort(java.util.Collections.reverseOrder(
                    java.util.Comparator.comparingInt(s -> Integer.parseInt(s.split("\t")[0]))));
            FRLogger.info("Nets with incomplete connections: " + worst.size()
                    + "; worst " + Math.min(10, worst.size()) + ":");
            for (int i = 0; i < Math.min(10, worst.size()); ++i)
            {
                FRLogger.info("  " + worst.get(i).replace('\t', ' ') + " incomplete");
            }
            dump_airlines(handling, board);
        }
        catch (Exception e)
        {
            FRLogger.warn("Could not count incomplete connections: " + e);
        }
    }

    /**
     * Writes every ratsnest air line to logs/airlines.txt: net, both endpoints in board
     * units, and the item types at each end. The air lines are the diagonal overlay on the
     * board, and this is what makes their geometry inspectable rather than guessable.
     */
    private static void dump_airlines(eu.mihosoft.freerouting.interactive.BoardHandling p_handling,
                                      eu.mihosoft.freerouting.board.BasicBoard p_board)
    {
        java.io.File out = new java.io.File("logs/airlines.txt");
        try
        {
            if (out.getParentFile() != null)
            {
                out.getParentFile().mkdirs();
            }
            java.io.PrintWriter writer = new java.io.PrintWriter(out, "UTF-8");
            try
            {
                writer.println("# net\tfrom_x\tfrom_y\tto_x\tto_y\tlength\tfrom_item\tto_item");
                eu.mihosoft.freerouting.designforms.specctra.CoordinateTransform transform =
                        p_board.communication.coordinate_transform;
                for (eu.mihosoft.freerouting.interactive.RatsNest.AirLine line
                        : p_handling.get_ratsnest().get_airlines())
                {
                    double[] from = transform.board_to_dsn(line.from_corner);
                    double[] to = transform.board_to_dsn(line.to_corner);
                    double length = Math.hypot(to[0] - from[0], to[1] - from[1]);
                    writer.println((line.net == null ? "?" : line.net.name)
                            + "\t" + Math.round(from[0] * 100) / 100.0
                            + "\t" + Math.round(from[1] * 100) / 100.0
                            + "\t" + Math.round(to[0] * 100) / 100.0
                            + "\t" + Math.round(to[1] * 100) / 100.0
                            + "\t" + Math.round(length * 100) / 100.0
                            + "\t" + (line.from_item == null ? "?" : line.from_item.getClass().getSimpleName())
                            + "\t" + (line.to_item == null ? "?" : line.to_item.getClass().getSimpleName()));
                }
            }
            finally
            {
                writer.close();
            }
            FRLogger.info("Wrote the ratsnest air lines to " + out.getPath());
        }
        catch (Exception e)
        {
            FRLogger.warn("Could not write the air line dump: " + e);
        }
    }

    /**
     * Writes the clearance report for -rm verify. Returns the violation count, or a negative
     * number when the check could not be run at all.
     */
    private static int run_clearance_verification(BoardFrame p_frame, StartupOptions p_options)
    {
        eu.mihosoft.freerouting.board.BasicBoard board =
                p_frame.board_panel.board_handling.get_routing_board();
        if (board == null)
        {
            FRLogger.error("Clearance verification: the board could not be read.", null);
            return -1;
        }
        java.io.PrintWriter writer = null;
        try
        {
            if (p_options.design_output_filename != null)
            {
                FRLogger.info("Writing the clearance report to '" + p_options.design_output_filename + "'...");
                writer = new java.io.PrintWriter(p_options.design_output_filename, "UTF-8");
            }
            else
            {
                writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(System.out));
            }
            report_incomplete_connections(p_frame);
            return eu.mihosoft.freerouting.interactive.ClearanceReport.write(board, writer);
        }
        catch (Exception e)
        {
            FRLogger.error("Clearance verification failed.", e);
            return -1;
        }
        finally
        {
            if (writer != null)
            {
                writer.close();
            }
        }
    }

    static private BoardFrame create_board_frame(DesignFile p_design_file, javax.swing.JTextField p_message_field,
            BoardFrame.Option p_option, boolean p_is_test_version, java.util.Locale p_locale, String p_design_rules_file)
    {
        java.util.ResourceBundle resources =
                java.util.ResourceBundle.getBundle("eu.mihosoft.freerouting.gui.MainApplication", p_locale);

        java.io.InputStream input_stream = p_design_file.get_input_stream();
        if (input_stream == null)
        {
            if (p_message_field != null)
            {
                p_message_field.setText(resources.getString("message_8") + " " + p_design_file.get_name());
            }
            return null;
        }

        TestLevel test_level;
        if (p_is_test_version)
        {
            test_level = DEBUG_LEVEL;
        }
        else
        {
            test_level = TestLevel.RELEASE_VERSION;
        }
        BoardFrame new_frame = new BoardFrame(p_design_file, p_option, test_level, p_locale, !p_is_test_version);
        boolean read_ok = new_frame.read(input_stream, p_design_file.is_created_from_text_file(), p_message_field);
        if (!read_ok)
        {
            return null;
        }
        new_frame.menubar.add_design_dependent_items();
        if (p_design_file.is_created_from_text_file())
        {
            // Read the file  with the saved rules, if it is existing.

            String file_name = p_design_file.get_name();
            String[] name_parts = file_name.split("\\.");

            String design_name = name_parts[0];

            String parent_folder_name = null;
            String rules_file_name = null;
            String confirm_import_rules_message = null;
            if (p_design_rules_file == null) {
                parent_folder_name = p_design_file.get_parent();
                rules_file_name = design_name + ".rules";
                // A batch run has nobody to answer this, and read_rules_file treats a null
                // message as "go ahead", so leaving it null imports the stored rules exactly
                // as clicking Yes would. Prompting here did not make a batch run slow, it
                // made it HANG FOREVER: the dialog waits on the event thread before the
                // autorouter has started, so -mt never fires (its timer only stops the
                // autorouter thread) and the process writes no output at all. Under a virtual
                // display there is not even anyone who could click it.
                if (batch_mode)
                {
                    FRLogger.info("Importing stored rules from '" + rules_file_name
                            + "' without prompting, because this is a batch run."
                            + " Pass -dr to use a different rules file.");
                }
                else
                {
                    confirm_import_rules_message = resources.getString("confirm_import_rules");
                }
            } else {
                rules_file_name = p_design_rules_file;
            }

            DesignFile.read_rules_file(design_name, parent_folder_name, rules_file_name,
                    new_frame.board_panel.board_handling, p_option == BoardFrame.Option.WEBSTART,
                    confirm_import_rules_message);
            new_frame.refresh_windows();
        }
        return new_frame;
    }
    /**
     * True once a batch run (-de) has been recognised, so nothing on the open path may
     * block on a modal dialog. See apply_rule_and_mode_options and create_board_frame.
     */
    private static boolean batch_mode = false;

    private final java.util.ResourceBundle resources;
    private final javax.swing.JButton demonstration_button;
    private final javax.swing.JButton sample_board_button;
    private final javax.swing.JButton open_board_button;
    private final javax.swing.JButton recent_board_button;
    private final javax.swing.JButton restore_defaults_button;
    private final javax.swing.JTextField message_field;
    private final javax.swing.JPanel main_panel;
    /**
     * A Frame with routing demonstrations in the net.
     */
    private final WindowNetSamples window_net_demonstrations;
    /**
     * A Frame with sample board designs in the net.
     */
    private final WindowNetSamples window_net_sample_designs;
    /** The list of open board frames */
    private final java.util.Collection<BoardFrame> board_frames 
            = new java.util.LinkedList<>();
    private String design_dir_name = null;
    private final boolean is_test_version;
    private final boolean is_webstart;
    private final java.util.Locale locale;
    private static final TestLevel DEBUG_LEVEL = TestLevel.CRITICAL_DEBUGGING_OUTPUT;

    private class BoardFrameWindowListener extends java.awt.event.WindowAdapter
    {

        public BoardFrameWindowListener(BoardFrame p_board_frame)
        {
            this.board_frame = p_board_frame;
        }

        @Override
        public void windowClosed(java.awt.event.WindowEvent evt)
        {
            if (board_frame != null)
            {
                // remove this board_frame from the list of board frames
                board_frame.dispose();
                board_frames.remove(board_frame);
                board_frame = null;
            }
        }
        private BoardFrame board_frame;
    }

    private class WindowStateListener extends java.awt.event.WindowAdapter
    {

        @Override
        public void windowClosing(java.awt.event.WindowEvent evt)
        {
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            boolean exit_program = true;
            if (!is_test_version && board_frames.size() > 0)
            {
                int option = javax.swing.JOptionPane.showConfirmDialog(null,
                        resources.getString("confirm_cancel"),
                        null, javax.swing.JOptionPane.YES_NO_OPTION);
                if (option == javax.swing.JOptionPane.NO_OPTION)
                {
                    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
                    exit_program = false;
                }
            }
            if (exit_program)
            {
                exitForm(evt);
            }
        }

        @Override
        public void windowIconified(java.awt.event.WindowEvent evt)
        {
            window_net_sample_designs.parent_iconified();
        }

        @Override
        public void windowDeiconified(java.awt.event.WindowEvent evt)
        {
            window_net_sample_designs.parent_deiconified();
        }
    }
    static final String WEB_FILE_BASE_NAME = "http://www.freerouting.mihosoft.eu";

    static final String VERSION_NUMBER_STRING = 
        "v" + Constants.FREEROUTING_VERSION
            + " (build-date: "
            + Constants.FREEROUTING_BUILD_DATE +")";
}
