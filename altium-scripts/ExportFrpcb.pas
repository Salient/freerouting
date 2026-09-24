{..............................................................................}
{ ExportFrpcb.pas                                                             }
{                                                                              }
{ Exports the active PCB document to the FRPCB JSON interchange format for    }
{ import into freerouting. See /docs/frpcb-format.md for the format spec and }
{ the rationale (Altium's Specctra DSN export drops pairwise net-class        }
{ clearance rules and, at least once, individual pad shapes - this script     }
{ reads geometry and rules directly from the live PCB object model instead    }
{ of going through any intermediate export format).                          }
{                                                                              }
{ REQUIRES a script project: this file must be added as Document1 in         }
{ ExportFrpcb.PrjScr (shipped alongside this file) - a bare .pas is          }
{ invisible in the Run Script dialog (confirmed live-Altium requirement,     }
{ see reerouting/altium/VERIFICATION_STATUS.json). Open ExportFrpcb.PrjScr,  }
{ then File > Run Script, select ExportFrpcb in the dialog. Writes           }
{ <board-name>.frpcb.json next to the PCB document.                          }
{                                                                              }
{ Every non-trivial API call below (rule iteration, Scope1Expression/Gap,     }
{ net-class membership via IsMember, the FirstPCBObject-narrowing pattern,    }
{ board-outline segment fields) is grounded in Altium's own official          }
{ DelphiScript examples (IterateRules, MillExporter) and a tested community   }
{ DelphiScript<->JSON bridge - not guessed. One narrow gap remains: keepouts  }
{ are all emitted as the generic, most restrictive "keepout" type, because    }
{ nothing in the confirmed API distinguishes a copper keepout from a via-only }
{ or placement-only one (see WriteKeepouts). Everything else - layers,        }
{ outline, padstacks, components, nets, net classes with resolved width and   }
{ clearance, the pairwise clearance matrix, via rules, copper pours and       }
{ existing routed copper - is exported.                                      }
{..............................................................................}

Var
    OutLines     : TStringList;

{..............................................................................}
{ JSON string-building helpers. DelphiScript has no JSON library (confirmed - }
{ every real-world export script hand-builds JSON via string concatenation),  }
{ so this mirrors that convention rather than pull in any external library.   }
{..............................................................................}

Function JsonEscape(S : String) : String;
Var
    I : Integer;
    C : String;
    Result2 : String;
Begin
    Result2 := '';
    For I := 1 To Length(S) Do
    Begin
        C := S[I];
        If C = '"' Then Result2 := Result2 + '\"'
        Else If C = '\' Then Result2 := Result2 + '\\'
        Else If C = #13 Then Result2 := Result2 + ''
        Else If C = #10 Then Result2 := Result2 + '\n'
        Else Result2 := Result2 + C;
    End;
    Result := Result2;
End;

Function JStr(S : String) : String;
Begin
    Result := '"' + JsonEscape(S) + '"';
End;

{ CoordToMils / MilsToCoord are confirmed native DelphiScript coordinate       }
{ utility functions (used throughout the eda-agent bridge and Altium's own    }
{ scripts without local definition). FRPCB coordinates/lengths are always     }
{ mils (see frpcb_version/unit field), so every length written to the file    }
{ goes through CoordToMils first. }
Function CoordMils(C : TCoord) : String;
Begin
    Result := FloatToStr(CoordToMils(C));
End;

{..............................................................................}
{ Layers                                                                      }
{..............................................................................}

Function BoolStr(B : Boolean) : String;
Begin
    If B Then Result := 'true' Else Result := 'false';
End;

Procedure WriteLayers(Board : IPCB_Board);
Var
    LayerStack : IPCB_LayerStack_V7;
    LayerObj   : IPCB_LayerObject_V7;
    First      : Boolean;
Begin
    // Opens the top-level "board" object - see the COUPLING note on
    // WriteBoardOutline below. WriteLayers/WriteBoardOutline/WriteKeepouts
    // must run adjacently and in this order.
    OutLines.Add('  "board": { "layers": [');
    LayerStack := Board.LayerStack_V7;
    LayerObj := LayerStack.FirstLayer;
    First := True;
    While LayerObj <> Nil Do
    Begin
        If Not First Then OutLines.Add('    },');
        First := False;
        OutLines.Add('    { "name": ' + JStr(LayerObj.Name) + ', "signal": ' +
            // Confirmed usage splits between .LayerID and .V7_LayerID across
            // Altium versions in the reference scripts; .LayerID is the more
            // common of the two. If this throws on your Altium build, try
            // LayerObj.V7_LayerID instead.
            BoolStr(ILayer.IsSignalLayer(LayerObj.LayerID)));
        LayerObj := LayerStack.NextLayer(LayerObj);
    End;
    If Not First Then OutLines.Add('    }');
    OutLines.Add('  ],');
End;

{..............................................................................}
{ Board outline                                                               }
{                                                                              }
{ COUPLING: WriteLayers (above) opens the top-level "board" object and       }
{ leaves it open (trailing comma after the layers array); this procedure     }
{ adds "outline" and also leaves it open; WriteKeepouts, called immediately  }
{ after in ExportFrpcb, adds "keepouts" and closes the object. Keep all      }
{ three calls adjacent and in this exact order - do not insert another       }
{ OutLines.Add between them.                                                 }
{                                                                              }
{ CONFIRMED (Altium-DelphiScripts/PCB/PolygonReFitBO.pas, a real working      }
{ script that reads/writes board-outline segments): TPolySegment exposes      }
{ .vx/.vy (segment start vertex), .Kind (ePolySegmentLine / ePolySegmentArc), }
{ and for arc segments .cx/.cy/.Radius/.Angle1/.Angle2. Arc segments are      }
{ tessellated below into short line segments (12 degrees per step) since the  }
{ FRPCB format represents the outline as a plain point polygon.              }
{..............................................................................}

{ The clearance Altium requires between copper and the board outline, in Coords.

  Altium keeps this in a separate rule kind from ordinary clearance. That kind is
  NOT exposed as a named constant in this DelphiScript engine (eRule_Clearance
  covers copper-to-copper only), so it is matched by its numeric RuleKind - 63,
  BoardOutlineClearance, per the eda-agent reference notes - and a named constant
  is deliberately avoided because an identifier this engine does not know faults
  at runtime where Try/Except cannot catch it.

  Returns 0 when the board defines no such rule, which the importer treats as "use
  the default clearance". }
Function BoardOutlineClearance(Board : IPCB_Board) : TCoord;
Const
    RULEKIND_BOARD_OUTLINE_CLEARANCE = 63;
Var
    Iter : IPCB_BoardIterator;
    Rule : IPCB_ClearanceConstraint;
    Kind : Integer;
    G    : TCoord;
Begin
    Result := 0;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eRuleObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        Rule := Iter.FirstPCBObject;
        While Rule <> Nil Do
        Begin
            Kind := -1;
            Try Kind := Ord(Rule.RuleKind); Except End;
            If Kind = RULEKIND_BOARD_OUTLINE_CLEARANCE Then
            Begin
                G := 0;
                Try G := Rule.Gap; Except End;
                If G > Result Then Result := G;
            End;
            Rule := Iter.NextPCBObject;
        End;
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
End;

Procedure WriteBoardOutline(Board : IPCB_Board);
Const
    ARC_STEP_DEGREES = 12;
Var
    Outline    : IPCB_BoardOutline;
    I, Steps, S, NextI : Integer;
    Seg, NextSeg : TPolySegment;
    First      : Boolean;
    Angle, Rad : Double;
    StartAngle, EndAngle, Span : Double;
    NX, NY     : Integer;
Begin
    OutLines.Add('  "outline": [ [');
    Outline := Board.BoardOutline;
    First := True;
    For I := 0 To Outline.PointCount - 1 Do
    Begin
        Seg := Outline.Segments[I];
        If Not First Then OutLines.Add('      ,');
        First := False;
        OutLines.Add('      [' + CoordMils(Seg.vx) + ', ' + CoordMils(Seg.vy) + ']');
        If Seg.Kind = ePolySegmentArc Then
        Begin
            // Tessellate the arc into short line segments, emitting only the
            // INTERMEDIATE points (this segment's start vertex was just written,
            // and the arc's end point is the NEXT segment's start vertex).
            //
            // DIRECTION: an arc from A to B can be traversed either way round,
            // so neither Angle1/Angle2 order nor "which endpoint is closer to
            // the start vertex" identifies the correct sweep - for a semicircle
            // both endpoints are equidistant, which is exactly this board's
            // left-edge cutouts, and guessing inverted them from concave to
            // convex. The unambiguous signal is that the arc must END on the
            // next segment's start vertex: choose the sweep whose end angle
            // lands there.
            Rad := Seg.Radius;
            NextI := I + 1;
            If NextI > Outline.PointCount - 1 Then NextI := 0;
            NextSeg := Outline.Segments[NextI];
            NX := NextSeg.vx;
            NY := NextSeg.vy;

            // Angle of this segment's start vertex and of the next vertex, as
            // seen from the arc centre. ArcTan2 is the reliable way to recover
            // them; Angle1/Angle2 alone do not say which is the start.
            StartAngle := ArcTan2(1.0 * Seg.vy - Seg.cy, 1.0 * Seg.vx - Seg.cx) * (180.0 / 3.14159265);
            EndAngle := ArcTan2(1.0 * NY - Seg.cy, 1.0 * NX - Seg.cx) * (180.0 / 3.14159265);

            // Take the SHORT way round from start to end. Altium outline arcs
            // are <= 180 degrees per segment (a full circle is split), so the
            // short sweep is the correct one, and this needs no knowledge of
            // Angle1/Angle2 ordering at all.
            Span := EndAngle - StartAngle;
            While Span <= -180 Do Span := Span + 360;
            While Span > 180 Do Span := Span - 360;

            Steps := Round(Abs(Span) / ARC_STEP_DEGREES);
            If Steps < 1 Then Steps := 1;
            For S := 1 To Steps - 1 Do
            Begin
                Angle := (StartAngle + Span * (S / Steps)) * (3.14159265 / 180.0);
                OutLines.Add('      ,[' + CoordMils(Seg.cx + Round(Rad * Cos(Angle))) +
                    ', ' + CoordMils(Seg.cy + Round(Rad * Sin(Angle))) + ']');
            End;
        End;
    End;
    OutLines.Add('  ] ],');
    // Clearance from copper to the board edge. Emitted inside "board" so the
    // importer can give the outline its own clearance class; without it the
    // outline falls back to the default class and the autorouter will happily
    // route right up to the board edge.
    OutLines.Add('  "outline_clearance": ' + CoordMils(BoardOutlineClearance(Board)) + ',');
End;

{..............................................................................}
{ Keepouts                                                                    }
{                                                                              }
{ Altium has no dedicated "keepout object" type - the DRC treats primitives on }
{ the special eKeepOutLayer as keepouts by LAYER IDENTITY. The reliably usable  }
{ shape is a POLYGON (IPCB_Polygon) on that layer, which exposes the same      }
{ PointCount/ShapeSegments boundary the board outline does. Bare Track         }
{ segments on the keepout layer are also legal in Altium but describe an open  }
{ polyline, not an area - the previous version of this routine emitted each     }
{ such track as a 2-point "polygon", which the FRPCB importer correctly        }
{ rejected (an area needs >= 3 points), so those entries were pure noise and   }
{ are no longer emitted.                                                       }
{                                                                              }
{ STILL UNRESOLVED: nothing in the confirmed API distinguishes a copper        }
{ keepout from a via-only or placement-only keepout, so every entry is typed   }
{ "keepout" (the generic, most restrictive case). FRPCB supports via_keepout   }
{ and place_keepout; populating them needs a live-Altium probe of the room /   }
{ eKeepout_* scope flags.                                                      }
{                                                                              }
{ COUPLING: closes the "board" object opened by WriteLayers and continued   }
{ by WriteBoardOutline - see the notes there. Must run immediately after     }
{ WriteBoardOutline.                                                         }
{..............................................................................}

Procedure WriteKeepouts(Board : IPCB_Board);
Const
    ARC_STEP_DEGREES = 12;
Var
    Iter    : IPCB_BoardIterator;
    Poly    : IPCB_Polygon;
    I, Steps, S, NextI : Integer;
    Seg, NextSeg : TPolySegment;
    First, FirstPt : Boolean;
    Angle, Rad : Double;
    StartAngle, EndAngle, Span : Double;
Begin
    OutLines.Add('  "keepouts": [');
    First := True;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(ePolyObject));
        Iter.AddFilter_LayerSet(MkSet(eKeepOutLayer));
        Iter.AddFilter_Method(eProcessAll);
        Poly := Iter.FirstPCBObject;
        While Poly <> Nil Do
        Begin
            If Poly.PointCount >= 3 Then
            Begin
                If Not First Then OutLines.Add('    },');
                First := False;
                OutLines.Add('    { "type": "keepout", "layers": "all",');
                OutLines.Add('      "polygon": [');
                FirstPt := True;
                For I := 0 To Poly.PointCount - 1 Do
                Begin
                    Seg := Poly.ShapeSegments[I];
                    If Not FirstPt Then OutLines.Add('        ,');
                    FirstPt := False;
                    OutLines.Add('        [' + CoordMils(Seg.vx) + ', ' + CoordMils(Seg.vy) + ']');
                    If Seg.Kind = ePolySegmentArc Then
                    Begin
                        // Same arc handling as WriteBoardOutline: sweep the short
                        // way from this vertex to the NEXT segment's vertex. See
                        // the long comment there for why Angle1/Angle2 ordering
                        // and endpoint-distance tests both fail on semicircles.
                        Rad := Seg.Radius;
                        NextI := I + 1;
                        If NextI > Poly.PointCount - 1 Then NextI := 0;
                        NextSeg := Poly.ShapeSegments[NextI];
                        StartAngle := ArcTan2(1.0 * Seg.vy - Seg.cy, 1.0 * Seg.vx - Seg.cx) * (180.0 / 3.14159265);
                        EndAngle := ArcTan2(1.0 * NextSeg.vy - Seg.cy, 1.0 * NextSeg.vx - Seg.cx) * (180.0 / 3.14159265);
                        Span := EndAngle - StartAngle;
                        While Span <= -180 Do Span := Span + 360;
                        While Span > 180 Do Span := Span - 360;
                        Steps := Round(Abs(Span) / ARC_STEP_DEGREES);
                        If Steps < 1 Then Steps := 1;
                        For S := 1 To Steps - 1 Do
                        Begin
                            Angle := (StartAngle + Span * (S / Steps)) * (3.14159265 / 180.0);
                            OutLines.Add('        ,[' + CoordMils(Seg.cx + Round(Rad * Cos(Angle))) +
                                ', ' + CoordMils(Seg.cy + Round(Rad * Sin(Angle))) + ']');
                        End;
                    End;
                End;
                OutLines.Add('      ]');
            End;
            Poly := Iter.NextPCBObject;
        End;
        If Not First Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
    OutLines.Add('  ] },');
End;

{..............................................................................}
{ Padstacks - one entry per distinct pad "name" actually used on the board.   }
{ Reads geometry straight off each IPCB_Pad (TopXSize/TopYSize/TopShape etc), }
{ never through any export format, precisely to avoid the Altium Specctra-    }
{ exporter defect found earlier (a handful of ordinary rect pads exported     }
{ with NO shape sub-scope at all in a live test file - see the "Why           }
{ shapeless-padstack tolerance still matters" note in docs/frpcb-format.md).  }
{ Plated = False (no copper on any layer) is written as a shapeless          }
{ padstack, matching the format's shapeless-padstack convention.             }
{..............................................................................}

Function ShapeName(S : TShape) : String;
Begin
    // eRoundedRectangular appears in some SDKs, eRoundRectangle in others -
    // handled defensively per the research pass's flagged gap.
    Case S Of
        eRounded         : Result := 'circle';
        eRectangular     : Result := 'rect';
        eOctagonal       : Result := 'octagon';
    Else
        Result := 'rect'; // rounded-rect / unknown - approximate as rect
    End;
End;

{ Emits one layer's pad shape. Rot is the pad's rotation in degrees.

  A circle is rotation-invariant. An axis-aligned rectangle covers 0/90/180/270
  (the caller pre-swaps X/Y for 90/270, see PadSwapXY). Anything else - this
  board has 18 components at 45, 110, 135 and 200 degrees - cannot be expressed
  as an axis-aligned rect at all, so the pad is emitted as an explicit rotated
  4-point polygon instead. Without this those pads kept their unrotated
  orientation while their positions were correct, which is exactly the
  "footprints angled between horizontal and vertical are not rotated" symptom. }
Procedure WritePadShapeRot(LayerTag : String; XSize, YSize : TCoord; Shape : TShape; Rot : Double);
Var
    R, C, Sn, HX, HY : Double;
    I : Integer;
    PxArr, PyArr : Array[0..3] Of Double;
    Pts : String;
    Orth : Boolean;
Begin
    If Shape = eRounded Then
    Begin
        OutLines.Add('      "' + LayerTag + '": { "type": "circle", "diameter": ' +
            CoordMils(XSize) + ' }');
        Exit;
    End;

    // Is the rotation an exact multiple of 90 (within a small tolerance)?
    R := Rot;
    While R < 0 Do R := R + 360;
    While R >= 90 Do R := R - 90;
    Orth := (R < 0.5) Or (R > 89.5);

    If Orth Then
    Begin
        OutLines.Add('      "' + LayerTag + '": { "type": "' + ShapeName(Shape) +
            '", "width": ' + CoordMils(XSize) + ', "height": ' + CoordMils(YSize) + ' }');
        Exit;
    End;

    // Rotated rectangle, as a polygon centred on the pad origin. FRPCB padstack
    // shapes are relative to the pad location, so these points are offsets.
    C := Cos(Rot * (3.14159265 / 180.0));
    Sn := Sin(Rot * (3.14159265 / 180.0));
    HX := 1.0 * XSize / 2;
    HY := 1.0 * YSize / 2;
    PxArr[0] := -HX; PyArr[0] := -HY;
    PxArr[1] :=  HX; PyArr[1] := -HY;
    PxArr[2] :=  HX; PyArr[2] :=  HY;
    PxArr[3] := -HX; PyArr[3] :=  HY;
    Pts := '';
    For I := 0 To 3 Do
    Begin
        If I > 0 Then Pts := Pts + ', ';
        Pts := Pts + '[' + FloatToStr(CoordToMils(Round(PxArr[I] * C - PyArr[I] * Sn))) +
            ', ' + FloatToStr(CoordToMils(Round(PxArr[I] * Sn + PyArr[I] * C))) + ']';
    End;
    OutLines.Add('      "' + LayerTag + '": { "type": "polygon", "points": [' + Pts + '] }');
End;

Procedure WritePadShape(LayerTag : String; XSize, YSize : TCoord; Shape : TShape);
Begin
    WritePadShapeRot(LayerTag, XSize, YSize, Shape, 0);
End;

{ A padstack identity derived from GEOMETRY, not from Pad.Name. Pad.Name is the
  pin DESIGNATOR ('1', '2', 'A', ...) and repeats on every component, so keying
  padstacks by it collapsed 2443 distinct pads on a live board down to 63
  padstacks - every component's pin '1' inherited whatever geometry the first
  component's pin '1' happened to have, scattering pins and leaving ~1600 nets
  incomplete. Pads that truly share size/shape/hole legitimately share one
  padstack entry, which is the point of a padstack library. }
{ The pad's own rotation in degrees, 0 if unavailable. }
Function PadRotation(Pad : IPCB_Pad) : Double;
Begin
    Result := 0;
    Try Result := Pad.Rotation; Except End;
End;

{ Padstack-identity suffix for a pad whose rotation is NOT a multiple of 90.
  Orthogonal pads return '' so they keep sharing padstacks with each other (the
  caller already swaps X/Y for 90/270); a pad at e.g. 45 degrees is emitted as a
  rotated polygon, so its angle is part of what makes the padstack distinct. }
Function PadRotKey(Pad : IPCB_Pad) : String;
Var
    R, M : Double;
Begin
    Result := '';
    R := 0;
    Try R := Pad.Rotation; Except End;
    While R < 0 Do R := R + 360;
    While R >= 360 Do R := R - 360;
    M := R;
    While M >= 90 Do M := M - 90;
    If (M >= 0.5) And (M <= 89.5) Then
        Result := '_r' + FloatToStr(Round(R * 10) / 10);
End;

{ True when this pad is surface-mount, i.e. it has copper on exactly one layer
  (the layer it sits on) rather than a barrel through the whole stack. Used both
  for the padstack identity and to decide which layers get a shape. Falls back to
  "no hole means SMD", which is how Altium models it, if IsSurfaceMount is not
  available on this build. }
Function IsSmdPad(Pad : IPCB_Pad) : Boolean;
Begin
    Result := False;
    Try
        Result := Pad.IsSurfaceMount;
    Except
        Result := (Pad.HoleSize <= 0);
    End;
End;

{ True when this pad's own rotation means its X/Y extents must be swapped to
  land on the board in the right orientation.

  Pad.TopXSize/TopYSize are the pad's UNROTATED dimensions; the pad's actual
  orientation lives in Pad.Rotation. FRPCB pad shapes are axis-aligned extents
  with no rotation field (and the importer places components with rotation 0,
  because pin coordinates are already absolute), so a pad rotated 90 or 270
  degrees has to be emitted with its width and height exchanged. Without this,
  every rectangular pad on a 90/270-rotated part came out rotated 90 degrees
  from its true orientation on a live board.

  Rotations that are not near a right angle cannot be represented by swapping
  extents at all; those pads keep their unrotated extents (the bounding
  behaviour is still approximately right for near-square pads, and such pads
  are rare). }
Function PadSwapXY(Pad : IPCB_Pad) : Boolean;
Var
    R : Double;
Begin
    Result := False;
    R := 0;
    Try R := Pad.Rotation; Except End;
    // Normalise into [0,180) - a 180 degree rotation leaves extents unchanged.
    While R < 0 Do R := R + 360;
    While R >= 180 Do R := R - 180;
    // ONLY a near-exact quarter turn swaps extents. Any other angle is emitted as
    // an explicitly rotated polygon by WritePadShapeRot, which rotates the
    // UNSWAPPED extents - so swapping here too would transform such a pad twice
    // and land it 90 degrees off (seen on the 110 and 200 degree parts).
    Result := (R > 89.5) And (R < 90.5);
End;

Function PadstackKey(Pad : IPCB_Pad) : String;
Var
    TX, TY, BX, BY : TCoord;
Begin
    If (Pad.TopXSize <= 0) And (Pad.TopYSize <= 0) And
       (Pad.BotXSize <= 0) And (Pad.BotYSize <= 0) Then
        Result := 'nopad_' + CoordMils(Pad.HoleSize)
    Else
    Begin
        If PadSwapXY(Pad) Then
        Begin
            TX := Pad.TopYSize; TY := Pad.TopXSize;
            BX := Pad.BotYSize; BY := Pad.BotXSize;
        End
        Else
        Begin
            TX := Pad.TopXSize; TY := Pad.TopYSize;
            BX := Pad.BotXSize; BY := Pad.BotYSize;
        End;
        // An SMD pad has copper on ONE side only, so which side it is on is part
        // of the padstack's identity - two otherwise identical SMD pads on
        // opposite sides are different padstacks. Without this the exported
        // padstack claimed copper on BOTH Top Layer and Bottom Layer, which put
        // every SMD pad on both sides of the board.
        If IsSmdPad(Pad) Then
            Result := 'smd' + IntToStr(Ord(Pad.Layer)) + '_' +
                IntToStr(Ord(Pad.TopShape)) + '_' +
                CoordMils(TX) + 'x' + CoordMils(TY) + PadRotKey(Pad)
        Else
            Result := 'pad_' + IntToStr(Ord(Pad.TopShape)) + '_' +
                CoordMils(TX) + 'x' + CoordMils(TY) + '_' +
                IntToStr(Ord(Pad.BotShape)) + '_' +
                CoordMils(BX) + 'x' + CoordMils(BY) + '_h' +
                CoordMils(Pad.HoleSize) + PadRotKey(Pad);
    End;
End;

Procedure WritePadstacks(Board : IPCB_Board);
Var
    Iter      : IPCB_BoardIterator;
    Pad       : IPCB_Pad;
    Via       : IPCB_Via;
    Seen      : TStringList;
    First     : Boolean;
    TopName, BotName : String;
    ViaName   : String;
    PsName    : String;
    LayerStack : IPCB_LayerStack_V7;
    LayerObj   : IPCB_LayerObject_V7;
Begin
    OutLines.Add('  "padstacks": [');
    // Use the board's ACTUAL top/bottom layer names (e.g. "Top Layer", not the
    // literal string "TopLayer") - these must match board.layers' names exactly,
    // or the FRPCB importer cannot resolve which layer a pad shape belongs to
    // and silently drops every padstack's copper (confirmed failure mode: a
    // live export against a real board produced "references unknown layer
    // 'TopLayer'" for every single padstack, because this board's real layer
    // was named "Top Layer" with a space).
    TopName := Board.LayerName(eTopLayer);
    BotName := Board.LayerName(eBottomLayer);
    Seen := TStringList.Create;
    Seen.Sorted := True;
    Seen.Duplicates := dupIgnore;
    First := True;
    Try
        Iter := Board.BoardIterator_Create;
        Try
            Iter.AddFilter_ObjectSet(MkSet(ePadObject));
            Iter.AddFilter_LayerSet(AllLayers);
            Iter.AddFilter_Method(eProcessAll);
            Pad := Iter.FirstPCBObject;
            While Pad <> Nil Do
            Begin
                PsName := PadstackKey(Pad);
                If Seen.IndexOf(PsName) < 0 Then
                Begin
                    Seen.Add(PsName);
                    If Not First Then OutLines.Add('    },');
                    First := False;

                    OutLines.Add('    { "name": ' + JStr(PsName) + ',');
                    If Pad.HoleSize > 0 Then
                        OutLines.Add('      "drill": ' + CoordMils(Pad.HoleSize) + ',')
                    Else
                        OutLines.Add('      "drill": null,');

                    // "Shapeless" means NO COPPER ON ANY LAYER (a bare mounting
                    // hole / NPTH / fiducial), which is a size test - NOT
                    // Pad.Plated. Plated describes only whether the HOLE is
                    // plated: every ordinary SMD pad has Plated = False and no
                    // hole, yet has real copper. Using Plated as the shapeless
                    // test stripped the copper off 167 real SMD pads on a live
                    // board, which made their pins unresolvable on import and
                    // left ~1600 nets incomplete.
                    If (Pad.TopXSize <= 0) And (Pad.TopYSize <= 0) And
                       (Pad.BotXSize <= 0) And (Pad.BotYSize <= 0) Then
                        OutLines.Add('      "shapes": {}')
                    Else
                    Begin
                        OutLines.Add('      "shapes": {');
                        If IsSmdPad(Pad) Then
                        Begin
                            // SMD: copper on its own layer ONLY. Emitting both Top
                            // and Bottom (as this used to) put every SMD pad on
                            // both sides of the board.
                            If PadSwapXY(Pad) Then
                                WritePadShapeRot(Board.LayerName(Pad.Layer), Pad.TopYSize, Pad.TopXSize, Pad.TopShape, PadRotation(Pad))
                            Else
                                WritePadShapeRot(Board.LayerName(Pad.Layer), Pad.TopXSize, Pad.TopYSize, Pad.TopShape, PadRotation(Pad));
                        End
                        Else
                        Begin
                            // Through-hole: copper on EVERY signal layer, not just
                            // top and bottom. This board has 4127 traces on its four
                            // inner layers (Sig [Vert], Sig [Horiz], PWR, GND);
                            // emitting only top/bottom shapes left all of them
                            // electrically orphaned from their pins, so the nets
                            // stayed "incomplete" and freerouting drew ratsnest
                            // lines across the board. MidXSize/MidYSize/MidShape
                            // describe the inner-layer copy of the pad.
                            If PadSwapXY(Pad) Then
                                WritePadShapeRot(TopName, Pad.TopYSize, Pad.TopXSize, Pad.TopShape, PadRotation(Pad))
                            Else
                                WritePadShapeRot(TopName, Pad.TopXSize, Pad.TopYSize, Pad.TopShape, PadRotation(Pad));
                            LayerStack := Board.LayerStack_V7;
                            LayerObj := LayerStack.FirstLayer;
                            While LayerObj <> Nil Do
                            Begin
                                If (LayerObj.Name <> TopName) And (LayerObj.Name <> BotName)
                                   And ILayer.IsSignalLayer(LayerObj.LayerID) Then
                                Begin
                                    OutLines.Add('      ,');
                                    If (Pad.MidXSize > 0) And (Pad.MidYSize > 0) Then
                                        If PadSwapXY(Pad) Then WritePadShapeRot(LayerObj.Name, Pad.MidYSize, Pad.MidXSize, Pad.MidShape, PadRotation(Pad))
                                        Else WritePadShapeRot(LayerObj.Name, Pad.MidXSize, Pad.MidYSize, Pad.MidShape, PadRotation(Pad))
                                    Else
                                        If PadSwapXY(Pad) Then WritePadShapeRot(LayerObj.Name, Pad.TopYSize, Pad.TopXSize, Pad.TopShape, PadRotation(Pad))
                                        Else WritePadShapeRot(LayerObj.Name, Pad.TopXSize, Pad.TopYSize, Pad.TopShape, PadRotation(Pad));
                                End;
                                LayerObj := LayerStack.NextLayer(LayerObj);
                            End;
                            OutLines.Add('      ,');
                            If PadSwapXY(Pad) Then
                                WritePadShapeRot(BotName, Pad.BotYSize, Pad.BotXSize, Pad.BotShape, PadRotation(Pad))
                            Else
                                WritePadShapeRot(BotName, Pad.BotXSize, Pad.BotYSize, Pad.BotShape, PadRotation(Pad));
                        End;
                        OutLines.Add('      }');
                    End;
                End;
                Pad := Iter.NextPCBObject;
            End;
        Finally
            Board.BoardIterator_Destroy(Iter);
        End;

        // Vias reference a padstack by the same synthesized name WriteRouting
        // uses ('via_<size>_<hole>') - emit one padstack entry per distinct
        // via size/hole combination actually used, or every routed via
        // referencing an undefined padstack gets silently skipped on import
        // (confirmed failure mode on a live board: ~1000 vias skipped this
        // way). A via's copper is a plain round pad on every signal layer it
        // spans - see the layer loop below.
        Iter := Board.BoardIterator_Create;
        Try
            Iter.AddFilter_ObjectSet(MkSet(eViaObject));
            Iter.AddFilter_LayerSet(AllLayers);
            Iter.AddFilter_Method(eProcessAll);
            Via := Iter.FirstPCBObject;
            While Via <> Nil Do
            Begin
                ViaName := 'via_' + CoordMils(Via.Size) + '_' + CoordMils(Via.HoleSize);
                If Seen.IndexOf(ViaName) < 0 Then
                Begin
                    Seen.Add(ViaName);
                    If Not First Then OutLines.Add('    },');
                    First := False;
                    OutLines.Add('    { "name": ' + JStr(ViaName) + ',');
                    OutLines.Add('      "drill": ' + CoordMils(Via.HoleSize) + ',');
                    OutLines.Add('      "shapes": {');
                    WritePadShape(TopName, Via.Size, Via.Size, eRounded);
                    // Same reasoning as through-hole pads above: a via's barrel
                    // carries copper on every signal layer it spans, and without
                    // inner-layer shapes the inner-layer traces it connects stay
                    // orphaned. FRPCB has no per-via layer span, so this emits the
                    // via's round pad on all signal layers.
                    LayerStack := Board.LayerStack_V7;
                    LayerObj := LayerStack.FirstLayer;
                    While LayerObj <> Nil Do
                    Begin
                        If (LayerObj.Name <> TopName) And (LayerObj.Name <> BotName)
                           And ILayer.IsSignalLayer(LayerObj.LayerID) Then
                        Begin
                            OutLines.Add('      ,');
                            WritePadShape(LayerObj.Name, Via.Size, Via.Size, eRounded);
                        End;
                        LayerObj := LayerStack.NextLayer(LayerObj);
                    End;
                    OutLines.Add('      ,');
                    WritePadShape(BotName, Via.Size, Via.Size, eRounded);
                    OutLines.Add('      }');
                End;
                Via := Iter.NextPCBObject;
            End;
        Finally
            Board.BoardIterator_Destroy(Iter);
        End;
    Finally
        Seen.Free;
    End;
    If Not First Then OutLines.Add('    }');
    OutLines.Add('  ],');
End;

{..............................................................................}
{ Components + their pins.                                                    }
{..............................................................................}

Function NetNameOf(Pad : IPCB_Pad) : String;
Begin
    If Pad.Net <> Nil Then Result := Pad.Net.Name Else Result := '';
End;

Procedure WriteComponents(Board : IPCB_Board);
Var
    CompIter : IPCB_BoardIterator;
    Comp     : IPCB_Component;
    PadIter  : IPCB_GroupIterator;
    Pad      : IPCB_Pad;
    FirstC, FirstP : Boolean;
    Side     : String;
Begin
    OutLines.Add('  "components": [');
    FirstC := True;
    CompIter := Board.BoardIterator_Create;
    Try
        CompIter.AddFilter_ObjectSet(MkSet(eComponentObject));
        CompIter.AddFilter_LayerSet(AllLayers);
        CompIter.AddFilter_Method(eProcessAll);
        Comp := CompIter.FirstPCBObject;
        While Comp <> Nil Do
        Begin
            If Not FirstC Then OutLines.Add('    },');
            FirstC := False;

            If Comp.Layer = eBottomLayer Then Side := 'bottom' Else Side := 'top';

            OutLines.Add('    { "name": ' + JStr(Comp.Name.Text) + ',');
            OutLines.Add('      "package": ' + JStr(Comp.Pattern) + ',');
            OutLines.Add('      "x": ' + CoordMils(Comp.x) + ', "y": ' + CoordMils(Comp.y) + ',');
            OutLines.Add('      "rotation": ' + FloatToStr(Comp.Rotation) + ',');
            OutLines.Add('      "side": ' + JStr(Side) + ',');
            OutLines.Add('      "pins": [');

            FirstP := True;
            PadIter := Comp.GroupIterator_Create;
            Try
                PadIter.AddFilter_ObjectSet(MkSet(ePadObject));
                Pad := PadIter.FirstPCBObject;
                While Pad <> Nil Do
                Begin
                    If Not FirstP Then OutLines.Add('        },');
                    FirstP := False;
                    OutLines.Add('        { "pin": ' + JStr(Pad.Name) + ', "net": ' +
                        JStr(NetNameOf(Pad)) + ', "padstack": ' + JStr(PadstackKey(Pad)) + ',');
                    OutLines.Add('          "x": ' + CoordMils(Pad.X) + ', "y": ' + CoordMils(Pad.Y) + ' ');
                    Pad := PadIter.NextPCBObject;
                End;
                If Not FirstP Then OutLines.Add('        }');
            Finally
                Comp.GroupIterator_Destroy(PadIter);
            End;
            OutLines.Add('      ]');

            Comp := CompIter.NextPCBObject;
        End;
        If Not FirstC Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(CompIter);
    End;
    OutLines.Add('  ],');
End;

{..............................................................................}
{ Nets - name + pin list ("Component-Pin" references, matching the FRPCB     }
{ spec's "component-pin" convention). Per-net width/clearance override is    }
{ intentionally NOT emitted here: those overrides are a per-net Specctra-era  }
{ concept and Altium's native rule engine expresses the same intent through   }
{ net classes + the clearance matrix instead, which this script already      }
{ captures more precisely in WriteNetClasses/WriteClearanceMatrix below.      }
{..............................................................................}

Procedure WriteNets(Board : IPCB_Board);
Var
    NetIter  : IPCB_BoardIterator;
    Net      : IPCB_Net;
    PinIter  : IPCB_GroupIterator;
    Pad      : IPCB_Pad;
    FirstN, FirstP : Boolean;
Begin
    OutLines.Add('  "nets": [');
    FirstN := True;
    NetIter := Board.BoardIterator_Create;
    Try
        NetIter.AddFilter_ObjectSet(MkSet(eNetObject));
        NetIter.AddFilter_LayerSet(AllLayers);
        NetIter.AddFilter_Method(eProcessAll);
        Net := NetIter.FirstPCBObject;
        While Net <> Nil Do
        Begin
            If Not FirstN Then OutLines.Add('    },');
            FirstN := False;

            OutLines.Add('    { "name": ' + JStr(Net.Name) + ',');
            OutLines.Add('      "pins": [');

            FirstP := True;
            PinIter := Net.GroupIterator_Create;
            Try
                PinIter.AddFilter_ObjectSet(MkSet(ePadObject));
                Pad := PinIter.FirstPCBObject;
                While Pad <> Nil Do
                Begin
                    If Not FirstP Then OutLines.Add('        ,');
                    FirstP := False;
                    If Pad.Component <> Nil Then
                        OutLines.Add('        ' + JStr(Pad.Component.Name.Text + '-' + Pad.Name))
                    Else
                        OutLines.Add('        ' + JStr(Pad.Name));
                    Pad := PinIter.NextPCBObject;
                End;
            Finally
                Net.GroupIterator_Destroy(PinIter);
            End;
            OutLines.Add('      ]');

            Net := NetIter.NextPCBObject;
        End;
        If Not FirstN Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(NetIter);
    End;
    OutLines.Add('  ],');
End;

{..............................................................................}
{ Named via rules (the top-level "vias" section). Each entry names a padstack  }
{ the autorouter is allowed to place when it needs to change layers; net        }
{ classes reference one by name via their "via" field. Without this the         }
{ importer has no via to place and the autorouter can only route single-layer. }
{ One rule is emitted per distinct via geometry actually present on the board, }
{ named identically to the padstack WritePadstacks emits for it.               }
{..............................................................................}

Procedure WriteViaRules(Board : IPCB_Board);
Var
    Iter    : IPCB_BoardIterator;
    Via     : IPCB_Via;
    Seen    : TStringList;
    First   : Boolean;
    ViaName : String;
Begin
    OutLines.Add('  "vias": [');
    Seen := TStringList.Create;
    Seen.Sorted := True;
    Seen.Duplicates := dupIgnore;
    First := True;
    Try
        Iter := Board.BoardIterator_Create;
        Try
            Iter.AddFilter_ObjectSet(MkSet(eViaObject));
            Iter.AddFilter_LayerSet(AllLayers);
            Iter.AddFilter_Method(eProcessAll);
            Via := Iter.FirstPCBObject;
            While Via <> Nil Do
            Begin
                ViaName := 'via_' + CoordMils(Via.Size) + '_' + CoordMils(Via.HoleSize);
                If Seen.IndexOf(ViaName) < 0 Then
                Begin
                    Seen.Add(ViaName);
                    If Not First Then OutLines.Add('    },');
                    First := False;
                    OutLines.Add('    { "name": ' + JStr(ViaName) +
                        ', "padstack": ' + JStr(ViaName) + ' ');
                End;
                Via := Iter.NextPCBObject;
            End;
            If Not First Then OutLines.Add('    }');
        Finally
            Board.BoardIterator_Destroy(Iter);
        End;
    Finally
        Seen.Free;
    End;
    OutLines.Add('  ],');
End;

{ The via rule name a net class should use. Picks the smallest via on the
  board (by pad size) so the autorouter defaults to the least intrusive one
  that actually exists in this design; returns '' if the board has no vias. }
Function DefaultViaRuleName(Board : IPCB_Board) : String;
Var
    Iter    : IPCB_BoardIterator;
    Via     : IPCB_Via;
    BestSize : TCoord;
Begin
    Result := '';
    BestSize := 0;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eViaObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        Via := Iter.FirstPCBObject;
        While Via <> Nil Do
        Begin
            If (BestSize = 0) Or (Via.Size < BestSize) Then
            Begin
                BestSize := Via.Size;
                Result := 'via_' + CoordMils(Via.Size) + '_' + CoordMils(Via.HoleSize);
            End;
            Via := Iter.NextPCBObject;
        End;
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
End;

{..............................................................................}
{ Net classes - enumerate via eClassObject filtered to MemberKind =           }
{ eClassMemberKind_Net (confirmed pattern). MemberCount/MemberName[] are NOT  }
{ exposed in DelphiScript (confirmed absent from the type library), so       }
{ membership is recovered the same way the eda-agent bridge does it: iterate  }
{ every net on the board and test NetClassObj.IsMember(Net) - O(nets *       }
{ classes), unavoidable per every real-world example found.                  }
{..............................................................................}

Function ExtractNetClassName(ScopeExpr : String) : String;
Var
    P1, P2 : Integer;
Begin
    Result := '';
    P1 := Pos('InNetClass(''', ScopeExpr);
    If P1 = 0 Then Exit;
    P1 := P1 + Length('InNetClass(''');
    P2 := Pos('''', Copy(ScopeExpr, P1, Length(ScopeExpr)));
    If P2 = 0 Then Exit;
    Result := Copy(ScopeExpr, P1, P2 - 1);
End;

{ Resolves the preferred trace width a net class should route at, by finding a
  eRule_MaxMinWidth rule scoped to InNetClass('<name>'). Falls back to the
  board's widest applicable default, then to 8 mil, so a class ALWAYS gets a
  usable nonzero width - emitting no width at all made the autorouter abort on
  load. IPCB_MaxMinWidthConstraint.PreferedWidth is Altium's spelling (one 'r').
  Returned in Coords, matching every other length this script writes. }
{ Extracts the Nth (0-based) InNetClass('...') name from a scope expression, or ''
  when there is no Nth one. Altium scopes are often a compound OR-list such as
  "InNetClass('1300V') or InNetClass('900V') or InNetClass('500V')", and a rule
  like that carries a real constraint (on this board, CHASSIS to the HV classes at
  150 mil) that a single-name parse would silently drop. }
Function NthNetClassName(ScopeExpr : String; N : Integer) : String;
Var
    Rest : String;
    P1, P2, Found : Integer;
Begin
    Result := '';
    Rest := ScopeExpr;
    Found := 0;
    While True Do
    Begin
        P1 := Pos('InNetClass(''', Rest);
        If P1 = 0 Then Exit;
        Rest := Copy(Rest, P1 + Length('InNetClass('''), Length(Rest));
        P2 := Pos('''', Rest);
        If P2 = 0 Then Exit;
        If Found = N Then
        Begin
            Result := Copy(Rest, 1, P2 - 1);
            Exit;
        End;
        Inc(Found);
        Rest := Copy(Rest, P2 + 1, Length(Rest));
    End;
End;

{ The single InNet('...') name in a scope expression, or '' if absent. A rule
  scoped to one NET rather than a class still constrains every class it is paired
  with, so such rules are expanded against the other side's classes. }
Function ExtractNetName(ScopeExpr : String) : String;
Var
    P1, P2 : Integer;
Begin
    Result := '';
    P1 := Pos('InNet(''', ScopeExpr);
    If P1 = 0 Then Exit;
    P1 := P1 + Length('InNet(''');
    P2 := Pos('''', Copy(ScopeExpr, P1, Length(ScopeExpr)));
    If P2 = 0 Then Exit;
    Result := Copy(ScopeExpr, P1, P2 - 1);
End;

Function ClassWidth(Board : IPCB_Board; ClassName : WideString) : TCoord;
Var
    Iter      : IPCB_BoardIterator;
    RuleWidth : IPCB_MaxMinWidthConstraint;
    Found     : Boolean;
    W         : TCoord;
    Kind      : TRuleKind;
Begin
    Result := MilsToCoord(8);
    Found := False;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eRuleObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        RuleWidth := Iter.FirstPCBObject;
        While (RuleWidth <> Nil) And (Not Found) Do
        Begin
            Kind := eRule_Clearance;
            Try Kind := RuleWidth.RuleKind; Except End;
            If Kind = eRule_MaxMinWidth Then
            Begin
                Try
                    If ExtractNetClassName(RuleWidth.Scope1Expression) = ClassName Then
                    Begin
                        W := 0;
                        Try W := RuleWidth.PreferedWidth; Except End;
                        If W <= 0 Then Try W := RuleWidth.MinLimit; Except End;
                        If W > 0 Then
                        Begin
                            Result := W;
                            Found := True;
                        End;
                    End;
                Except End;
            End;
            RuleWidth := Iter.NextPCBObject;
        End;
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
End;

{ Resolves a net class's own (class-vs-itself) clearance, used as the baseline
  for its clearance-matrix row. Prefers the matrix cell for (class, class),
  then any eRule_Clearance scoped to this class, then the board default. }
Function ClassClearance(Board : IPCB_Board; ClassName : WideString) : TCoord;
Var
    Iter      : IPCB_BoardIterator;
    RuleClear : IPCB_ClearanceConstraint;
    Found     : Boolean;
    G         : TCoord;
    Kind      : TRuleKind;
    IsMatrix  : Boolean;
    Infra     : IPCB_ClearanceMatrixInfrastructure;
    E1, E2    : IPCB_MatrixItemEnumerator;
    CellRule  : IPCB_ClearanceConstraint;
Begin
    Result := MilsToCoord(8);
    Found := False;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eRuleObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        RuleClear := Iter.FirstPCBObject;
        While (RuleClear <> Nil) And (Not Found) Do
        Begin
            Kind := eRule_MaxMinWidth;
            Try Kind := RuleClear.RuleKind; Except End;
            If Kind = eRule_Clearance Then
            Begin
                // NOTE: do NOT call GetState_IsMatrix - it is an undeclared
                // identifier in this DelphiScript engine and faults at runtime where
                // Try/Except cannot catch it, taking the whole script down. Detect a
                // matrix rule by whether its ClearanceRules infrastructure can be
                // obtained and yields items.
                IsMatrix := False;
                Infra := Nil;
                Try Infra := RuleClear.ClearanceRules; Except End;
                If Infra <> Nil Then
                    Try IsMatrix := Infra.GetMatrixItemsEnumerator.Next; Except End;
                If IsMatrix Then
                Begin
                    // Look for this class's own diagonal cell in the matrix.
                    Try
                        Infra := RuleClear.ClearanceRules;
                        E1 := Infra.GetMatrixItemsEnumerator;
                        While (E1.Next) And (Not Found) Do
                        Begin
                            If E1.ItemName = ClassName Then
                            Begin
                                E2 := Infra.GetMatrixItemsEnumerator;
                                While (E2.Next) And (Not Found) Do
                                Begin
                                    If E2.ItemName = ClassName Then
                                    Begin
                                        CellRule := Infra.GetCellRule(ClassName, E1.ItemType, ClassName, E2.ItemType);
                                        If CellRule <> Nil Then
                                        Begin
                                            G := CellRule.Gap;
                                            If G > 0 Then
                                            Begin
                                                Result := G;
                                                Found := True;
                                            End;
                                        End;
                                    End;
                                End;
                            End;
                        End;
                    Except End;
                End
                Else
                Begin
                    Try
                        If ExtractNetClassName(RuleClear.Scope1Expression) = ClassName Then
                        Begin
                            G := RuleClear.Gap;
                            If G > 0 Then
                            Begin
                                Result := G;
                                Found := True;
                            End;
                        End;
                    Except End;
                End;
            End;
            RuleClear := Iter.NextPCBObject;
        End;
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
End;

Procedure WriteNetClasses(Board : IPCB_Board);
Var
    ClassIter : IPCB_BoardIterator;
    NetIter   : IPCB_BoardIterator;
    ObjClass  : IPCB_ObjectClass;
    Net       : IPCB_Net;
    FirstC, FirstN : Boolean;
Begin
    OutLines.Add('  "net_classes": [');
    FirstC := True;
    ClassIter := Board.BoardIterator_Create;
    Try
        ClassIter.AddFilter_ObjectSet(MkSet(eClassObject));
        ClassIter.AddFilter_LayerSet(AllLayers);
        ClassIter.AddFilter_Method(eProcessAll);
        ObjClass := ClassIter.FirstPCBObject;
        While ObjClass <> Nil Do
        Begin
            If ObjClass.MemberKind = eClassMemberKind_Net Then
            Begin
                If Not FirstC Then OutLines.Add('    },');
                FirstC := False;

                OutLines.Add('    { "name": ' + JStr(ObjClass.Name) + ',');
                OutLines.Add('      "nets": [');

                FirstN := True;
                NetIter := Board.BoardIterator_Create;
                Try
                    NetIter.AddFilter_ObjectSet(MkSet(eNetObject));
                    NetIter.AddFilter_LayerSet(AllLayers);
                    NetIter.AddFilter_Method(eProcessAll);
                    Net := NetIter.FirstPCBObject;
                    While Net <> Nil Do
                    Begin
                        If ObjClass.IsMember(Net) Then
                        Begin
                            If Not FirstN Then OutLines.Add('        ,');
                            FirstN := False;
                            OutLines.Add('        ' + JStr(Net.Name));
                        End;
                        Net := NetIter.NextPCBObject;
                    End;
                Finally
                    Board.BoardIterator_Destroy(NetIter);
                End;
                OutLines.Add('      ],');
                // Width and self-clearance MUST be emitted: with neither present the
                // FRPCB importer leaves every class at freerouting's built-in default
                // trace width and gives the class's clearance-matrix row no baseline,
                // and the autorouter then aborts immediately on load (observed on a
                // live board - "autorouting is interrupted immediately").
                OutLines.Add('      "width": ' + CoordMils(ClassWidth(Board, ObjClass.Name)) + ',');
                OutLines.Add('      "clearance": ' + CoordMils(ClassClearance(Board, ObjClass.Name)) + ',');
                OutLines.Add('      "min_length": 0, "max_length": 0,');
                // Reference a real via rule so the autorouter is allowed to change
                // layers; without a via the router can only work on one layer.
                OutLines.Add('      "via": ' + JStr(DefaultViaRuleName(Board)));
            End;
            ObjClass := ClassIter.NextPCBObject;
        End;
        If Not FirstC Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(ClassIter);
    End;
    OutLines.Add('  ],');
End;

{..............................................................................}
{ Clearance matrix - THE reason this script/format exists. Iterates every     }
{ eRule_Clearance rule and, where both Scope1Expression and Scope2Expression  }
{ are a bare InNetClass('...') reference, emits a clearance_matrix entry.     }
{ Confirmed API: Rule.Scope1Expression / Scope2Expression / Gap, and the      }
{ FirstPCBObject-direct-into-typed-var narrowing pattern (declaring the loop  }
{ variable AS IPCB_ClearanceConstraint and assigning straight from the        }
{ iterator - a cast from a plain IPCB_Rule variable does NOT narrow in        }
{ DelphiScript and crashes on constraint-only property access).              }
{                                                                              }
{ CONFIRMED from a live export against a real board (165-6689-800, the       }
{ 500V/900V/1300V/HV CLOSE/Power Rail rule matrix this project exists to      }
{ capture): most of this board's clearance rules are compound boolean scope   }
{ expressions (e.g. InNet('CHASSIS') vs IsVia and not InNet('*')), which      }
{ cannot be expressed as a class-vs-class pair and are correctly left out of  }
{ clearance_matrix - FRPCB's clearance_matrix is class-vs-class only by       }
{ design (see docs/frpcb-format.md). The real per-class-pair matrix (the     }
{ screenshot grid) lives in a separate rule object in "matrix mode"           }
{ (detected by whether it exposes matrix items), queried via ClearanceRules/    }
{ GetCellRule below.                                                          }
{                                                                              }
{ CONFIRMED GOTCHA: a matrix rule's rows/columns are NOT all net classes -    }
{ Altium lets individual nets (e.g. 'CHASSIS', 'NetC42_2') sit in the same    }
{ matrix alongside true classes ('500V', 'HV CLOSE'). A live export          }
{ produced entries like {"classes": ["HV CLOSE", "NetC42_2"]} where          }
{ 'NetC42_2' is a plain net, not a class - the FRPCB importer correctly      }
{ rejects these as "unknown class" (they don't exist in net_classes). Fixed   }
{ by filtering matrix items through IsNetClassName below, built from the     }
{ same eClassObject enumeration WriteNetClasses uses, rather than trusting    }
{ every matrix row/column to be a class.                                     }
{..............................................................................}

Function IsNetClassName(Board : IPCB_Board; Name : WideString) : Boolean;
Var
    ClassIter : IPCB_BoardIterator;
    ObjClass  : IPCB_ObjectClass;
    Found     : Boolean;
Begin
    Found := False;
    ClassIter := Board.BoardIterator_Create;
    Try
        ClassIter.AddFilter_ObjectSet(MkSet(eClassObject));
        ClassIter.AddFilter_LayerSet(AllLayers);
        ClassIter.AddFilter_Method(eProcessAll);
        ObjClass := ClassIter.FirstPCBObject;
        While (ObjClass <> Nil) And (Not Found) Do
        Begin
            If (ObjClass.MemberKind = eClassMemberKind_Net) And (ObjClass.Name = Name) Then
                Found := True;
            ObjClass := ClassIter.NextPCBObject;
        End;
    Finally
        Board.BoardIterator_Destroy(ClassIter);
    End;
    Result := Found;
End;

{ Dumps every cell of every matrix-mode clearance rule, from BOTH infrastructures
  (ClearanceRules and SameClearanceRules), including the item type each name was
  reported with. Diagnostic only. }
Procedure WriteMatrixCellDump(Board : IPCB_Board);
Var
    Iter  : IPCB_BoardIterator;
    Rule  : IPCB_ClearanceConstraint;
    Kind  : TRuleKind;
    IsM   : Boolean;
    First : Boolean;
    Pass  : Integer;
    Infra : IPCB_ClearanceMatrixInfrastructure;
    E1, E2 : IPCB_MatrixItemEnumerator;
    Cell  : IPCB_ClearanceConstraint;
    G     : TCoord;
    Src   : String;
Begin
    OutLines.Add('  "_matrix_cells_debug": [');
    First := True;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eRuleObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        Rule := Iter.FirstPCBObject;
        While Rule <> Nil Do
        Begin
            Kind := eRule_MaxMinWidth;
            Try Kind := Rule.RuleKind; Except End;
            If Kind = eRule_Clearance Then
            Begin
                For Pass := 0 To 1 Do
                Begin
                    Infra := Nil;
                    If Pass = 0 Then
                    Begin
                        Src := 'ClearanceRules';
                        Try Infra := Rule.ClearanceRules; Except End;
                    End
                    Else
                    Begin
                        Src := 'SameClearanceRules';
                        Try Infra := Rule.SameClearanceRules; Except End;
                    End;
                    If Infra <> Nil Then
                    Begin
                        Try
                            E1 := Infra.GetMatrixItemsEnumerator;
                            While E1.Next Do
                            Begin
                                E2 := Infra.GetMatrixItemsEnumerator;
                                While E2.Next Do
                                Begin
                                    G := -1;
                                    Try
                                        Cell := Infra.GetCellRule(E1.ItemName, E1.ItemType, E2.ItemName, E2.ItemType);
                                        If Cell <> Nil Then G := Cell.Gap;
                                    Except End;
                                    If Not First Then OutLines.Add('    },');
                                    First := False;
                                    OutLines.Add('    { "src": ' + JStr(Src) +
                                        ', "a": ' + JStr(E1.ItemName) + ', "at": ' + IntToStr(Ord(E1.ItemType)) +
                                        ', "b": ' + JStr(E2.ItemName) + ', "bt": ' + IntToStr(Ord(E2.ItemType)) +
                                        ', "gap": ' + CoordMils(G) + ' ');
                                End;
                            End;
                        Except End;
                    End;
                End;
            End;
            Rule := Iter.NextPCBObject;
        End;
        If Not First Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
    OutLines.Add('  ],');
End;

{ Writes a "_clearance_rules_debug" array: one entry per eRule_Clearance rule with
  its name, both scope expressions, its Gap and whether it is a matrix rule. Used to
  find where Altium keeps the large class-pair clearances. }
Procedure WriteClearanceRuleDump(Board : IPCB_Board);
Var
    Iter  : IPCB_BoardIterator;
    Rule  : IPCB_ClearanceConstraint;
    First : Boolean;
    Kind  : TRuleKind;
    S1, S2, Nm : String;
    G     : TCoord;
    IsM   : Boolean;
Begin
    // Also dump every matrix CELL from both infrastructures a matrix rule exposes
    // (ClearanceRules and SameClearanceRules), so we can see which one actually
    // holds the large per-class-pair values from Altium's Rules grid.
    WriteMatrixCellDump(Board);
    OutLines.Add('  "_clearance_rules_debug": [');
    First := True;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eRuleObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        Rule := Iter.FirstPCBObject;
        While Rule <> Nil Do
        Begin
            Kind := eRule_MaxMinWidth;
            Try Kind := Rule.RuleKind; Except End;
            If Kind = eRule_Clearance Then
            Begin
                Nm := ''; S1 := ''; S2 := ''; G := -1; IsM := False;
                Try Nm := Rule.Name; Except End;
                Try S1 := Rule.Scope1Expression; Except End;
                Try S2 := Rule.Scope2Expression; Except End;
                Try G := Rule.Gap; Except End;
                // GetState_IsMatrix is undeclared in this engine (faults uncatchably);
                // report whether the rule exposes enumerable matrix items instead.
                Try IsM := Rule.ClearanceRules.GetMatrixItemsEnumerator.Next; Except End;
                If Not First Then OutLines.Add('    },');
                First := False;
                OutLines.Add('    { "name": ' + JStr(Nm) + ', "scope1": ' + JStr(S1) +
                    ', "scope2": ' + JStr(S2) + ', "gap": ' + CoordMils(G) +
                    ', "is_matrix": ' + BoolStr(IsM) + ' ');
            End;
            Rule := Iter.NextPCBObject;
        End;
        If Not First Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
    OutLines.Add('  ],');
End;

Procedure WriteClearanceMatrix(Board : IPCB_Board);
Var
    Iter          : IPCB_BoardIterator;
    RuleClear     : IPCB_ClearanceConstraint;
    ClassA, ClassB : String;
    First         : Boolean;
    Scope1, Scope2 : String;
    GapVal        : TCoord;
    RuleKindOk    : Boolean;
    IsMatrixRule  : Boolean;
    NA, NB        : Integer;
    MatrixInfra   : IPCB_ClearanceMatrixInfrastructure;
    ItemEnum1, ItemEnum2 : IPCB_MatrixItemEnumerator;
    Name1, Name2  : WideString;
    CellRule      : IPCB_ClearanceConstraint;
    CellGap       : TCoord;
Begin
    // DIAGNOSTIC: dump every clearance rule verbatim (kind, both scope strings,
    // Gap, matrix flag) so a mismatch between Altium's Rules dialog and what lands
    // in clearance_matrix can be diagnosed from the exported file instead of
    // guessing. Harmless to the importer, which ignores unknown top-level keys.
    WriteClearanceRuleDump(Board);
    OutLines.Add('  "clearance_matrix": [');
    First := True;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eRuleObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        // Direct assignment from FirstPCBObject into the typed IPCB_ClearanceConstraint
        // variable - required for DelphiScript to actually narrow the interface.
        RuleClear := Iter.FirstPCBObject;
        While RuleClear <> Nil Do
        Begin
            RuleKindOk := False;
            Try RuleKindOk := (RuleClear.RuleKind = eRule_Clearance); Except End;
            If RuleKindOk Then
            Begin
                // See the note in ClassClearance: GetState_IsMatrix is undeclared in
                // this engine and faults uncatchably. Treat "has enumerable matrix
                // items" as the matrix test instead.
                IsMatrixRule := False;
                MatrixInfra := Nil;
                Try MatrixInfra := RuleClear.ClearanceRules; Except End;
                If MatrixInfra <> Nil Then
                    Try IsMatrixRule := MatrixInfra.GetMatrixItemsEnumerator.Next; Except End;
                If IsMatrixRule Then
                Begin
                    Try
                        ItemEnum1 := MatrixInfra.GetMatrixItemsEnumerator;
                        While ItemEnum1.Next Do
                        Begin
                            Name1 := ItemEnum1.ItemName;
                            If IsNetClassName(Board, Name1) Then
                            Begin
                                ItemEnum2 := MatrixInfra.GetMatrixItemsEnumerator;
                                While ItemEnum2.Next Do
                                Begin
                                    Name2 := ItemEnum2.ItemName;
                                    If (Name1 < Name2) And IsNetClassName(Board, Name2) Then // emit each unordered class pair once
                                    Begin
                                        CellGap := -1;
                                        Try
                                            CellRule := MatrixInfra.GetCellRule(Name1, ItemEnum1.ItemType, Name2, ItemEnum2.ItemType);
                                            If CellRule <> Nil Then CellGap := CellRule.Gap;
                                        Except End;
                                        If CellGap >= 0 Then
                                        Begin
                                            If Not First Then OutLines.Add('    },');
                                            First := False;
                                            OutLines.Add('    { "classes": [' + JStr(Name1) + ', ' + JStr(Name2) +
                                                '], "clearance": ' + CoordMils(CellGap) + ' ');
                                        End;
                                    End;
                                End;
                            End;
                        End;
                    Except
                        // PROBE path failed outright (wrong property/method name on this
                        // Altium build) - fall through; this rule simply contributes
                        // nothing to clearance_matrix rather than crashing the export.
                    End;
                End
                Else
                Begin
                    // Non-matrix clearance rule. Both scopes are parsed as OR-lists of
                    // InNetClass(...) and expanded into every implied class pair, because
                    // real boards put genuine constraints in compound scopes - on this
                    // board the only large clearance (150 mil) lives in
                    //   InNet('CHASSIS') vs InNetClass('1300V') or ... or InNetClass('HV CLOSE')
                    // which a single-name parse dropped entirely. A side scoped to a bare
                    // InNet(...) contributes its NET name, which FRPCB cannot express as a
                    // class pair; such a rule is expanded against the other side's classes
                    // only if that net is itself a class name, and otherwise skipped.
                    Scope1 := '';
                    Scope2 := '';
                    GapVal := 0;
                    Try Scope1 := RuleClear.Scope1Expression; Except End;
                    Try Scope2 := RuleClear.Scope2Expression; Except End;
                    Try GapVal := RuleClear.Gap; Except End;

                    For NA := 0 To 15 Do
                    Begin
                        ClassA := NthNetClassName(Scope1, NA);
                        If ClassA = '' Then
                        Begin
                            // No class on this side; fall back to a net name that happens
                            // to also be a class (Altium allows both to share a name).
                            If NA > 0 Then Break;
                            ClassA := ExtractNetName(Scope1);
                            If (ClassA = '') Or (Not IsNetClassName(Board, ClassA)) Then Break;
                        End;
                        For NB := 0 To 15 Do
                        Begin
                            ClassB := NthNetClassName(Scope2, NB);
                            If ClassB = '' Then
                            Begin
                                If NB > 0 Then Break;
                                ClassB := ExtractNetName(Scope2);
                                If (ClassB = '') Or (Not IsNetClassName(Board, ClassB)) Then Break;
                            End;
                            If ClassA <> ClassB Then
                            Begin
                                If Not First Then OutLines.Add('    },');
                                First := False;
                                OutLines.Add('    { "classes": [' + JStr(ClassA) + ', ' + JStr(ClassB) +
                                    '], "clearance": ' + CoordMils(GapVal) + ' ');
                            End;
                        End;
                    End;
                End;
            End;
            RuleClear := Iter.NextPCBObject;
        End;
        If Not First Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
    OutLines.Add('  ],');
End;


{ its net, layer and boundary polygon. On the freerouting side these become    }
{ ConductionArea items (BasicBoard.insert_conduction_area), which is what lets }
{ plane-connected nets (GND, power rails) count as routed instead of showing   }
{ up as ratsnest lines - the single largest source of the ~1600 "incomplete    }
{ connections" seen on a live board before pours were exported.                }
{                                                                              }
{ Exports the POURED result rather than each polygon's defining boundary: a     }
{ poured IPCB_Polygon owns IPCB_Region children whose MainContour is the real  }
{ copper Altium rendered, already clipped to the board outline and to the      }
{ actual clearance rules, and already resolved for pour-over priority and      }
{ removed islands. That is why the exported shapes differ from (and are more   }
{ fragmented than) the polygons drawn in the PCB editor.                       }
{..............................................................................}

Procedure WritePours(Board : IPCB_Board);
Var
    Iter     : IPCB_BoardIterator;
    Poly     : IPCB_Polygon;
    GrpIter  : IPCB_GroupIterator;
    Region   : IPCB_Region;
    Contour  : IPCB_Contour;
    I        : Integer;
    First, FirstPt : Boolean;
    NetName, LayerNm : String;
    Poured, AnyRegion : Boolean;
    Seg, NextSeg : TPolySegment;
    NextI, Steps, S : Integer;
    Angle, Rad, StartAngle, EndAngle, Span : Double;
Begin
    OutLines.Add('  "pours": [');
    First := True;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(ePolyObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        Poly := Iter.FirstPCBObject;
        While Poly <> Nil Do
        Begin
            NetName := '';
            Try If Poly.Net <> Nil Then NetName := Poly.Net.Name; Except End;
            // NOTE: deliberately NOT gated on Poly.Poured. A polygon whose copper is
            // shelved (or not yet repoured) reports Poured = False and owns no region
            // children, and requiring Poured exported zero pours on a board whose
            // pours were shelved - which silently regressed every plane net back to
            // ratsnest. Instead: use the poured regions when they exist (accurate,
            // already clipped), and fall back to the polygon's own boundary when they
            // do not (approximate, unclipped, but far better than no copper at all).
            If NetName <> '' Then
            Begin
                LayerNm := Board.LayerName(Poly.Layer);
                // Emit the POURED RESULT, not the polygon's defining boundary.
                // Altium's pour engine has already resolved everything that makes
                // the rendered copper differ from the outline the user drew:
                // clearance to pads/tracks/other nets, the board outline, cutouts,
                // pour-over/priority stacking between overlapping polygons, and
                // removed islands/necks. Those children are IPCB_Region objects
                // whose MainContour is a flat, already-tessellated point list
                // (IPCB_Contour: Count, x[I], y[I]) - so this needs no arc
                // handling and no clipping of our own, and it is accurate to the
                // real clearance rules rather than an approximation of them.
                AnyRegion := False;
                GrpIter := Poly.GroupIterator_Create;
                Try
                    GrpIter.AddFilter_ObjectSet(MkSet(eRegionObject));
                    Region := GrpIter.FirstPCBObject;
                    While Region <> Nil Do
                    Begin
                        Contour := Nil;
                        Try Contour := Region.MainContour; Except End;
                        If (Contour <> Nil) And (Contour.Count >= 3) Then
                        Begin
                            If Not First Then OutLines.Add('    },');
                            First := False;
                            OutLines.Add('    { "net": ' + JStr(NetName) + ', "layer": ' +
                                JStr(LayerNm) + ',');
                            OutLines.Add('      "polygon": [');
                            FirstPt := True;
                            For I := 0 To Contour.Count - 1 Do
                            Begin
                                If Not FirstPt Then OutLines.Add('        ,');
                                FirstPt := False;
                                OutLines.Add('        [' + CoordMils(Contour.x[I]) +
                                    ', ' + CoordMils(Contour.y[I]) + ']');
                            End;
                            OutLines.Add('      ]');
                            AnyRegion := True;
                        End;
                        Region := GrpIter.NextPCBObject;
                    End;
                Finally
                    Poly.GroupIterator_Destroy(GrpIter);
                End;

                // Fallback: no poured regions (copper shelved / not repoured), so
                // emit the polygon's own drawn boundary. Unclipped and ignoring
                // clearance, but it still gives plane nets their copper; repour in
                // Altium before exporting to get the accurate shapes.
                If Not AnyRegion Then
                Begin
                    If Poly.PointCount >= 3 Then
                    Begin
                        If Not First Then OutLines.Add('    },');
                        First := False;
                        OutLines.Add('    { "net": ' + JStr(NetName) + ', "layer": ' +
                            JStr(LayerNm) + ',');
                        OutLines.Add('      "polygon": [');
                        FirstPt := True;
                        For I := 0 To Poly.PointCount - 1 Do
                        Begin
                            Seg := Poly.ShapeSegments[I];
                            If Not FirstPt Then OutLines.Add('        ,');
                            FirstPt := False;
                            OutLines.Add('        [' + CoordMils(Seg.vx) + ', ' + CoordMils(Seg.vy) + ']');
                            If Seg.Kind = ePolySegmentArc Then
                            Begin
                                Rad := Seg.Radius;
                                NextI := I + 1;
                                If NextI > Poly.PointCount - 1 Then NextI := 0;
                                NextSeg := Poly.ShapeSegments[NextI];
                                StartAngle := ArcTan2(1.0 * Seg.vy - Seg.cy, 1.0 * Seg.vx - Seg.cx) * (180.0 / 3.14159265);
                                EndAngle := ArcTan2(1.0 * NextSeg.vy - Seg.cy, 1.0 * NextSeg.vx - Seg.cx) * (180.0 / 3.14159265);
                                Span := EndAngle - StartAngle;
                                While Span <= -180 Do Span := Span + 360;
                                While Span > 180 Do Span := Span - 360;
                                Steps := Round(Abs(Span) / 12);
                                If Steps < 1 Then Steps := 1;
                                For S := 1 To Steps - 1 Do
                                Begin
                                    Angle := (StartAngle + Span * (S / Steps)) * (3.14159265 / 180.0);
                                    OutLines.Add('        ,[' + CoordMils(Seg.cx + Round(Rad * Cos(Angle))) +
                                        ', ' + CoordMils(Seg.cy + Round(Rad * Sin(Angle))) + ']');
                                End;
                            End;
                        End;
                        OutLines.Add('      ]');
                    End;
                End;
            End;
            Poly := Iter.NextPCBObject;
        End;
        If Not First Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
    OutLines.Add('  ],');
End;

{..............................................................................}
{ Vias (existing, placed) and routed tracks - the "routing" block, for        }
{ protecting already-routed copper from the autorouter or reimporting a      }
{ partially-hand-routed board.                                                }
{..............................................................................}

Procedure WriteRouting(Board : IPCB_Board);
Var
    Iter   : IPCB_BoardIterator;
    Track  : IPCB_Track;
    Via    : IPCB_Via;
    First  : Boolean;
Begin
    OutLines.Add('  "routing": { "wires": [');
    First := True;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eTrackObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        Track := Iter.FirstPCBObject;
        While Track <> Nil Do
        Begin
            If (Track.Layer <> eKeepOutLayer) And (Track.Net <> Nil) Then
            Begin
                If Not First Then OutLines.Add('    },');
                First := False;
                OutLines.Add('    { "net": ' + JStr(Track.Net.Name) + ', "layer": ' +
                    // Board.LayerName, not Layer2String, so this matches
                    // board.layers' names exactly - see the note on
                    // WritePadstacks' TopName/BotName for why a mismatched
                    // layer-name string silently drops data on import.
                    JStr(Board.LayerName(Track.Layer)) + ', "width": ' + CoordMils(Track.Width) + ',');
                OutLines.Add('      "path": [ [' + CoordMils(Track.X1) + ', ' + CoordMils(Track.Y1) +
                    '], [' + CoordMils(Track.X2) + ', ' + CoordMils(Track.Y2) + '] ],');
                OutLines.Add('      "fixed": "unfixed" ');
            End;
            Track := Iter.NextPCBObject;
        End;
        If Not First Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
    OutLines.Add('  ], "vias": [');

    First := True;
    Iter := Board.BoardIterator_Create;
    Try
        Iter.AddFilter_ObjectSet(MkSet(eViaObject));
        Iter.AddFilter_LayerSet(AllLayers);
        Iter.AddFilter_Method(eProcessAll);
        Via := Iter.FirstPCBObject;
        While Via <> Nil Do
        Begin
            If Via.Net <> Nil Then
            Begin
                If Not First Then OutLines.Add('    },');
                First := False;
                OutLines.Add('    { "net": ' + JStr(Via.Net.Name) + ', "padstack": ' +
                    JStr('via_' + CoordMils(Via.Size) + '_' + CoordMils(Via.HoleSize)) + ',');
                OutLines.Add('      "x": ' + CoordMils(Via.x) + ', "y": ' + CoordMils(Via.y) + ',');
                OutLines.Add('      "fixed": "unfixed" ');
            End;
            Via := Iter.NextPCBObject;
        End;
        If Not First Then OutLines.Add('    }');
    Finally
        Board.BoardIterator_Destroy(Iter);
    End;
    OutLines.Add('  ] }');
End;

{..............................................................................}
{ Entry point                                                                  }
{..............................................................................}

Procedure ExportFrpcb;
Var
    Board       : IPCB_Board;
    OutFileName : TPCBString;
Begin
    Board := PCBServer.GetCurrentPCBBoard;
    If Board = Nil Then
    Begin
        ShowMessage('No PCB document is open.');
        Exit;
    End;

    OutLines := TStringList.Create;
    Try
        OutLines.Add('{');
        OutLines.Add('  "frpcb_version": 1,');
        OutLines.Add('  "unit": "mil",');
        WriteLayers(Board);
        WriteBoardOutline(Board);
        WriteKeepouts(Board);
        WritePadstacks(Board);
        WriteComponents(Board);
        WriteNets(Board);
        WriteViaRules(Board);
        WriteNetClasses(Board);
        WriteClearanceMatrix(Board);
        WritePours(Board);
        WriteRouting(Board);
        OutLines.Add('}');

        OutFileName := ChangeFileExt(Board.FileName, '.frpcb.json');
        OutLines.SaveToFile(OutFileName);
        ShowMessage('Wrote ' + OutFileName);
    Finally
        OutLines.Free;
    End;
End;
{..............................................................................}
