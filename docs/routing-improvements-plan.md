# Routing improvements: plan

Six asks, grounded in what the code already does. Two of them turn out to be
mostly wiring rather than new machinery, and one already exists and only needs
to be turned on — so the order below is by value per hour, not by the order
they were raised.

Throughout, "the reference board" is the 6-layer board currently driving the
Altium round trip: ~15k items, 438 nets, 44 incomplete connections and ~848
real clearance violations after `-rm finish`.

## Summary of what exists

| Ask | Status | Where |
|---|---|---|
| Start at pass N | **exists**, no CLI flag | `AutorouteSettings.get_start_pass_no`, `WindowAutorouteDetailParameter:89` |
| Via reduction | **exists**, already priority #2 | `BatchOptRoute.opt_route_pass:179` |
| Inner-layer preference | hook exists and is **live but never written** | `AutorouteControl.add_via_costs` → `MazeSearchAlgo:869` |
| Per-layer trace costs | exists, settable | `AutorouteSettings.set_preferred_direction_trace_costs` |
| Multiple trace widths | not present; one width per net per layer | `AutorouteControl.init_net:trace_half_width` |
| Violations lag | diagnosed, see §5 | `BoardHandling.toggle_clearance_violations:604` |

## 1. Violations lag — do this first

Cheapest fix, most immediate relief, zero routing risk.

Two separate problems, and the second is the one that actually hurts:

**Compute is on the EDT.** `toggle_clearance_violations` (`BoardHandling.java:604`)
runs `new ClearanceViolations(board.get_items())` synchronously from a
`BoardToolbar` action listener (`BoardToolbar.java:180`) and from
`MenuState.java:159`. That is one search-tree query per item over ~15k items,
on the UI thread.

**Draw is O(violations) on every repaint.** `ClearanceViolations.draw` iterates
the whole list per repaint, doing `fill_area` plus `draw_circle` for each, with
**no viewport culling**. It computes `intensity` from layer visibility and then
draws anyway — including at intensity 0, i.e. for every violation on every
hidden layer. Pan and zoom then re-run the lot.

Fix in this order, measuring after each:

1. **Skip `intensity <= 0`.** One line. On a 6-layer board with one layer
   visible this drops ~5/6 of the work out of every repaint.
2. **De-duplicate at construction.** The list holds each violation twice, once
   from each item's point of view — `ClearanceReport` already documents this and
   works around it. Halves both the draw cost and the reported count.
3. **Apply the same-net filter and the 0.5 mil rounding floor that
   `ClearanceReport` already applies.** The GUI currently shows neither, so its
   count is ~3× the report's for the same board. Aligning them makes the GUI
   show ~848 instead of ~2799: less to draw, and it stops the two paths
   disagreeing, which is its own bug.
4. **Viewport cull** against the visible clip bounds.
5. Only if still slow: compute on a worker thread via the existing
   `InteractiveActionThread` + `screen_messages` channel, and **cap** rendering
   at N (default ~2000) with the status bar saying how many were suppressed.

Steps 1–3 are a few lines each and likely resolve it; the worker thread (5) is
real work and should not be started until 1–4 have been measured.

## 2. Start at pass N — mostly exists

`start_pass_no` is already in `AutorouteSettings`, already has a GUI field, and
already drives the weights: `BatchAutorouter.java:330` sets
`ripup_costs = start_ripup_costs * pass_no`, so starting at pass 12 genuinely
starts with pass-12 ripup aggression. `MainApplication.java:120` already derives
`stop_pass_no` from `start_pass_no + max_passes - 1`.

Missing:

- A CLI flag. Add `-sp N` to `StartupOptions` alongside `-mp`/`-mt`.
- **Last completed pass is never reported.** Log it at the end of a batch run
  (`BatchAutorouter.autoroute_passes`) so a resumed run can be told where to
  pick up. Storing it in the `.frpcb.json`/board save is a larger change and
  probably not needed — printing it is enough to act on.

Note the semantics are "start with pass N's *weights*", not "resume the work of
pass N"; the board state is whatever you loaded. Worth saying plainly in the
help text so nobody expects checkpoint/restore.

## 3. Via reduction — exists, needs exposing

`BatchOptRoute` already optimizes with a strict lexicographic objective
(`opt_route_pass:179`):

    incomplete count  →  via count  →  weighted trace length

Via count is *already* the second priority: a re-route is accepted if it has
fewer vias at equal completion, and trace length only breaks ties among equal
via counts. It runs as the postroute phase, gated by
`AutorouteSettings.get_with_postroute()`, and works by ripping up each
connection in turn (in ascending x order) and re-routing it with elevated ripup
costs.

