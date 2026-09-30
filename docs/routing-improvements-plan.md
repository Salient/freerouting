# Routing improvements: plan

Six asks, grounded in what the code already does. Two of them turn out to be
mostly wiring rather than new machinery, and one already exists and only needs
to be turned on — so the order below is by value per hour, not by the order
they were raised.

Throughout, "the reference board" is the 6-layer board currently driving the
Altium round trip: ~15k items, 438 nets, 44 incomplete connections and ~848
real clearance violations after `-rm finish`.

## 0. The objective

Decided with the user: **reproducibility is not a requirement.** The only thing
judged is the quality of the final board — aesthetics, efficiency, total trace
length, via count. Two runs of the same input may legitimately differ.

This is the right call and it shapes everything in §6: a router is a heuristic
optimiser, the artifact it emits is reviewed and DRC'd on its own merits, and
insisting on bit-identical output would forfeit most of the available
parallelism to buy a property nobody needs. Concretely it means commit order may
follow completion order, RNG seeds need not be fixed, and the acceptance test
for any change is a *score comparison*, not a diff.

So every proposal below is measured against one function, which the code already
has most of — `BatchOptRoute.opt_route_pass:179`:

    incomplete count  →  via count  →  weighted trace length

lexicographically, lower better. `calc_weighted_trace_length` weights length by
clearance class; `routing_board.get_vias().size()` and
`BasicBoard.cumulative_trace_length()` supply the rest.

