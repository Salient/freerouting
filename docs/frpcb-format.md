# FRPCB interchange format (`.frpcb.json`)

Custom JSON interchange format for round-tripping a PCB design between Altium
Designer and freerouting, replacing the Specctra DSN/session pipeline.

## Why

Altium's Specctra DSN exporter drops information freerouting can otherwise
represent — most importantly, pairwise net-class clearance rules (Altium's
"Rules" matrix, e.g. `500V`↔`900V` = 25mil) are exported as class membership
only, with no `class_class` clearance scope. FRPCB is emitted directly by a
DelphiScript reading Altium's own `PCB_Rule` objects, so it captures exactly
what the internal freerouting model (`eu.mihosoft.freerouting.rules`,
`eu.mihosoft.freerouting.board`) can use — no more, no less. See
"Deliberately excluded" below for what neither side can represent.

## Top-level structure

```jsonc
{
  "frpcb_version": 1,
  "unit": "mil",                 // all length values in this file use this unit
  "board": { ... },              // outline, layers, keepouts
  "padstacks": [ ... ],          // shared padstack library
  "components": [ ... ],         // placed components/footprints
  "nets": [ ... ],               // net -> pins, per-net rule overrides
  "net_classes": [ ... ],        // named rule classes + membership
  "clearance_matrix": [ ... ],   // pairwise clearance between classes
  "vias": [ ... ],               // named via rules (padstack the router may place)
  "pours": [ ... ],              // copper pours / planes, as conduction areas
  "routing": { "wires": [...], "vias": [...] }  // pre-existing routed geometry (optional)
}
```

`unit` is always `"mil"` or `"mm"`. Every length field in the file (widths,
clearances, coordinates, drill sizes) is in this unit — no separate
resolution/scale-factor step, unlike Specctra DSN. The importer converts once,
at load time, using the same `dsn_to_board`-style scale factor freerouting
already uses internally.

## `board`

```jsonc
"board": {
  "outline": [ [ [x,y], [x,y], ... ], ... ],   // one or more closed polygons (islands OK)
  "layers": [
    { "name": "TopLayer", "signal": true },
    { "name": "L2_GND",   "signal": false },
    ...
  ],
  "keepouts": [
    { "type": "keepout" | "via_keepout" | "place_keepout",
      "layers": ["TopLayer", ...] | "all",
      "polygon": [ [x,y], ... ] }
  ],
  "outline_clearance": 20.0          // copper-to-board-edge clearance; 0/absent = default
}
```

`outline_clearance` is the clearance copper must keep from the board edge. The
importer gives the outline its own clearance-matrix class set to this value
against every other class, via `create_board`'s outline-clearance-class
parameter. Without it the outline shares the default class and the autorouter
will route right up to the board edge. Exported from Altium's board-outline
clearance rule, which is a different rule kind from ordinary copper clearance
and is matched by numeric `RuleKind` because DelphiScript does not expose a
named constant for it.

Maps directly to `BoardOutline` (multiple `PolylineShape`s), `LayerStructure`
(`Layer{name, is_signal}`), and the three keepout `Item` subclasses
(`ObstacleArea` / `ViaObstacleArea` / `ComponentObstacleArea`). Per the
internal model, the outline itself cannot vary per layer — only keepouts can.

## `padstacks`

```jsonc
"padstacks": [
  {
    "name": "Pad193",
    "drill": 12.0,               // omit or null for a through-hole-less (SMD) pad
    "shapes": {                  // per-layer copper shape; omit a layer = no copper there
      "TopLayer": { "type": "circle", "diameter": 25.0 },
      "BottomLayer": { "type": "rect", "width": 25.0, "height": 30.0 },
      "L2_GND": { "type": "polygon", "points": [[x,y], ...] }
    }
  }
]
```

A padstack with an empty/absent `shapes` object is a **shapeless padstack**
(mounting hole, NPTH, fiducial) — the importer registers it exactly as the
DSN parser now does (`Library.read_padstack_scope`'s null-shape-array path),
so pins referencing it resolve without the `NegativeArraySizeException` that
motivated that fix. `drill` is carried in the file for documentation/fab
purposes only — per the model survey, freerouting's `Padstack` object has no
queryable drill field, so it does not affect routing.

### Why shapeless-padstack tolerance still matters even though FRPCB isn't Specctra

Investigating `test3.dsn`'s "has no shape" warnings during this project traced
the cause to an **Altium Specctra-exporter defect**, not a geometry the DSN
format can't express: five ordinary rectangular SMD pads (e.g. `Pad193`, pin 1
of a `TI_DSE0006A` footprint whose sibling pins export a normal
`(shape (rect ...))`) were emitted as a bare `(padstack Pad193\n)` with no
shape sub-scope at all. All 266 padstacks in that file use nothing but the
plain `rect` primitive — there was no unsupported/custom geometry (rounded
rect, octagon, arc, polygon) anywhere in the file, so "Specctra can't express
this shape" was not the mechanism.

Because the defect is in Altium's *exporter*, not in Specctra's shape
vocabulary, the fix belongs in the capture script that produces FRPCB, not in
this format's design: the DelphiScript should read each pad's geometry
directly from Altium's live `IPCB_Pad` object model (`TopXSize`/`TopYSize`/
`TopShape`/`Rotation`/hole size), never from an intermediate Specctra export,
so this class of pin-1-loses-its-shape omission cannot recur. FRPCB's
shapeless-padstack handling is kept anyway — as defense in depth for the
genuine NPTH/mounting-hole/fiducial case, and in case a future capture path
ever again passes through a lossy export step.

## `components`

