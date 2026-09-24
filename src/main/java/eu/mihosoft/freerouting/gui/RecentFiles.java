/*
 *   Recently-opened design files, persisted across runs.
 */
package eu.mihosoft.freerouting.gui;

import eu.mihosoft.freerouting.logger.FRLogger;

import java.util.LinkedList;
import java.util.List;

/**
 * Keeps the list of recently opened design files, and the directory the last one
 * came from, in the user preferences so both survive a restart.
 */
public class RecentFiles
{
    private static final int MAX_ENTRIES = 10;
    private static final String KEY_PREFIX = "recent_design_";
    private static final String KEY_LAST_DIR = "last_design_dir";

    private RecentFiles()
    {
    }

    private static java.util.prefs.Preferences prefs()
    {
        return java.util.prefs.Preferences.userNodeForPackage(RecentFiles.class);
    }

    /**
     * The recently opened design file paths, most recent first. Entries whose file no
     * longer exists are left out, so a stale list does not offer dead paths.
     */
    public static List<String> get_paths()
    {
        List<String> result = new LinkedList<>();
        try
        {
            java.util.prefs.Preferences p = prefs();
            for (int i = 0; i < MAX_ENTRIES; ++i)
            {
                String path = p.get(KEY_PREFIX + i, null);
                if (path == null || path.isEmpty())
                {
                    continue;
                }
                if (new java.io.File(path).exists() && !result.contains(path))
                {
                    result.add(path);
                }
            }
        }
        catch (Exception e)
        {
            FRLogger.warn("RecentFiles.get_paths: could not read preferences: " + e.getLocalizedMessage());
        }
        return result;
    }

    /**
     * Records p_file as the most recently opened design, and its parent as the
     * directory to start the next file chooser in.
     */
    public static void add(java.io.File p_file)
    {
        if (p_file == null)
        {
            return;
        }
        try
        {
            String path = p_file.getAbsolutePath();
            List<String> paths = get_paths();
            paths.remove(path);
            paths.add(0, path);

            java.util.prefs.Preferences p = prefs();
            for (int i = 0; i < MAX_ENTRIES; ++i)
            {
                if (i < paths.size())
                {
                    p.put(KEY_PREFIX + i, paths.get(i));
                }
                else
                {
                    p.remove(KEY_PREFIX + i);
                }
            }
            String parent = p_file.getParent();
            if (parent != null)
            {
                p.put(KEY_LAST_DIR, parent);
            }
            p.flush();
        }
        catch (Exception e)
        {
            FRLogger.warn("RecentFiles.add: could not write preferences: " + e.getLocalizedMessage());
        }
    }

    /**
     * The directory a file chooser should open in: the one the last design was opened
     * from, or p_fallback when there is no usable stored directory.
     */
    public static String get_start_dir(String p_fallback)
    {
        try
        {
            String dir = prefs().get(KEY_LAST_DIR, null);
            if (dir != null && !dir.isEmpty() && new java.io.File(dir).isDirectory())
            {
                return dir;
            }
        }
        catch (Exception e)
        {
            FRLogger.warn("RecentFiles.get_start_dir: could not read preferences: " + e.getLocalizedMessage());
        }
        return p_fallback;
    }
}
