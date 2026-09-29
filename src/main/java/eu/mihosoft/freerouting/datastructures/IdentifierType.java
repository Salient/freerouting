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
 * Identifier.java
 *
 * Created on 25. Januar 2005, 09:50
 */

package eu.mihosoft.freerouting.datastructures;

import eu.mihosoft.freerouting.logger.FRLogger;

import java.io.OutputStreamWriter;

/**
 * Describes legal identifiers together with the character used for string quotes.
 *
 * @author Alfons Wirtz
 */
public class IdentifierType
{
    /**
     * Defines the reserved characters and the string for quoting identifiers containing
     * reserved characters for a new instance of Identifier.
     */
    public IdentifierType(String [] p_reserved_chars, String p_string_quote)
    {
        reserved_chars = p_reserved_chars;
        string_quote = p_string_quote;
    }
    
    /**
     * Writes p_name after puttiong it into quotes, if it contains reserved characters or blanks.
     */
    public void write(String p_name, OutputStreamWriter p_file)
    {
        try
        {
            String name = escape_reserved(p_name);
            if (is_legal(name))
            {
                p_file.write(name);
            }
            else
            {
                p_file.write(quote(name));
            }
        }
        catch (java.io.IOException e)
        {
            FRLogger.warn("IndentFileWriter.new_line: unable to write to file");
        }
    }
    

    /**
     * Encodes the characters Altium escapes in its own Specctra output, so a name never has to
     * be quoted for containing one.
     *
     * <p>Taken from an Altium-generated .dsn of this very board, not from the Specctra spec: it
     * quotes nothing at all (zero quoted net names in the file) and writes ~SP~ for a space,
     * ~LP~ for '(' and ~RP~ for ')', 7901 / 120 / 118 times respectively. Backslashes and
     * hyphens it passes through literally.
     *
     * <p>Quoting these instead is not merely unconventional, it is unsafe. This board uses
     * Altium's overbar notation, so net names are full of backslashes and several END with one -
     * "R\E\A\D\Y\ \F\I\R\E\". Quoted, the trailing backslash escapes the closing quote and
     * the string never terminates, so Altium consumed the rest of the file as one token. That
     * produced thousands of traces fanned off to the coordinate origin, and hung Altium.
     */
    private static String escape_reserved(String p_name)
    {
        if (p_name == null)
        {
            return p_name;
        }
        return p_name.replace("(", "~LP~").replace(")", "~RP~").replace(" ", "~SP~");
    }

    /**
     * Looks, if p_string dous not contain reserved characters or blanks.
     */
    private boolean is_legal( String p_string)
    {
        if (p_string == null)
        {
            FRLogger.warn("IdentifierType.is_legal: p_string is null");
            return false;
        }
        for (int i = 0; i < reserved_chars.length; ++i)
        {
            if (p_string.contains(reserved_chars[i]))
            {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Puts p_sting into quotes.
     */
    private String quote(String p_string)
    {
        return string_quote + p_string + string_quote;
    }
    private final String string_quote;
    private final String[] reserved_chars;
}
