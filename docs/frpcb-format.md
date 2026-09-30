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

The exporter also writes two top-level keys not listed above,
`_board_outline_rules` and `_dropped_clearance_rules`. These are diagnostic
only — `FrpcbFile` never reads them, and the importer ignores unknown
top-level keys entirely, so their presence costs nothing on import. See
"Diagnostic keys" under `clearance_matrix` below.

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

Each layer's shape has one of exactly three `type`s — `circle` (`diameter`),
`rect` (`width`/`height`), or `polygon` (`points`, a convex ring of `[x,y]`
offsets from the pad/via origin) — matching the only three cases
`FrpcbFile.read_shape` recognizes; anything else is logged and that layer's
shape is dropped. There is no `octagon` or `oval` type. Altium's octagonal
pads, and its rounded pads whose two extents differ (a "stadium", not a
circle — round only when both extents are equal), are exported as an
explicit `polygon`: an 8-point inscribed octagon, or two 6-point semicircular
caps joined into a capsule, both already rotated (`WriteRoundedPolygon` in
`ExportFrpcb.pas`, added in commit `fd95cbfe`). Before that commit, octagonal
pads were written with the literal `type` value `"octagon"`, which
`read_shape` rejects as unknown — the pad ended up with no copper at all and
its pin was silently dropped from the net.

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

### The via padstack naming contract — get this wrong and vias vanish silently

`ExportFrpcb.pas` does not give vias a human-chosen padstack name. It
synthesizes one from geometry: `'via_' + CoordMils(Via.Size) + '_' +
CoordMils(Via.HoleSize)` (outer size, then hole size, both in mils). This
exact expression is repeated verbatim at every place a via needs to name its
padstack: `WritePadstacks` (which defines the padstack entry itself, one per
distinct size/hole pair actually used on the board), `WriteViaRules` (the
named entries under `vias`, below), and `WriteRouting` (each routed via under
`routing.vias`). All three must produce the identical string for a given via,
because `FrpcbFile` resolves `"padstack"` by exact name lookup
(`read_via`/`read_routed_via` against the `padstacks` map built by
`read_padstack`) — there is no fallback and no geometric matching.

If a referenced padstack name is not in the `padstacks` array — e.g. because
a hand-edited or partially-regenerated file added a routed via without a
matching padstack entry — `read_routed_via` logs a warning
(`"references unknown padstack ..., skipping it"`) and drops that one via.
The import still succeeds; nothing else fails. This is the confirmed failure
mode noted in `ExportFrpcb.pas`'s `WritePadstacks`: before it emitted a
padstack entry per via size/hole combination, a live board's export silently
lost about 1000 vias this way, discoverable only by noticing the warning
count or the missing copper, not by any import error. The same exact-name
requirement applies to a component pin's `"padstack"` field
(`read_component`) and to a named via rule's `"padstack"` field (`read_via`).

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

`package` is documentation only, unlike Specctra DSN's library `image` scope.
DSN parses one `image` per distinct footprint and has every placement of it
share that one `Package`; FRPCB's importer deliberately does *not* do this —
`read_component` builds and registers a fresh `Package` per component,
keyed by `package + "#" + component name`, never shared across components
even when they quote the same `package` string. Each component's own `pins`
array is the sole authority for that instance's pin/padstack/net data, which
matters because two components can share a nominal package name while
differing per pin (e.g. fiducials that all say `"FIDUCIAL_200X100"` but carry
different per-instance padstacks). An earlier version cached `Package` by
`package` name alone; the second component with a given name then silently
reused the first one's pin/padstack data.

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
values go in `clearance_matrix`. `min_length`/`max_length` are only applied
when present and greater than 0. `via` is resolved by name against the
`vias` array; an unknown name is logged and the class is left without a via
rule.