So the work is not to build this, it is to:

- Confirm postroute is actually enabled in the batch/CLI path — if it is off,
  no via optimization is happening at all.
- Expose a "via reduction effort" control that raises
  `AutorouteSettings.via_costs` during postroute only. Higher via costs make the
  maze search itself avoid vias; combined with the existing accept-if-fewer-vias
  rule this is the direct lever.
- Report before/after via counts (`BatchOptRoute.java:64` already logs them at
  WARN — promote to the report).

## 4. Inner-layer preference — the hook is already wired

The best find in this review. `AutorouteControl.add_via_costs[from].to_layer[to]`
is a per-layer-pair via cost. It is allocated, **explicitly zeroed** at
`AutorouteControl.java:83`, read by the maze search at `MazeSearchAlgo.java:869`
where it is added to the expansion value — and **never written by anything**.

That is exactly the quantity needed for the balance the ask describes. Combined
with the per-layer trace costs that already exist
(`set_preferred_direction_trace_costs(layer, value)`, consumed via
`ctrl.trace_costs[layer]` in `MazeSearchAlgo` and `DestinationDistance`), the
whole feature is a cost-assignment problem inside the existing search:

- Raise horizontal/vertical trace costs on layer 0 and layer n-1 → the search
  prefers inner layers on its own.
- Write `add_via_costs` so a via *inward* is cheap and a via *outward* is
  expensive → the search pays to leave an inner layer, which is precisely the
  "extra via vs. direct outer-layer route" trade-off, priced per hop rather
  than decided by a rule.

Design: one "inner layer preference" control (0–100) mapping deterministically
to those two arrays, logged at INFO so a given run's weights are auditable. At 0
the mapping must produce today's exact values, so the default is a no-op.

**No change to `MazeSearchAlgo`.** This is the point: the cost function is
already parameterised, the parameters are just never set.

## 5. Multiple trace widths

The only ask needing genuinely new control flow, but still not a change to the
search.

Today: `AutorouteControl.init_net` reads
`board.rules.get_trace_half_width(net_no, layer)` once per net per layer and
the search treats it as fixed (via `compensated_trace_half_width`, which adds
the clearance compensation). `NetClass` stores `trace_half_width_arr` per layer
and already has `set_trace_half_width_on_inner`. `with_neckdown` exists for
local narrowing near pads.

Because width is an *input* to the search, a preferred-width list is a loop
**around** the existing algorithm:

- Add to `AutorouteSettings` an ordered width list plus a mode:
  `as_imported` (default — use the Altium widths), `override` (single width
  wins), `prefer` (ordered list).
- In `BatchAutorouter.autoroute_item`, attempt the connection at the most
  preferred width; on `NOT_ROUTED`, retry at the next narrower. Each retry is a
  full maze search, so gate it: only on failure, and only from pass 2 on.
- CLI: `-tw <mil>` to override, `-tw 10,8,6` for a preference ladder.
- State the precedence against the imported widths explicitly and log which
  width each net was routed at. On a board where width is a current-carrying
  requirement, silently narrowing a trace is the worst possible outcome — so
  `prefer`/`override` must never widen or narrow past a per-net *minimum*, and
  that minimum should come from the imported rules, not from the CLI.

That last constraint is a design requirement, not a nicety: a width ladder is a
safety hazard on a high-voltage board unless it is floored per net.

## 6. Multi-threading the autorouter

The constraint given is that the existing algorithm stay intact, and that the
work go into deconflicting concurrent instances of it. That is the right
instinct, and it rules out the approach that would otherwise be tempting.

### What the algorithm is

Per connection: `AutorouteEngine.autoroute_connection` runs `MazeSearchAlgo`
(A*/best-first over expansion rooms built lazily from the `ShapeSearchTree`),
then `LocateFoundConnectionAlgo` back-traces the path, then
`InsertFoundConnectionAlgo` mutates the board — inserting traces and vias,
shoving neighbours, ripping up obstacles it was allowed to rip up. Then
`opt_changed_area` pull-tights the affected region.

### Shared mutable state, enumerated

This list is the actual work of any parallel scheme:

1. **`RoutingBoard` item list + `UndoableObjects`** — mutated by insert/ripup,
   and the undo stack is a single linear history.