```jsonc
"components": [
  {
    "name": "U18A",
    "package": "SOIC8",
    "x": 1234.5, "y": 678.9,
    "rotation": 90.0,
    "side": "top" | "bottom",
    "fixed": true,
    "pins": [
      { "pin": "1", "net": "GND", "padstack": "Pad193", "x": 1230.0, "y": 675.0 }
    ]
  }
]
```

Maps to `board.Component` (location/rotation/side/fixed) and its `Package`'s
pin list; each pin resolves its padstack by name against the `padstacks`
array, mirroring `Network.insert_component`. A pin whose padstack is
shapeless is skipped exactly as `a9878d08e` already handles for DSN.

## `nets`

```jsonc
"nets": [
  {
    "name": "LP DET A SENSE TOPB",
    "pins": ["R28B-1", "C21B-2", ...],       // "component-pin" references
    "rule": { "width": 8.0, "clearance": 5.0 }   // optional per-net override
  }
]
```

`rule.width`/`rule.clearance` map to the same per-net `Rule.WidthRule` /
`Rule.ClearanceRule` handling in `Network.read_net_scope` — including the
class-dedup-by-value fix (nets sharing an identical clearance value share one
internal `NetClass` instead of minting one per net).

## `net_classes`

```jsonc
"net_classes": [
  {
    "name": "500V",
    "nets": ["NetC42_2"],
    "width": 8.0,
    "clearance": 75.0,          // self-clearance (500V <-> 500V)
    "via": "std_via",
    "min_length": 0, "max_length": 0,
    "active_layers": ["TopLayer", "BottomLayer"]
  }
]
```

Maps 1:1 to `rules.NetClass` — width/clearance/via-rule/min-max-length/
active-layers/pull-tight/shove-fixed are all representable per the model
survey. `clearance` here is the class's **self**-clearance; cross-class
values go in `clearance_matrix`.

## `clearance_matrix` — the gap this format exists to close

```jsonc
"clearance_matrix": [
  { "classes": ["500V", "900V"], "clearance": 25.0 },
  { "classes": ["500V", "1300V"], "clearance": 75.0 },
  { "classes": ["HV CLOSE", "900V"], "clearance": 100.0 },
  { "classes": ["Power Rail", "500V"], "clearance": 75.0 }
]
```

Directly populates `ClearanceMatrix` cells for the named class pair, on both
`(i,j)` and `(j,i)` — the importer always writes both directions, since the
internal model does not structurally enforce symmetry (per the model survey,
every existing DSN writer sets both cells by convention; FRPCB's importer
does the same rather than relying on the matrix to symmetrize itself). This
is exactly the Altium "Rules" grid from the screenshot that Specctra DSN
export currently drops on the floor.

## `vias`

```jsonc
"vias": [
  { "name": "std_via", "padstack": "ViaPad_10_20", "clearance_class": "default" }
]
```

Maps to `ViaRule`/`ViaInfo` (name, padstack, clearance class). Referenced by
name from `net_classes[].via`.


## `pours`

```jsonc
"pours": [
  { "net": "GND", "layer": "GND", "polygon": [ [x,y], ... ] }
]
```

Each entry is one copper pour / plane, imported as a freerouting
`ConductionArea` (`BasicBoard.insert_conduction_area`) belonging to its net and
inserted as a non-obstacle for that net. This is what lets plane-connected nets
count as routed: without pours, every pin on a plane net shows up as an
unrouted ratsnest line (on a real 438-net board this accounted for the large
majority of ~1600 reported "incomplete connections").

Exported from Altium's `IPCB_Polygon` objects, whose `PointCount` /
`ShapeSegments[I]` boundary is the same `TPolySegment` structure the board
outline uses — so arcs are tessellated identically, including the
arc-direction handling described under `board.outline`.

## `routing` (optional — for reimporting after autorouting, or for pre-routed traces)

```jsonc
"routing": {
  "wires": [
    { "net": "GND", "layer": "TopLayer", "width": 8.0,
      "path": [[x,y],[x,y],...], "fixed": "unfixed"|"shove_fixed"|"user_fixed"|"system_fixed" }
  ],
  "vias": [
    { "net": "GND", "padstack": "std_via", "x": 100.0, "y": 200.0, "fixed": "unfixed" }
  ]
}
```

Maps to `board.insert_trace_without_cleaning` / `board.insert_via`, tagged
with `FixedState` — this is the path for both (a) pre-existing routed traces
Altium already has that should be protected from the autorouter, and (b) the
reverse direction: freerouting emits this same `routing` block standalone
after autorouting, for a script on the Altium side to place tracks/vias via
`PCBServer.PCBObjectFactory(eTrackObject, ...)`.

## Deliberately excluded (not representable by either side)

Per the internal-model survey, these are absent from freerouting's rules
engine entirely — capturing them from Altium would be lossy busywork, so
FRPCB does not attempt to carry them:

- **Differential pairs** — no representation anywhere in
  `eu.mihosoft.freerouting.rules`.
- **Impedance targets** — only literal trace width is stored; no
  width-to-impedance mapping.
- **Pairwise/relative length matching (skew)** between two specific nets —
  only an absolute per-net-class min/max length exists
  (`NetClass.minimum_trace_length`/`maximum_trace_length`), enforced as a UI
  highlight only, not by the autorouter engine.
- **Stackup material/thickness/dielectric constant** — `Layer` is just
  `{name, is_signal}`.
- **Drill diameter as a queried field** — carried in FRPCB for
  documentation/fab purposes, but freerouting's `Padstack` model has no
  drill-size field for the router to consult.

## Versioning

`frpcb_version` is an integer, bumped on any breaking change to field
meaning (not on additive new optional fields). The importer rejects a file
whose major version it doesn't recognize rather than guessing.