Omitting `active_layers` leaves every layer active (the class's default).
Including it switches the class to an explicit allow-list: every layer
starts inactive and only the named ones are turned on, so an unknown layer
name in the array is logged and simply contributes nothing — it does not
fall back to "all layers".

## `clearance_matrix` — the gap this format exists to close

```jsonc
"clearance_matrix": [
  { "classes": ["500V", "900V"], "clearance": 75.0 },
  { "classes": ["500V", "1300V"], "clearance": 150.0 },
  { "classes": ["1300V", "HV CLOSE"], "clearance": 100.0 },
  { "classes": ["Power Rail", "default"], "clearance": 10.0 }
]
```

Directly populates `ClearanceMatrix` cells for the named class pair, on both
`(i,j)` and `(j,i)` — the importer always writes both directions, since the
internal model does not structurally enforce symmetry (per the model survey,
every existing DSN writer sets both cells by convention; FRPCB's importer
does the same rather than relying on the matrix to symmetrize itself). This
is exactly the Altium "Rules" grid that Specctra DSN export drops on the floor.

### Prefer `Constraints.xml`: this array is not where Altium keeps the matrix

**On an Altium project that uses the Constraint Manager, do not trust this
array.** The DelphiScript can only reach the legacy `PCB_Rule` objects, and on
such a project those hold a stale default matrix, not the design's real rules.
Measured on the reference board, every high-voltage value in this array was
understated by between 2.5× and 25×:

| pair | real | this array said |
|---|---|---|
| (default) ↔ `1300V` | **200 mil** | 8 |
| (default) ↔ `900V` | **150 mil** | 8 |
| `500V` ↔ `1300V` | **150 mil** | 10 |
| `1300V` ↔ `HV CLOSE` | **100 mil** | 10 |
| `500V` ↔ `900V` | **75 mil** | 10 |
| `500V`/`900V`/`1300V` self | **25 mil** | 10 |

The real matrix lives in `Constraints.xml`, which Altium keeps beside the
`.PcbDoc`, as `CCMFromScope → CCMToScope → CCMConstraint[GAP]` keyed by GUID
against `CNetClass`. Pass it with `-dc`, or leave a copy next to the design file
and the importer finds it:

    java -jar freerouting-executable.jar -de board.frpcb.json -dc Constraints.xml

A supplied `Constraints.xml` **replaces** this array and the
`net_classes[].clearance` diagonals. See
`designforms/frpcb/AltiumConstraintsFile.java`. Two cautions:

- `Constraints.xml` is rewritten when constraints or the project are saved, not
  on every `.PcbDoc` save, so it can lag the board. The importer warns when it is
  older than the design file.
- **Neither store alone is complete.** Every clearance in `Constraints.xml` is a
  class-pair matrix cell, so a rule scoped to a single *net* — on the reference
  board, `CHASSIS` to the HV classes at 150 mil — exists only as a legacy
  `PCB_Rule` and still has to come from the DelphiScript via `nets[].rule`.

### Class membership, and why a correct matrix is not sufficient

A net is routinely in several Altium classes at once: on the reference board all
438 nets are in `All Nets` as well as, for two of them, `1300V`. freerouting's
`Net` holds exactly one `NetClass`, so the importer assigns the **smallest**
class containing each net (`FrpcbFile.assign_net_classes`). Assigning in file
order instead let `All Nets` overwrite `1300V`, which left the HV classes with no
members and made the matrix inert whatever its values were.

For the same reason a class's clearance row is bound with both
`NetClass.set_trace_clearance_class` *and*
`default_item_clearance_classes.set_all`: every inserted item reads the latter,
so setting only the former leaves all copper on the default class.

### Diagnostic keys: `_board_outline_rules` and `_dropped_clearance_rules`

The exporter writes two additional top-level arrays purely so a human can see
what it could not carry into the format proper. `FrpcbFile` never reads
either one — unknown top-level keys are simply ignored on import — so they
are safe to leave in a file, and safe to strip.

```jsonc
"_board_outline_rules": [
  { "rule": "BoardOutlineClearance", "scope": "All", "clearance": 10.0,
    "priority": 4, "enabled": true },
  { "rule": "ChassisOutline", "scope": "(Not InNet('CHASSIS'))",
    "clearance": 100.0, "priority": 2, "enabled": true }
],
"_dropped_clearance_rules": [
  { "rule": "PadToPad", "scope1": "IsPad", "scope2": "IsPad",
    "clearance": 7.8,
    "reason": "no net class on either side; freerouting has no object-type clearance scope" }
]
```

`_board_outline_rules` lists **every** `PCB_Rule` of Altium's board-outline-
clearance kind found on the board, whether or not it ended up governing
`board.outline_clearance` — `rule`/`scope` are the rule's name and
`Scope1Expression`, `clearance` its `Gap` in the file's unit, `priority` its
Altium rule priority (1 highest), and `enabled` its `Rule.Enabled` flag
(defaulting to `true` if the property can't be read). This is the audit
trail for the single value picked for `board.outline_clearance`: since FRPCB
can only carry one board-edge clearance, the exporter has to pick exactly
one governing rule among possibly several overlapping ones, and does so by
Altium's own conflict-resolution rule — the highest-priority *enabled* rule
whose scope actually covers general copper (excluding a scope that names one
specific net, or a non-copper text scope) — rather than by simply taking the
largest `Gap` among all matching rules. On the reference board four enabled
rules overlapped at priorities 1–4 with gaps of 0, 10, 10 and 100 mil;
getting this selection wrong (by scope alone, by gap alone, or by an
incomplete priority rule) was the source of several iterations of a several-
hundred-violation over- or under-report before landing on the current
scope-and-priority logic.

`_dropped_clearance_rules` lists enabled `eRule_Clearance` rules this format
cannot represent at all: a nonzero `Gap` whose two scopes are neither a
net-scoped rule (which becomes a synthetic single-net class instead, see
above) nor the board's own default `All`/`All` scope. `reason` is currently
always the same string, `"no net class on either side; freerouting has no
object-type clearance scope"` — object-type scopes such as pad-to-pad or
via-to-pad have no representation anywhere in
`eu.mihosoft.freerouting.rules`, which is the same limitation the
`OBJECTCLEARANCES` entry under "Deliberately excluded" describes for
`Constraints.xml`.

## `vias`

```jsonc
"vias": [
  { "name": "std_via", "padstack": "ViaPad_10_20", "clearance_class": "default" }
]
```

Maps to `ViaRule`/`ViaInfo` (name, padstack, clearance class). Referenced by
name from `net_classes[].via`. `padstack` is resolved by exact name against
`padstacks` — see "The via padstack naming contract" above; an unresolved
name causes the whole via entry to be skipped. `clearance_class` is
optional: omitted, or a name not found in the clearance matrix, falls back
to `BoardRules.default_clearance_class()` (the latter case also logs a
warning) rather than failing the via.


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

`fixed` is matched case-insensitively against the four values shown above;
anything else (including a missing field) is treated as `"unfixed"` rather
than rejected. `routing.vias[].padstack` is resolved the same way as
`vias[].padstack` — by exact name against `padstacks` — and is subject to
the same silent-skip-on-mismatch failure mode described in "The via padstack
naming contract" under `padstacks` above.

### `-rm`: what to do with the routing already in the file

The exporter cannot know which of these a given run wants, and producing an
export needs a live Altium session driven by hand, so the fixed state is
reinterpreted at *import* time and one exported file serves all three modes:

| `-rm` | effect |
|---|---|
| `reroute` (default) | every wire and via becomes `unfixed`; the autorouter may rip up and reroute the whole board |
| `finish` | everything becomes `user_fixed`; the autorouter only completes connections that are still incomplete |
| `verify` | import and check clearances, route nothing; a violation report goes to `-do`. Exit 0 clean, 2 violations found, 1 could not check |

Run `verify` before `finish`: `finish` makes existing copper unfixable, so any
clearance violation already present becomes one the router is not allowed to
repair.

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
- **Per-object-type clearance** (`OBJECTCLEARANCES` in `Constraints.xml`).
  Altium refines a single clearance rule by the *kind* of object on each side;
  the reference board carries, among others:

  | pair | Altium | vs. the rule's generic 8 mil |
  |---|---|---|
  | SMD pad ↔ SMD pad | 7.8 mil | **looser** |
  | through-hole pad ↔ via | 0 mil | **looser** |
  | track ↔ track, arc ↔ arc, text ↔ text | 9 mil | tighter |

  `ClearanceMatrix` is indexed by class × class × layer only — there is no
  object-type dimension anywhere in `eu.mihosoft.freerouting.rules` — so this
  cannot be represented. The consequence is one-directional and worth knowing:
  freerouting enforces the *generic* value everywhere, so it is **stricter** than
  Altium on the two looser pairs above. On the reference board that accounts for
  a group of pad-to-pad "violations" that Altium's own DRC passes, because
  Altium allows 7.8 mil there and this importer requires 8.

## Versioning

`frpcb_version` is an integer, bumped on any breaking change to field
meaning (not on additive new optional fields). The importer rejects a file
whose major version it doesn't recognize rather than guessing.