2. **`ShapeSearchTree`** (the board's default tree *and* `autoroute_search_tree`)
   — mutated on every insert and delete through `SearchTreeManager`.
3. **`ItemAutorouteInfo`, attached to each board `Item`**
   (`Item.get_autoroute_info()`, lazily created; holds `expansion_room_arr`,
   `start_info`, door state). **This is the single biggest obstacle.** The
   search annotates *shared board items* with *per-search* state. Two
   concurrent searches on one board silently corrupt each other — no exception,
   just wrong routes. Any parallel scheme must move this into per-engine
   storage (an `IdentityHashMap<Item, ItemAutorouteInfo>`, or an array indexed
   by `Item.get_id_no()`) before anything else is attempted.
4. **`routing_board.changed_area`** — one board-level accumulator, set up by
   `start_marking_changed_area()` per item in the pass loop.
5. **`MazeSearchAlgo.random_generator`**, seeded from `ripup_costs`
   (`MazeSearchAlgo.java:86`) *specifically* to keep the ripup algorithm
   reproducible.
6. `hdlg.screen_messages` / `hdlg.repaint()` — EDT coupling in the pass loop.

Per-engine state (`drill_page_array`, the expansion room lists) is already
properly scoped and fine.

### Approaches, ranked

**D. Make the serial path faster first.** Probably the best value per hour, and
it must come first because you cannot tell whether parallelism helped if you
have not profiled the serial case.

- `autoroute_pass` recomputes `get_unconnected_set` / `get_connected_set` per
  item per pass — repeated connectivity walks over 438 nets.
- `calc_airline` is O(|from| × |to|) per connection.
- **`autoroute_item` wraps its entire body in `catch (Exception e) { return
  false; }`** (and so does `autoroute_pass`). Every bug is therefore
  indistinguishable from "no route found". Log those exceptions before doing
  anything else — some fraction of the 44 remaining incompletes may be thrown
  exceptions rather than genuine routing failures, and that is a one-line
  change with a potentially large answer.

**C. Parallelize the read-only work.** Low risk, no algorithm change:
`ClearanceViolations` construction (pure per-item reads — also fixes §1),
`calc_weighted_trace_length`, the search-tree bulk build at import. Do these
opportunistically.

**E. K independent routers, pick the best board.** The user's own "run multiple
instances and flag conflicts", in its safest form: run K full routers on K
copies of the board with different seeds and weight schedules, then keep the
best result by `BatchOptRoute`'s existing objective (incompletes → vias →
length). Zero conflict resolution, zero algorithm change, zero shared state —
the instances never touch each other. Scales straight to as many cores as you
have RAM for board copies. It does not make a single route faster, but it
converts cores directly into *better* results, which on a board with 44
incompletes is what is actually wanted. **This is the recommended first
parallel step.**

**A. Speculative parallel routing with serialized commit.** The real
fine-grained answer. Route N connections concurrently against a read-only board
snapshot; commit sequentially, re-validating each candidate against the
now-mutated board; on conflict, re-route that one connection serially.
Requirements: item #3 above (per-engine `ItemAutorouteInfo`) is a hard
prerequisite; a read-only board view; and a conflict test (does the candidate's
geometry violate clearance against anything committed since its snapshot).

Ripup makes naive conflicts frequent, so batch by **spatial disjointness**:
group pending connections whose airline bounding boxes, expanded by the
clearance, do not overlap. Conflicts then become rare and most commits land.

The serious risk is **determinism**, and it matters more here than usual: this
board is heading to a layout review, and a router whose output depends on thread
scheduling is very hard to defend. So batch composition and commit order must be
deterministic functions of the input (sort by item id), never of completion
order. Same input plus same thread count must give a bit-identical board. Treat
that as an acceptance test, not an aspiration.

**B. Parallelize inside one maze search — rejected.** Parallel best-first
search brings duplicate expansion and load-balancing problems, would require
rewriting `MazeSearchAlgo` (violating the stated constraint), and the frontier
here is small enough that there is little to win.

### Recommended sequence

1. Log the swallowed exceptions in `autoroute_item` / `autoroute_pass`. One
   line, possibly a large answer.
2. Profile a full `-rm finish` run; find where the time actually goes.
3. E — K independent routers, best board wins.
4. Only then A, starting with moving `ItemAutorouteInfo` off `Item`, which is
   worth doing on its own merits.

## Suggested overall order

1. §1 violations lag, steps 1–4 (hours, no routing risk)
2. §4 inner-layer preference (the hook is wired; this is cost assignment)
3. §2 `-sp` flag and last-pass logging (small)
4. §3 confirm postroute is on, expose via-reduction effort (small)
5. §6 step 1–2: exception logging, then profile
6. §5 trace width ladder, with the per-net floor
7. §6 step 3–4: parallel instances, then speculative commit
