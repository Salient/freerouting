package eu.mihosoft.freerouting.logger;

import eu.mihosoft.freerouting.FreeRouting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;

public class FRLogger {
    private static Logger logger = LogManager.getLogger(FreeRouting.class);

    private static DecimalFormat performanceFormat = new DecimalFormat("0.00");

    private static HashMap<Integer, Instant> perfData = new HashMap<Integer, Instant>();

    public static void traceEntry(String perfId)
    {
        perfData.put(perfId.hashCode(), java.time.Instant.now());
    }

    public static void traceExit(String perfId)
    {
        traceExit(perfId, null);
    }

    public static void traceExit(String perfId, Object result)
    {
        long timeElapsed = Duration.between(perfData.get(perfId.hashCode()), java.time.Instant.now()).toMillis();

        perfData.remove(perfId.hashCode());
        if (timeElapsed < 0) {
            timeElapsed = 0;
        }
        logger.trace("Method '" + perfId.replace("{}", result != null ? result.toString() : "(null)") + "' was performed in " + performanceFormat.format(timeElapsed/1000.0) + " seconds.");
    }

    /**
     * Returns true, if trace-level messages are actually going to be recorded.
     * Callers that build up a fine-grained diagnostic message (e.g. a per-phase
     * timing breakdown) should guard that work with this check, so that the cost
     * of formatting the message is paid only when something will read it.
     */
    public static boolean isTraceEnabled()
    {
        return logger.isTraceEnabled();
    }

    /**
     * Logs p_msg at trace level. Off by default on the console appender; see
     * log4j2.xml. Used for cheap, opt-in performance diagnostics (see
     * isTraceEnabled()) rather than the traceEntry/traceExit pair, when several
     * related durations need to be reported together as a single line.
     */
    public static void trace(String msg)
    {
        logger.trace(msg);
    }

    public static void info(String msg)
    {
        logger.info(msg);
    }

    public static void warn(String msg)
    {
        logger.warn(msg);
    }

    public static void error(String msg, Throwable t)
    {
        if (t == null)
        {
            logger.error(msg);
        } else
        {
            logger.error(msg, t);
        }
    }
}
