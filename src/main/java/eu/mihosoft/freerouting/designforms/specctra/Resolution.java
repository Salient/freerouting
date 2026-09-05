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
 * Resolution.java
 *
 * Created on 30. Oktober 2004, 08:00
 */

package eu.mihosoft.freerouting.designforms.specctra;

import eu.mihosoft.freerouting.logger.FRLogger;

/**
 * Class for reading resolution scopes from dsn-files.
 *
 * @author Alfons Wirtz
 */
public class Resolution extends ScopeKeyword
{
    
    /** Creates a new instance of Resolution */
    public Resolution()
    {
        super("resolution");
    }
    
    public boolean read_scope(ReadScopeParameter p_par)
    {
        try
        {
            // read the unit
            Object next_token = p_par.scanner.next_token();
            if (!(next_token instanceof String))
            {
                FRLogger.warn("Resolution.read_scope: string expected");
                return false;
            }
            p_par.unit = eu.mihosoft.freerouting.board.Unit.from_string((String) next_token);
            if (p_par.unit == null)
            {
                // An unrecognised unit string should not abort the whole parse. Default to mil (the
                // Specctra default) and keep going; the integer value and closing bracket are still
                // consumed below.
                FRLogger.warn("Resolution.read_scope: unrecognised unit '" + next_token + "'; defaulting to mil");
                p_par.unit = eu.mihosoft.freerouting.board.Unit.MIL;
            }
            // read the scale factor
            next_token = p_par.scanner.next_token();
            if (!(next_token instanceof Integer))
            {
                FRLogger.warn("Resolution.read_scope: integer expected");
                return false;
            }
            int resolution_value = ((Integer)next_token).intValue();
            if (resolution_value <= 0)
            {
                // The resolution is used as a divisor in coordinate transforms; a value of 0 (or
                // negative) would produce Infinity/NaN coordinates and silently corrupt the whole
                // board. Fall back to the Specctra default of 100 instead.
                FRLogger.warn("Resolution.read_scope: resolution must be positive, got " + resolution_value + "; defaulting to 100");
                resolution_value = 100;
            }
            p_par.resolution = resolution_value;
            // overread the closing bracket
            next_token = p_par.scanner.next_token();
            if (next_token != CLOSED_BRACKET)
            {
                FRLogger.warn("Resolution.read_scope: closing bracket expected");
                return false;
            }
            return true;
        }
        catch (java.io.IOException e)
        {
            FRLogger.error("Resolution.read_scope: IO error scanning file", e);
            return false;
        }
    }
    
    public static void write_scope(eu.mihosoft.freerouting.datastructures.IndentFileWriter p_file, eu.mihosoft.freerouting.board.Communication p_board_communication)  throws java.io.IOException
    {
        p_file.new_line();
        p_file.write("(resolution ");
        p_file.write(p_board_communication.unit.toString());
        p_file.write(" ");
        p_file.write((Integer.valueOf(p_board_communication.resolution)).toString());
        p_file.write(")");
    }
    
}
