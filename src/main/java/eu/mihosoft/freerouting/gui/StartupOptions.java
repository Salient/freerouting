package eu.mihosoft.freerouting.gui;

import eu.mihosoft.freerouting.logger.FRLogger;

import java.util.Locale;

/**
 * Andrey Belomutskiy
 * 6/28/2014
 */
public class StartupOptions {
    /** What to do with the routing a design file already contains. */
    public enum RoutingMode {
        /**
         * Treat existing copper as movable, so the autorouter may rip it up and reroute the
         * whole board. The historical behaviour, and the default.
         */
        REROUTE,
        /**
         * Protect existing copper (FixedState.USER_FIXED) so the autorouter only completes
         * the connections that are still incomplete.
         */
        FINISH,
        /**
         * Import and check clearances, do not autoroute. The report goes to -do.
         */
        VERIFY;

        static RoutingMode parse(String p_value) {
            for (RoutingMode mode : values()) {
                if (mode.name().equalsIgnoreCase(p_value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    boolean single_design_option = false;
    boolean test_version_option = false;
    boolean session_file_option = false;
    boolean webstart_option = false;
    String design_input_filename = null;
    String design_output_filename = null;
    String design_rules_filename = null;
    String design_input_directory_name = null;
    /** -dc: an Altium Constraints.xml to take the clearance matrix from. */
    String altium_constraints_filename = null;
    /** -rm: what to do with the routing already present in the design file. */
    RoutingMode routing_mode = RoutingMode.REROUTE;
    int max_passes = 99999;
    // -il: 0-100, how strongly the autorouter should prefer inner layers over the two
    // outermost signal layers. 0 (default) reproduces today's behaviour exactly -- see
    // AutorouteSettings.get_outer_layer_trace_cost_factor / get_outer_via_cost_factor.
    int inner_layer_preference = 0;
    // Wall-clock limit for headless batch autorouting, in seconds. <= 0 means no
    // limit (route until the autorouter finishes or the pass cap is reached).
    int max_seconds = 0;
    // -sp: which ripup pass's *weights* to start at (AutorouteSettings.start_pass_no). This is
    // NOT a checkpoint/restore -- the board is whatever -de loaded; only the ripup-cost
    // schedule (BatchAutorouter.java: ripup_costs = start_ripup_costs * pass_no) picks up as if
    // that many passes had already run. -1 is the sentinel for "not specified": a design file
    // can carry its own embedded start_pass_no (e.g. a freerouting-exported .dsn resumed from a
    // prior run), and the default here must not stomp on that when the flag is simply absent.
    int start_pass_no = -1;
    java.util.Locale current_locale = java.util.Locale.ENGLISH;

    private StartupOptions() {
    }

    public Locale getCurrentLocale() {
        return current_locale;
    }

    public static StartupOptions parse(String[] p_args) {
        StartupOptions result = new StartupOptions();
        result.process(p_args);
        return result;
    }

    private void process(String[] p_args) {
        for (int i = 0; i < p_args.length; ++i) {
            try {
                if (p_args[i].startsWith("-dc")) {
                    // an explicit Altium Constraints.xml supplying the clearance matrix
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        altium_constraints_filename = p_args[i + 1];
                    }
                } else if (p_args[i].startsWith("-de")) {
                    // the design file is provided
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        single_design_option = true;
                        design_input_filename = p_args[i + 1];
                    }
                } else if (p_args[i].startsWith("-di")) {
                    // the design directory is provided
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        design_input_directory_name = p_args[i + 1];
                    }
                } else if (p_args[i].startsWith("-do")) {
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        design_output_filename = p_args[i + 1];
                    }
                } else if (p_args[i].startsWith("-dr")) {
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        design_rules_filename = p_args[i + 1];
                    }
                } else if (p_args[i].startsWith("-rm")) {
                    // how to treat the routing the design file already contains
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        RoutingMode parsed = RoutingMode.parse(p_args[i + 1]);
                        if (parsed == null) {
                            FRLogger.warn("Unknown -rm mode '" + p_args[i + 1] + "'; keeping "
                                    + routing_mode.name().toLowerCase());
                        } else {
                            routing_mode = parsed;
                        }
                    }
                } else if (p_args[i].startsWith("-mp")) {
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        max_passes = Integer.decode(p_args[i + 1]);
                    }
                } else if (p_args[i].startsWith("-mt")) {
                    // maximum autorouting time in seconds (headless); requests a clean
                    // stop after the interval, then the routed-so-far board is exported.
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        max_seconds = Integer.decode(p_args[i + 1]);
                    }
                } else if (p_args[i].startsWith("-il")) {
                    // inner-layer preference, 0-100; see AutorouteSettings for what it does.
                    // Must be checked before "-l" below: "-il".startsWith("-l") is false, so
                    // ordering relative to "-l" doesn't actually matter here, but it must stay
                    // ahead of any future "-i*" flag that could be a prefix of this one.
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        inner_layer_preference = Integer.decode(p_args[i + 1]);
                    }
                } else if (p_args[i].startsWith("-sp")) {
                    // Must be checked before "-s" below: "-sp".startsWith("-s") is true, so if
                    // "-s" came first every "-sp N" would be swallowed as the (no-value) session
                    // file flag and N would be treated as the next, unrelated argument.
                    if (p_args.length > i + 1 && !p_args[i + 1].startsWith("-")) {
                        start_pass_no = Integer.decode(p_args[i + 1]);
                    }
                } else if (p_args[i].startsWith("-l")) {
                    // the locale is provided
                    if (p_args.length > i + 1 && p_args[i + 1].startsWith("d")) {
                        current_locale = java.util.Locale.GERMAN;
                    }
                } else if (p_args[i].startsWith("-s")) {
                    session_file_option = true;
                } else if (p_args[i].startsWith("-w")) {
                    webstart_option = true;
                } else if (p_args[i].startsWith("-test")) {
                    test_version_option = true;
                }
            }
            catch (Exception e)
            {
                FRLogger.error("There was a problem parsing the '"+p_args[i]+"' parameter", e);
            }
        }
    }

    public boolean getWebstartOption() {
        return webstart_option;
    }

    public boolean isTestVersion() {
        return test_version_option;
    }

    public String getDesignDir() {
        return design_input_directory_name;
    }
}