**Aesthetics is the one named metric this does not capture.** Length and via
count get most of the way there, but the thing that reads as ugly on screen is
usually bend count — detours, staircases, and traces that wander off the
preferred direction. `PolylineTrace.corner_count()` already exists, so a fourth
term (total corners, or corners in excess of the airline's minimum) is cheap to
add and would let "aesthetics" be optimised rather than eyeballed. Worth adding
as a *reported* metric first, before it is given weight in the accept/reject
decision, so we can see how it correlates with the boards you judge by eye.

Extracting that scoring function into one place — `BoardScore.of(board)`,
returning all four terms — is a small prerequisite for §6 E, since comparing K
boards is the whole mechanism there.

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

## 4. Inner-layer preference — implemented, and MEASURED AS INEFFECTIVE

**Read this before tuning `-il`.** The feature is implemented and the dead
`add_via_costs` hook is now written, but measured on the real 6-layer reference
board it does not move routing to inner layers, and at high settings it loses
connections. Two runs, `-rm reroute -mt 150`, identical except for `-il`:

| | `-il 0` | `-il 80` |
|---|---|---|
| incomplete | **12** | **15** |
| vias | 1014 | 1017 |
| weighted length | 55802423698 | 55766915499 |
| corners | 11225 | 11344 |
| **outer-layer length** | **37.38%** | **37.16%** |

At `-il 80` the outer-layer trace costs were raised 8.2× (layer 0 from 2.20/3.30
to 18.04/27.06) and a via-landing penalty of 176000 was applied to the outer
layers — confirmed in the run log, so the weights really were written. The result
was a **0.22 percentage point** shift in routed length off the outer layers,
while incompletes rose from 12 to 15. By the §0 objective that is strictly worse:
incompletes dominate.

Per-layer length at `-il 0`: MidLayer3 29.97%, MidLayer2 29.45%, TopLayer 20.44%,
BottomLayer 16.94%, MidLayer4 2.91%, MidLayer1 0.29%.

**Why, and it is not a bug in the implementation.** The board is *already* 62.6%
inner-routed at the default, because the default per-layer trace costs already
penalise the outer layers (layer 0 at 2.20/3.30 and layer 5 at 3.10/2.20, against
1.00–2.10 on the inner layers). The remaining ~37% is largely **structural rather
than chosen**: pads live on outer layers, so escapes and approaches must start and
end there regardless of cost. Raising the price of something the router has no
alternative to does not change where it goes — it only distorts the search enough
to fail a few connections.

Note also that wire *count* is a misleading proxy here and was rejected: by count
the outer share looks like 81%, because a pad-escape stub counts the same as a
long haul. Length is the honest measure.

**Recommendation:** leave `-il` at 0. It is verified to be an exact no-op there.
Do not invest in tuning the constants
(`OUTER_TRACE_COST_BOOST_AT_MAX` and friends) without first establishing that
there is discretionary outer-layer routing to recover — on this board there is
very little. If inner-layer bias is genuinely wanted, the lever with headroom is
probably *placement* and pad-escape strategy, not the routing cost model.

Retained because the plumbing is correct, it is inert at the default, and the
measurement is the point: it stops this being re-litigated from first principles.

### Original analysis, for reference — the hook was already wired

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
   reproducible. Per §0 that property is not needed, so this is not a constraint
   — but note it is a `java.util.Random` reachable from concurrent searches, so
   it still needs to become per-engine to avoid contention on its internal seed.
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

**C. Parallelize the read-only work — NOT low risk, corrected.** This entry
previously called `ClearanceViolations` construction "pure per-item reads" and
therefore safe to parallelize opportunistically. **That was wrong**, and the
reason matters for every other option here.

`MinAreaTree.node_stack` (`datastructures/MinAreaTree.java:253`) is a `protected`
**instance** field — one shared `ArrayStack<TreeNode>` used as scratch space by
every search-tree *traversal*, including pure read paths. It is used by the base
class (`:67-86`) and by all three tree variants:
`board/ShapeSearchTree.java:668-728`,
`board/ShapeSearchTree90Degree.java:90-154`,
`board/ShapeSearchTree45Degree.java:103-172`.

So two threads issuing read-only queries against the same `ShapeSearchTree`
corrupt each other's traversal — silently, as wrong results or an
`ArrayIndexOutOfBounds`, not a clean failure. `Item.clearance_violations()`
(`board/Item.java:351-363`) reaches it through
`overlapping_tree_entries_with_clearance`. There is no such thing as a safe
concurrent *read* of these trees today.

Making the scratch stack thread-local (or a local variable) is a small change
across ~4 files, but it is a **prerequisite** for any parallel scheme, not a
nicety — and it is a latent bug regardless of parallelism, because it silently
couples any two traversals that interleave.

The rest of the entry stands once that is fixed: `calc_weighted_trace_length` and
the search-tree bulk build at import are then genuinely parallelizable.

**E. Portfolio: K independent routers, keep the best board.** The user's own
"run multiple instances", in its strongest form. Run K full routers on K copies
of the board, each with a different RNG seed and weight schedule, and keep
whichever board scores best on the §0 objective. Zero conflict resolution, zero
algorithm change, zero shared state — the instances never touch each other.
Scales to as many cores as you have RAM for board copies.

Because only the final artifact is judged, this is not a compromise, it is the
approach that most directly optimises the stated metric. Seed diversity is now a
*feature*: `MazeSearchAlgo` seeds its RNG from `ripup_costs` purely for
reproducibility (`MazeSearchAlgo.java:86`), and varying it instead turns each
core into an independent sample of the result distribution. Ripup-based routing
has high variance between seeds, so the max over K samples is meaningfully better
than any single run — and this is the cheapest way in the whole plan to convert
cores into better boards.

Extend it to a **parameter sweep**, since the search is already parameterised by
everything §3–§5 exposes: via costs, per-layer trace costs, `add_via_costs`,
ripup schedule, starting pass. Sweeping those across workers explores the
trade-off surface rather than guessing one point on it, and it answers the
inner-layer question (§4) empirically instead of by hand-tuning a slider.

It does not make a single route faster. It makes the *result* better, which on a
board with 44 incompletes is what is actually wanted. **This is the recommended
first parallel step**, and given §0 it may be sufficient on its own.

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
This is purely a throughput heuristic — it is not load-bearing for correctness.

**Reproducibility is explicitly not required** (see §0). That removes what would
otherwise be this approach's main cost: commits can land in completion order,
with no barrier waiting on a particular thread and no need to make batch
composition a function of the input. It also frees
`MazeSearchAlgo.random_generator` from its fixed seed, which becomes an asset
rather than a constraint — see E.

**B. Parallelize inside one maze search — rejected.** Parallel best-first
search brings duplicate expansion and load-balancing problems, would require
rewriting `MazeSearchAlgo` (violating the stated constraint), and the frontier
here is small enough that there is little to win.

### Recommended sequence

1. Log the swallowed exceptions in `autoroute_item` / `autoroute_pass`. One
   line, possibly a large answer.
2. Extract `BoardScore` (§0) and report it at the end of every run. Nothing
   below can be evaluated without it.
3. Profile a full `-rm finish` run; find where the time actually goes.
4. E — portfolio of K independent routers with varied seeds and weights, best
   board wins. Given §0 this is the highest value-per-hour parallel work, and
   possibly the last one needed.
5. Only then A, starting with moving `ItemAutorouteInfo` off `Item` — worth
   doing on its own merits regardless.

## Suggested overall order

1. §1 violations lag, steps 1–4 (hours, no routing risk)
2. §6 step 1: log the swallowed exceptions (one line)
3. §0 `BoardScore` + report it every run — the measuring stick for all of the below
4. §4 inner-layer preference (the hook is wired; this is cost assignment)
5. §2 `-sp` flag and last-pass logging (small)
6. §3 confirm postroute is on, expose via-reduction effort (small)
7. §6 step 3: profile a full run
8. §5 trace width ladder, with the per-net floor
9. §6 C: make `MinAreaTree.node_stack` thread-local — a prerequisite for **all**
   parallelism, and a latent bug regardless of it
10. §6 step 4: the portfolio router — then A only if that proves insufficient

Items 2 and 3 moved up: with reproducibility off the table, everything is judged
by score, so the score has to exist and be trustworthy before the tuning work in
4-6 can be told apart from noise.

Item 9 is new and gates 10: there is currently no safe concurrent *read* of a
search tree, so nothing parallel can be built or even prototyped before it lands.
