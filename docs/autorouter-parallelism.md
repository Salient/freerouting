# Parallelising the autorouter: analysis and plan

## Purpose and ground rules

This document plans how to convert extra CPU cores into a *better routed
board*, without rewriting the routing algorithm itself. Two decisions from the
user constrain everything below:

1. **The existing algorithm must stay intact to the greatest extent
   possible.** The job is deconflicting concurrent instances of it, not
   inventing a new one. Any proposal that requires rewriting `MazeSearchAlgo`'s
   expansion logic is disqualified by default.
2. **Reproducibility is not required.** The only thing judged is the quality
   of the final board — completeness, via count, trace length, aesthetics.
   Two runs of the same input may legitimately differ. This is the single
   biggest lever in the whole design space: it means RNG seeds don't need to
   be fixed, commits don't need to land in a canonical order, and "make it
   parallel" can mean "run several independent full attempts and keep the
   best one" without that being a cop-out.

Every code claim below is cited as `file:line`, verified against the tree at
the time of writing (branch `task2/parallelism-design`, based on
`build/gradle-9-modern-jdk`). Where I reason rather than cite — variance
between RNG seeds, expected profiling outcomes, effort estimates — I say so
explicitly. A companion document, `docs/routing-improvements-plan.md`, covers
six other routing-quality asks and contains an earlier, shorter pass at the
multithreading question (its §6); this document supersedes that section in
depth and corrects one of its claims (see the "read-only work" caveat in
Part V.B).

---

## Executive summary

- **The algorithm is a lazy, best-first (A*) search over a maximal-convex-tile
  decomposition, with going-around, shoving, ripping up, and adding a via all
  priced as edges in *one* search.** That unification is the main reason it
  beats phase-based competitors (rip-up-and-retry, greedy-then-fix), and nothing
  about parallelising it should touch that unification.
- **The real obstacle to concurrency is not the algorithm, it's shared board
  state.** Two hazards matter, one already flagged by the user's brief and one
  this review found independently:
  1. `ItemAutorouteInfo` (and a sibling cache on `Via`) attach *per-search*
     scratch state to *shared* board `Item` objects — confirmed, ~22 call
     sites, moving it is tractable (Part IV.A).
  2. **The search tree's own traversal state is a shared mutable field, not a
     local variable** (`MinAreaTree.node_stack`, `MinAreaTree.java:253`). Every
     tree query — including ones the existing plan calls "embarrassingly
     parallel read-only work" — reuses this one field with zero
     synchronization. Concurrent reads on the same tree instance are **not**
     safe today. This corrects a claim in `docs/routing-improvements-plan.md`
     (Part V.B below).
- **Recommended first step: a portfolio of K independent routers on K board
  copies, keep the best board by score.** Zero shared state, zero algorithm
  change, and a working deep-copy primitive already exists
  (`BasicBoard.clone()`, `BasicBoard.java:130-133`). Given that reproducibility
  isn't required, this converts cores into board quality directly and may be
  sufficient on its own.
- **Recommended second step (only if the portfolio proves insufficient):**
  speculative parallel routing of spatially-disjoint connections against a
  read-only-ish snapshot, with serialized commit. This is the "real"
  fine-grained answer, but it is gated on fixing both hazards above first.
- **Interactive push-and-shove is a different problem** — latency, not
  throughput — and is treated separately in Part VI.
- **Rejected:** parallelising inside one maze search (small frontier, would
  require rewriting `MazeSearchAlgo`, violates the stated constraint).

---

## Part I — What the algorithm actually does

### I.1 The per-connection pipeline

For one connection, `AutorouteEngine.autoroute_connection`
(`autoroute/AutorouteEngine.java:115`) runs, in order: `MazeSearchAlgo` to find
a path, `LocateFoundConnectionAlgo` to back-trace it, and
`InsertFoundConnectionAlgo` to commit it — inserting the trace/via, shoving
neighbours, ripping up whatever the search decided to rip up. `RoutingBoard`
then pull-tights the affected region via `opt_changed_area`
(`board/RoutingBoard.java:218`, `:235`). This runs once per unconnected
net-item, in a pass loop driven by `BatchAutorouter.autoroute_pass`
(`autoroute/BatchAutorouter.java:191`), across successive passes with rising
`ripup_costs` (`BatchAutorouter.java:330`:
`autoroute_control.ripup_costs = this.start_ripup_costs * p_ripup_pass_no;`).

### I.2 It's A* over free-space tiles, not a grid

**Claim verified.** The open list is a `TreeSet<MazeListElement>`
(`autoroute/MazeSearchAlgo.java:88`, `:1454`), and `MazeListElement` orders
itself by `sorting_value`:

```java
// MazeListElement.java:57-59
public int compareTo(MazeListElement p_other)
{
    double compare_value = (this.sorting_value - p_other.sorting_value);
```

`sorting_value` is built at `MazeSearchAlgo.java:595-604`:

```java
double expansion_value = p_from_element.expansion_value + p_add_costs
        + shape_entry_middle.weighted_distance(...);
double sorting_value = expansion_value + this.destination_distance.calculate(shape_entry_middle, layer);
```

`expansion_value` is cost-so-far, the `destination_distance.calculate(...)`
term is the heuristic — this is textbook A* (`f = g + h`), not Dijkstra or a
flood fill.

The expansion space is not a grid. It is the maximal-convex-free-tile
decomposition (`CompleteFreeSpaceExpansionRoom`) produced by
`ShapeSearchTree.complete_shape` (`board/ShapeSearchTree.java:643`), which
walks the board's k-d-like `MinAreaTree` and repeatedly restrains a candidate
tile against every obstacle shape it overlaps
(`ShapeSearchTree.java:694-722`, `restrain_shape`). Rooms are linked into a
graph by `ExpansionDoor`s (shared borders) rather than being cells on a
lattice — so the number of search nodes scales with board *complexity*
(obstacle count), not with board *area* ÷ grid pitch, which is the source of
freerouting's advantage over grid-based Lee/maze routers on large or sparse
boards (see Part II).

### I.3 Rooms are built lazily, per search, never for the whole board

**Claim verified.** `expand_to_room_doors` calls
`this.autoroute_engine.complete_neigbour_rooms(p_list_element.next_room);` at
`MazeSearchAlgo.java:235`, i.e. neighbour rooms are completed on demand, only
when the frontier reaches their door, not up front. `complete_neigbour_rooms`
(`AutorouteEngine.java:503`) in turn calls
`AutorouteEngine.complete_expansion_room` (`AutorouteEngine.java:384`), which
calls `ShapeSearchTree.complete_shape` and — this is the detail the user's
brief didn't call out — **inserts the newly-completed room back into the
search tree as a leaf**:

```java
// AutorouteEngine.java:456-468 (add_complete_room)
CompleteFreeSpaceExpansionRoom completed_room = (CompleteFreeSpaceExpansionRoom) calculate_doors(p_room);
if (completed_room != null && completed_room.get_shape().dimension() == 2)
{
    complete_expansion_rooms.add(completed_room);
    this.autoroute_search_tree.insert(completed_room);   // <-- mutates the tree
    result = completed_room;
}
```

and tears it back out again at teardown:

```java
// AutorouteEngine.java:249-262 (clear)
for (CompleteFreeSpaceExpansionRoom curr_room : complete_expansion_rooms)
{
    curr_room.remove_from_tree(this.autoroute_search_tree);
}
...
board.clear_all_item_temporary_autoroute_data();
```

So the decomposition is not just lazily *read*, it is lazily *written into*
the tree during the search and cleaned up afterward. This has a direct
consequence for parallelism, developed in Part IV.B: it isn't only board
edits (trace/via insert-remove) that mutate the shared tree during routing —
**the search itself does, as a side effect of exploring**, even before any
result is committed.

### I.4 The heuristic is an admissible box-distance lower bound

**Claim verified.** `DestinationDistance.calculate` (`autoroute/DestinationDistance.java:136`)
computes, for a query box/layer, the minimum of several weighted
Chebyshev-style distances to the destination's per-layer bounding boxes
(component-side / solder-side / inner-side), each accounting for the
*minimum* possible trace cost in each direction and the *minimum* via cost for
each layer change needed to get there:

```java
// DestinationDistance.java:432-434 (inner-layer case, 2-layer estimate)
double tmp_distance = inner_side_max_delta + inner_side_min_delta + min_normal_via_cost;
result = Math.min(result, tmp_distance);
```

Because every term uses a *minimum* possible cost for its category (never an
actual/average cost), the result never overestimates the true remaining cost
— which is exactly the admissibility condition A* needs to guarantee that the
first path popped off the open list to a destination door is optimal *for the
cost model in force at that instant* (see the caveat in Part I.5 and Part
II.5). One implementation nuance worth flagging: `min_normal_via_cost` is a
mutable (non-final) field, temporarily swapped by
`calculate_cheap_distance` (`DestinationDistance.java:456-465`); this is safe
today only because each `DestinationDistance` is owned by exactly one
`MazeSearchAlgo`/one thread — it would not be safe to share one
`DestinationDistance` instance across concurrent searches.

### I.5 The central insight: one search prices detour, shove, ripup, and via as competing edges

This is the strongest claim in the brief and it holds up completely.

- **Ripup enters the A* cost directly**, not as a separate phase. When a shove
  attempt fails but ripup is still possible, the search re-queues the door
  section with the ripup cost folded straight into both the cost-so-far and
  the sorting key:

  ```java
  // MazeSearchAlgo.java:355-361
  MazeListElement new_element =
          new MazeListElement(p_list_element.door, p_list_element.section_no_of_door,
          p_list_element.backtrack_door, p_list_element.section_no_of_backtrack_door,
          p_list_element.expansion_value + ripup_costs, p_list_element.sorting_value + ripup_costs,
          ...
  ```

  and that ripup cost is itself scaled by the width of the specific obstacle
  being considered — a thick trace costs more to rip than a thin one:

  ```java
  // MazeSearchAlgo.java:1093-1096 (check_ripup)
  eu.mihosoft.freerouting.board.Trace obstacle_trace = (eu.mihosoft.freerouting.board.Trace) p_obstacle_item;
  cost_factor = obstacle_trace.get_half_width();
  ```

- **Shoving is an expansion option evaluated inline**, not a repair pass run
  after a route fails:

  ```java
  // MazeSearchAlgo.java:348-350
  if (!curr_door_is_small && this.ctrl.max_shove_trace_recursion_depth > 0 && obstacle_item instanceof eu.mihosoft.freerouting.board.PolylineTrace)
  {
      if (!shove_trace_room(p_list_element, obstacle_room))
  ```

  `shove_trace_room` (`MazeSearchAlgo.java:1216`) delegates to
  `MazeShoveTraceAlgo.check_shove_trace_line`, and if shoving succeeds the
  search simply continues expanding through the (now virtually displaced)
  room — no separate "shove pass" object, no retry loop.

- **Adding a via is priced the same way**, via
  `ctrl.add_via_costs[from_layer].to_layer[to_layer]` read at
  `MazeSearchAlgo.java:869`, added straight into `expansion_value`.

Because all four options live in the same priority queue under the same cost
units, A* naturally picks whichever combination is cheapest *for this
connection, right now* — go around, nudge a neighbour, rip up a thin trace and
re-route it later, or drop a via and use another layer. Competing routers that
structure this as **separate phases** (route greedily → detect failure → rip
up → re-route) cannot make this trade-off; they can only ever compare "did
this phase succeed," not "which combination is cheapest." That structural
difference, not any single heuristic trick, is why this algorithm tends to
produce fewer vias and shorter total length than phase-based rip-up-and-retry
on the same board (reasoning, not a benchmarked claim in this codebase — no
comparative benchmark exists here to cite).

**One qualifier the brief didn't include, and which belongs in an honest
document (see Part II.5):** "optimal for its cost model" is a *per-connection*
guarantee at the moment that connection is searched. It is not a global
guarantee across the whole board or across connections routed later — a
later connection's ripup can undo an earlier connection's locally-optimal
route, and the multi-pass loop with rising `ripup_costs` is itself a hand-tuned
heuristic schedule, not something A* reasons about. This matters for Part V:
it's *why* the portfolio approach (many independent full attempts) has room to
find meaningfully different-quality boards from different net orderings and
seeds — the per-connection optimum does not pin down a unique board-level
outcome.

### I.6 A confirmed dead cost hook

**Claim verified exactly.** `AutorouteControl.add_via_costs[from].to_layer[to]`
is allocated and explicitly zeroed:

```java
// AutorouteControl.java:79-85
for (int i = 0; i < layer_count; ++i)
{
    for (int j = 0; j < layer_count; ++j)
    {
        add_via_costs[i].to_layer[j] = 0;
    }
}
```

read at `MazeSearchAlgo.java:869`, and — grepping every reference in the
codebase — **never written anywhere else**. It is a fully wired, currently
inert parameter. Not a parallelism finding per se, but relevant to Part V.A:
it's exactly the kind of knob a parameter sweep should vary, because sweeping
it is the only way anyone will ever find out what it's worth.

---

## Part II — Why this wins, and where it doesn't (honest competitive analysis)

This section is necessarily more reasoning than citation — comparing against
router architectures not in this codebase. I flag the boundary clearly.

### II.1 vs. gridded/Lee maze routers

*(Reasoning.)* A classic Lee/grid maze router expands over a fixed lattice.
Its cost is driven by grid cell count, which is board-area-over-pitch, largely
independent of how empty the board is. Freerouting's free-space-tile expansion
(Part I.2) scales with obstacle count instead, so a mostly-empty board with a
few components routes almost instantly, while both approaches degrade as
density rises — but the grid router degrades with density *and* with sheer
board size, whereas the tile router only degrades with density. The trade
freerouting makes is per-node complexity: computing a maximal free tile and
its doors (`SortedRoomNeighbours.calculate`, walked from
`AutorouteEngine.calculate_doors`, `AutorouteEngine.java:482-498`) is far more
expensive per node than "read four/eight grid neighbours." For sparse boards
this is a clear win; for extremely dense boards with many tiny obstacles, the
tile count can approach the cell count and the constant-factor cost per node
starts to matter more — this is a plausible weak spot but not something
measurable from source alone (see Part VIII).

### II.2 vs. channel/switchbox routers

*(Reasoning.)* Channel/switchbox routing (classic left-edge algorithms,
etc.) assumes a decomposed routing region with known channel capacities,
typically for gate-array/standard-cell layouts with regular structure.
General two-layer-plus PCB layouts with arbitrary component placement don't
have that regular structure, so this family isn't really a competitor for
this problem — it's a different problem shape. Not applicable to freerouting's
target use case; included here only because the brief asked for a general
router-architecture comparison.

### II.3 vs. plain rip-up-and-retry

This is the most directly comparable architecture, and the comparison is the
strongest argument for the codebase's design. A conventional rip-up-and-retry
router routes greedily net-by-net, and when a later net can't be routed,
identifies and removes some earlier net(s), then retries — as a **separate
control-flow phase** from the initial greedy route. As argued in Part I.5,
freerouting's ripup is not a separate phase, it's a priced edge *inside* the
same search that is also considering "go around" and "shove." The practical
effect: a rip-up-and-retry router's decision to rip up net X is made *without
knowing yet* whether ripping X is actually cheaper than detouring around it —
it only ever finds out after ripping and re-attempting. Freerouting's A*
compares the priced cost of ripping vs. detouring *before* committing to
either, because both are just entries in the same priority queue. This should
produce fewer wasted rip/reroute cycles and better final trade-offs, at the
cost of a more complex per-node expansion function. This is architectural
reasoning grounded in the cited code, not a benchmark against another router.

### II.4 vs. negotiated-congestion routing (PathFinder, common in FPGA routing)

*(Reasoning — PathFinder is not in this codebase.)* PathFinder-style routers
route all nets independently and cheaply first, let them overlap
("over-subscribe" shared resources), then iteratively raise the cost of
congested resources and re-route everything, so nets *negotiate* for shared
capacity over several global iterations. This is explicitly a multi-net,
whole-design congestion view. Freerouting's ripup cost model (Part I.5) is
comparable in spirit — a resource (an existing trace) has a rip cost that acts
like a congestion penalty — but it is evaluated **one connection at a time**
in net order, not negotiated **across all nets simultaneously**. There is no
cost feedback loop where routing net B changes the *board-wide* congestion
picture and reruns every other net's cost function; the closest freerouting
analogue is the outer multi-pass loop with escalating `ripup_costs`, which
is a much coarser, un-targeted version of the same idea (it raises the *global*
willingness to rip, not the congestion cost of the *specific resources* that
turned out to be oversubscribed). This is the most honest weakness to name:
**freerouting has no cross-net congestion negotiation.** A net routed early
gets first claim on cheap real estate; a net routed late pays whatever
ripup/shove cost is needed to displace it. Net *order* therefore materially
affects the final board, and nothing in the algorithm looks at global
resource contention the way PathFinder-family FPGA routers do.

### II.5 Honest weaknesses, stated plainly

- **Per-connection optimality is not board-level optimality** (Part I.5). The
  net routing order is a free variable the algorithm does not optimize; two
  different net orders can plausibly produce different-quality boards from
  identical input. (This is exactly the variance the portfolio approach in
  Part V.A is designed to exploit, not fight.)
- **No cross-net congestion negotiation** (Part II.4).
- **The heuristic assumes the cheapest via/trace cost achievable anywhere on
  the board**, which is a global lower bound, not a locally-tight one — for
  boards with very heterogeneous per-net costs (different trace widths per
  net, per-layer restrictions), the heuristic can be a much looser bound than
  the true remaining cost, which weakens A*'s pruning power without breaking
  its correctness. This is inferred from the heuristic's construction (Part
  I.4) and not independently measured.
- **Exceptions are swallowed silently.** Both `autoroute_pass`
  (`BatchAutorouter.java:293-297`) and `autoroute_item`
  (`BatchAutorouter.java:379-382`) wrap their entire body in
  `catch (Exception e) { return false; }` with no logging. An actual bug is
  therefore indistinguishable from "no route found," which means some
  unknown fraction of reported routing failures may not be genuine — this is
  a correctness/observability gap independent of parallelism, already flagged
  in `docs/routing-improvements-plan.md` §6, and worth fixing before drawing
  conclusions from any A/B comparison of parallel vs. serial results (a
  swallowed race-condition exception would look identical to "the router
  legitimately failed this connection").

---

## Part III — The objective function

`BatchOptRoute.opt_route_item` (`autoroute/BatchOptRoute.java:118`) already
computes exactly the comparison a "keep the best board" scheme needs, inline:

```java
// BatchOptRoute.java:179-183
boolean route_improved = !this.thread.is_stop_requested() && (incomplete_count_after < incomplete_count_before ||
        incomplete_count_after == incomplete_count_before &&
        (via_count_after < via_count_before ||
        via_count_after == via_count_before &&
        this.min_cumulative_trace_length_before > trace_length_after));
```

That's the lexicographic order **incomplete count → via count → weighted trace
length**, lower-better, confirmed exactly. `calc_weighted_trace_length`
(`BatchOptRoute.java:224-253`) weights each unfixed/shove-fixed trace segment
by `half_width + clearance_value(...)` (`BatchOptRoute.java:242`), i.e. wider
and higher-clearance-class traces count for more length — this is a
board-cost proxy, not literal millimetres, which matters if you're comparing
boards with different trace-width policies (e.g. across a parameter sweep that
varies width, per `docs/routing-improvements-plan.md` §5).

**This logic is not currently extracted into a reusable function** — it's
inline in `opt_route_item`, evaluated only during the post-route optimizer,
against the live mutable board (using `generate_snapshot()`/`pop_snapshot()`/
`undo()` on the single linear undo history, `BatchOptRoute.java:157,197,203` —
see Part IV.C). Extracting a standalone `BoardScore.of(board)` — pure
function, no mutation, callable against any board/board-copy — is a
**hard prerequisite** for every approach in Part V that compares whole boards
(especially V.A, the portfolio). This is cheap (the four inputs are all
already-existing read-only board queries: incomplete count from the ratsnest,
`get_vias().size()`, and `calc_weighted_trace_length`) but it has to happen
before anything else, or "pick the best board" has nothing to compare.

`PolylineTrace.corner_count()` (`board/PolylineTrace.java:115`) exists and is
a plausible cheap proxy for the "aesthetics" dimension named in the brief
(bend count / detours), confirmed present but not currently part of any score.
Recommend adding it as a **reported**, not yet weighted, fourth term — see
`docs/routing-improvements-plan.md` §0 for the same reasoning independently
arrived at.

---

## Part IV — Shared mutable state: the actual obstacle

This is where a concurrency-focused review earns its keep: the algorithm
itself parallelises fine in spirit (each connection's search is a pure
function of "the board as it currently is" plus its own scratch state); what
doesn't parallelise is that "scratch state" leaking into supposedly-shared
objects, and the board mutating out from under a search that hasn't finished.

### IV.A `ItemAutorouteInfo` and its sibling on `Via` — confirmed, and larger than described

**Claim verified.** `Item.get_autoroute_info()` (`board/Item.java:1209-1215`)
lazily creates and caches an `ItemAutorouteInfo` directly on the item:

```java
// Item.java:1209-1215
public eu.mihosoft.freerouting.autoroute.ItemAutorouteInfo get_autoroute_info()
{
    if (autoroute_info == null)
    {
        autoroute_info = new eu.mihosoft.freerouting.autoroute.ItemAutorouteInfo(this);
    }
    return autoroute_info;
}
```

backed by the field `Item.java:1462`:
`transient private eu.mihosoft.freerouting.autoroute.ItemAutorouteInfo autoroute_info = null;`.
`ItemAutorouteInfo` (`autoroute/ItemAutorouteInfo.java`) holds `start_info`
(whether this item belongs to the current search's start/destination set),
`precalculated_connnection`, and `expansion_room_arr` — an array of
`ObstacleExpansionRoom`, each of which carries its own door list, i.e. the
"door state" the brief mentioned is inside these room objects, reached
transitively through `expansion_room_arr`, not a separate field. Two
concurrent searches sharing an item — which happens the instant two
connections' expansion frontiers both touch the same trace or pin, which is
common — silently clobber each other's `start_info`/room state with **no
exception, no assertion, just a wrong route**, exactly as claimed.

**What the brief's enumeration missed:** `Via` carries a second,
structurally identical cache, `autoroute_drill_info`
(`board/Via.java:277`, an `ExpansionDrill`), lazily built by
`get_autoroute_drill_info` (`Via.java:167-181`) and cleared alongside
`ItemAutorouteInfo` by an overridden `clear_autoroute_info`
(`Via.java:191-194`: `super.clear_autoroute_info(); this.autoroute_drill_info = null;`).
Any refactor that moves `ItemAutorouteInfo` off `Item` must move this field
too, or vias will keep corrupting concurrent searches even after the "main"
fix lands.

**Refactor cost, counted directly** (not estimated): grepping every reference
in the tree —

| Symbol | Call sites | Files |
|---|---|---|
| `get_autoroute_info()` | 14 | `Item.java`, `Via.java`, `TargetItemExpansionDoor.java`, `AutorouteEngine.java`, `SortedRoomNeighbours.java`, `Sorted45DegreeRoomNeighbours.java`, `MazeSearchAlgo.java`, `SortedOrthogonalRoomNeighbours.java`, `Connection.java` |
| `get_autoroute_info_pur()` | 2 | `Item.java` (decl.), `AutorouteEngine.java` |
| `clear_autoroute_info()` | 6 | `Item.java` (decl.), `Via.java` (override + decl.), `BasicBoard.java` (×2), `RoutingBoard.java` (×2) |

~22 call sites across 9 files. That is a small, mechanical, contained
refactor: replace the field-on-`Item` with a per-engine lookup — an
`IdentityHashMap<Item, ItemAutorouteInfo>` owned by `AutorouteEngine`, or an
array indexed by `Item.get_id_no()` sized to the board's item count — and
change `get_autoroute_info()` to take the owning engine (or become an
instance method on the engine, `engine.info_for(item)`). Every call site
already has an `AutorouteEngine` or `MazeSearchAlgo` in scope (they're all
inside autoroute-package classes reached from an engine), so the plumbing is
"pass one more reference," not "restructure control flow." **Estimate: 0.5–1.5
days**, including `Via.autoroute_drill_info`, plus test time — this is
exactly the estimate the brief implicitly assumed, and verification confirms
it holds.

One point *for* doing this refactor regardless of parallelism: it is already
architecturally the right home for this data. `RoutingBoard.init_autoroute`
(`RoutingBoard.java:917-926`) reuses a single `AutorouteEngine` instance across
multiple connections within a pass when `maintain_database`/`retain_autoroute_database`
is true, specifically so `ItemAutorouteInfo` survives across connections as a
performance cache:

```java
// RoutingBoard.java:920-925
if (this.autoroute_engine == null || !p_retain_autoroute_database || ...)
{
    this.autoroute_engine = new AutorouteEngine(this, p_trace_clearance_class_no, p_retain_autoroute_database);
}
this.autoroute_engine.init_connection(p_net_no, p_stoppable_thread, p_time_limit);
```

The reuse boundary is already "one engine, several connections" — moving the
cache to live on the engine instead of on the item doesn't fight this existing
design, it makes the existing design's intent explicit.

### IV.B The search tree — mutated by commits, *and* mutated by the search itself, *and* its traversal state is shared

This is the part the brief's enumeration didn't reach, and it changes the risk
picture for "embarrassingly parallel read-only work" (Part V.B).

**IV.B.1 — Confirmed as stated: commits mutate shared trees.**
`SearchTreeManager.insert`/`remove` (`board/SearchTreeManager.java:58-89`)
iterate `compensated_search_trees` — every tree the board has, default plus
one per clearance class ever autorouted with — and mutate *all* of them on
every item insert/delete. `SearchTreeManager.get_autoroute_tree`
(`SearchTreeManager.java:197-233`) **caches one tree instance per clearance
class number**, shared by every `AutorouteEngine` created for that clearance
class on that board (`AutorouteEngine.java:67`:
`this.autoroute_search_tree = p_board.search_tree_manager.get_autoroute_tree(p_trace_clearance_class_no);`).
Since most boards use very few trace clearance classes, two connections
routed concurrently will very likely fetch **the same tree instance**.

**IV.B.2 — New finding: the search writes into that same shared tree before
any result is committed.** As shown in Part I.3, `add_complete_room`
(`AutorouteEngine.java:456-468`) calls
`this.autoroute_search_tree.insert(completed_room)` for every lazily-completed
expansion room, and `clear()` (`AutorouteEngine.java:249-262`) removes them
again at teardown. This means the *search itself*, not just its eventual
board edits, mutates a tree instance that may be shared with a concurrent
search on a different net of the same clearance class — with no snapshotting,
no lock, nothing. Today this is safe only because exactly one `AutorouteEngine`
ever touches a given cached tree at a time, by construction of the
single-threaded pass loop.

**IV.B.3 — New finding, and the sharpest one: tree traversal itself is not
reentrant-safe, let alone thread-safe, because the traversal stack is a
shared instance field, not a local variable.** `MinAreaTree` declares:

```java
// datastructures/MinAreaTree.java:253
protected ArrayStack<TreeNode> node_stack = new ArrayStack<TreeNode> (10000);
```

and every traversal method — `MinAreaTree.overlaps` (`MinAreaTree.java:60-91`,
the primitive behind `ShapeSearchTree.overlapping_tree_entries`,
`overlapping_objects`, and `overlapping_objects_with_clearance`) and
`ShapeSearchTree.complete_shape` (`ShapeSearchTree.java:643`, and its
overrides in `ShapeSearchTree90Degree.java:63` and
`ShapeSearchTree45Degree.java:64`, confirmed to *also* use
`this.node_stack` at lines 90-91 and 103-104 respectively, i.e. this is not a
slow-path-only issue, it's the default path for 90° and 45° routing) — resets
and drives *the same field* on the tree object:

```java
// MinAreaTree.java:67-69, repeated verbatim in complete_shape and its overrides
this.node_stack.reset();
this.node_stack.push(this.root);
```

`ArrayStack` (`datastructures/ArrayStack.java`) is a bare array with an index,
no synchronization whatsoever:

```java
public void push(p_element_type p_element)
{
    ++level;
    if (level >= node_arr.length) { reallocate(); }
    node_arr[level] = p_element;
}
```

**Two threads calling any of these query methods on the same `ShapeSearchTree`
instance — even purely as reads, mutating no board state at all — race on
`level` and `node_arr`.** This will silently return wrong/incomplete overlap
sets, or throw `ArrayIndexOutOfBoundsException`, non-deterministically,
depending on interleaving. It is not reentrant either: nothing prevents a
traversal from calling back into another traversal on the same tree before
the outer one finishes (none currently do, by inspection, but this is a latent
trap for future code, not just a threading one).

**This directly corrects a claim in `docs/routing-improvements-plan.md`.**
That document's §6 category **C** — "Parallelize the read-only work... low
risk, no algorithm change" — names `ClearanceViolations` construction as an
example. Verified: `Item.clearance_violations()` (`board/Item.java:351-363`)
calls `board.search_tree_manager.get_default_tree()` then
`default_tree.overlapping_tree_entries_with_clearance(...)`
(`ShapeSearchTree.java:470-497`), which calls `overlaps(offset_bounds)`
(`ShapeSearchTree.java:497`) — the exact shared-`node_stack` method above,
against **the one default tree every item on the board shares**. Parallelising
`ClearanceViolations` construction across items (`interactive/ClearanceViolations.java:47-56`,
`this.list.addAll(curr_item.clearance_violations())` in a loop) by simply
running that loop body on multiple threads is **not** low-risk as stated — it
races on `node_stack` on every single call. **Correction, not rejection:**
the fix is small — make the traversal stack a local variable (or a
per-call/`ThreadLocal<ArrayStack<TreeNode>>`) instead of an instance field,
which is a self-contained, low-risk change to `MinAreaTree`/`ShapeSearchTree`
and its two subclasses (4 files, the same shape of edit in each) — but it must
happen *before* category C is actually low-risk, not as an afterthought. This
also directly bears on Part V.C: any speculative-parallel-routing scheme,
even one that only *reads* a shared snapshot tree from multiple worker
threads, needs this fix or a private tree per worker; it cannot skip it.

**Practical implication for Part V:** the portfolio approach (V.A) sidesteps
all three of IV.B.1–3 entirely, because each portfolio worker gets its own
whole-board copy, hence its own `SearchTreeManager`, hence its own tree
instances — there is no sharing to race on. The speculative approach (V.C)
does not get this for free and needs (a) the `node_stack` fix above, and (b)
either a private `ShapeSearchTree` per concurrent worker (bypassing
`SearchTreeManager`'s per-clearance-class cache) or a serialization discipline
that ensures only one worker's `AutorouteEngine` ever holds a given cached
tree at a time.

### IV.C `RoutingBoard` item list and the single linear undo history

**Claim verified, mechanism more specific than "linear" alone.**
`BasicBoard.item_list` (`board/BasicBoard.java:1630`,
`public final UndoableObjects item_list;`) is one `UndoableObjects` per board.
Internally it stores objects in a `ConcurrentSkipListMap`
(`datastructures/UndoableObjects.java:47`) — thread-safe *per key*, which is a
red herring — but every undo/redo/insert/delete operation reads and mutates a
single plain `int stack_level` field non-atomically:

```java
// UndoableObjects.java:140-146 (generate_snapshot)
public void generate_snapshot()
{
    ...
    ++stack_level;
```

with matching `--stack_level`/`++stack_level` in `undo`/`redo`/`delete`
throughout the file. The map being concurrent-safe does not make the *undo
history* concurrent-safe: `stack_level` is a single global generation counter
with no synchronization, so two threads calling `generate_snapshot()`/`undo()`
concurrently will race on it regardless of the map underneath. This confirms
"single linear undo history" precisely and explains *why* it's true even
though the storage layer looks concurrent at first glance.

`BatchOptRoute.opt_route_item` (`autoroute/BatchOptRoute.java:157,197,203`)
uses exactly this mechanism per candidate re-route:
`generate_snapshot()` → attempt → `pop_snapshot()` (keep) or `undo(null)`
(discard) — i.e. the *existing* postroute optimizer is already a serial
speculate/commit-or-revert loop over one shared history. Any scheme in Part V
that wants concurrent speculative attempts against the live board (not a
board copy) must either serialize commits through this exact mechanism (one
at a time) or bypass it entirely by working against per-worker board copies.

### IV.D `RoutingBoard.changed_area`

**Claim verified.** `RoutingBoard.java:1407`: `transient ChangedArea changed_area;`
— one field per board, set up by `start_marking_changed_area()`
(`RoutingBoard.java:171-180`, lazily allocates if null) and consumed/cleared by
`opt_changed_area` (`RoutingBoard.java:235-250`, sets it back to `null` at the
end). Purely additive within one pass (`join_changed_area`,
`RoutingBoard.java:182-191`) but there is exactly one accumulator per board;
concurrent connections on one board would need either a per-worker accumulator
merged at commit time, or (again) per-worker board copies.

### IV.E `MazeSearchAlgo.random_generator`

**Claim verified, and the entanglement is worth naming precisely.**
`random_generator` (`MazeSearchAlgo.java:1465`,
`private final java.util.Random random_generator = new java.util.Random();`)
is seeded per-search:

```java
// MazeSearchAlgo.java:86
random_generator.setSeed(p_ctrl.ripup_costs); // To get reproducable random numbers in the ripup algorithm.
```

It's read in exactly one place, `check_ripup`'s late-pass jitter
(`MazeSearchAlgo.java:1144-1151`), gated by
`this.ctrl.ripup_pass_no >= 4 && this.ctrl.ripup_pass_no % 3 != 0` — so its
effect is real but bounded to breaking repetitive rip/reroute loops in later
passes, not a dominant source of route variety by itself. It is already
per-`MazeSearchAlgo` (hence per-thread once each engine/search is
thread-confined), so it needs no structural change for correctness — but note
it is currently **seeded from `ripup_costs`, not from an independent seed
field**. For the portfolio approach (V.A) to get genuinely independent
samples per worker, either vary `ripup_costs` itself (which also changes
routing behavior, a confound) or — cleaner — add a dedicated seed field to
`AutorouteControl` and change line 86 to use it. Small change, worth doing
explicitly rather than piggybacking on a cost parameter.

### IV.F EDT coupling in the batch pass loop

`hdlg.screen_messages`/`hdlg.repaint()` calls threaded through
`BatchAutorouter`'s pass loop assume a single UI-facing thread is driving
progress reporting. `BatchAutorouterThread`/`InteractiveActionThread`
(`interactive/InteractiveActionThread.java:37`,
`extends Thread implements Stoppable`) already run the batch autorouter off
the EDT on its own dedicated thread, with `request_stop`/`is_stop_requested`
correctly `synchronized` (`InteractiveActionThread.java:84-89`) — so the
*existing* batch autorouter is not itself EDT-bound; the concern is only that
a *portfolio* of K such workers would need K sets of this reporting plumbing
(or a shared, synchronized aggregator) rather than K workers fighting over one
`screen_messages` object. This is straightforward UI-layer work, not a
correctness risk to the routing itself.

### IV.G Summary table

| State | Where | Shared across | Fix required for concurrency |
|---|---|---|---|
| `ItemAutorouteInfo` on `Item` | `Item.java:1209,1462` | all engines touching that item | move to per-engine map (~22 call sites, 0.5–1.5 days) |
| `Via.autoroute_drill_info` | `Via.java:167-194,277` | same | same refactor, same pass |
| Cached per-clearance-class `ShapeSearchTree` | `SearchTreeManager.java:197-233` | all engines with that clearance class | private tree per concurrent worker, or serialize |
| `MinAreaTree.node_stack` traversal stack | `MinAreaTree.java:253` | **every caller of any tree query on that instance, reads included** | make traversal state local/`ThreadLocal` (small, self-contained) |
| `RoutingBoard.item_list` / `UndoableObjects.stack_level` | `BasicBoard.java:1630`, `UndoableObjects.java` | whole board | serialize commits, or per-worker board copies |
| `RoutingBoard.changed_area` | `RoutingBoard.java:1407` | whole board (one pass) | per-worker accumulator + merge, or board copies |
| `MazeSearchAlgo.random_generator` | `MazeSearchAlgo.java:1465` | already per-search | add independent seed field (small) |
| UI reporting (`screen_messages`) | pass loop | one UI | per-worker reporting or synchronized aggregator |

---

## Part V — Approaches, ranked

### V.A — Portfolio / parameter sweep (recommended first, possibly sufficient alone)

**Mechanism:** run K independent full autoroute attempts, each against its own
copy of the board, each with a different RNG seed and/or a different point in
the parameter space (`AutorouteSettings.get_via_costs()`,
`set_preferred_direction_trace_costs`, `add_via_costs` per Part I.6, the ripup
schedule via `get_start_ripup_costs()`, `start_pass_no`). Score every result
with the `BoardScore` from Part III and keep the best board.

**Why it's the right first move, given the constraints:**
- **Zero shared state.** Each worker owns a full board copy; none of Part
  IV's hazards apply, because nothing is shared. No refactor is a
  prerequisite.
- **Zero algorithm change.** `MazeSearchAlgo` is untouched.
- **Directly targets the stated metric.** Since only final board quality is
  judged and reproducibility is explicitly not required (per the brief), "run
  several independent attempts and keep the best" is not a compromise
  substitute for "real" parallelism — it *is* the correct use of the freed-up
  requirement. Every other approach in this document has to justify itself
  against this baseline, not the other way around.
- **A working deep-copy primitive already exists.** `BasicBoard.clone()`
  (`board/BasicBoard.java:130-133`):

  ```java
  public BasicBoard clone()
  {
      return deserialize(this.serialize(false));
  }
  ```

  round-trips the whole object graph through `ObjectOutputStream`/
  `ObjectInputStream` (`BasicBoard.java:88-128`). `RoutingBoard implements
  java.io.Serializable` (`board/RoutingBoard.java:59`), and the fields that
  must *not* survive a copy — `Item.autoroute_info`, `Via.autoroute_drill_info`,
  `RoutingBoard.changed_area`, `RoutingBoard.autoroute_engine` — are already
  marked `transient`, so a cloned board starts with a clean autoroute slate
  for free. This is a real, already-tested-by-existing-use (it backs
  `get_hash()` and presumably an existing diff/undo-adjacent feature)
  mechanism, not something to build from scratch.
  **What's unverified:** its performance on a ~15k-item board. Serialization
  round-trips are not typically the fastest deep-copy mechanism in Java; for
  K=8 copies of a large board this could dominate wall-clock time before
  routing even starts. **Needs profiling before committing to it as the
  copy mechanism** — the fallback is a purpose-built deep-copy that
  reconstructs `UndoableObjects`/`SearchTreeManager` directly, which is more
  code but likely faster. Treat `clone()` as the first thing to *try*, not the
  final answer.

**Reasoning on expected benefit (not measured, stated as reasoning):** Part
II.5 argues net order and per-search RNG jitter can change the final board,
because per-connection optimality doesn't pin down a unique board-level
result. If that's right, K independent attempts sample K points from a
distribution of achievable boards, and `max` over K samples should improve
monotonically (in expectation) with K, with diminishing returns — the shape
of that curve (how much K=4 buys over K=1, whether K=16 is meaningfully better
than K=4) is unknown without running it. This is the single most important
number to measure early, because it determines whether V.A is "sufficient
alone" (per the brief's framing) or just a good first step.

**Concrete costs:**
- **Memory:** K × (board item count × per-item footprint + K search trees).
  For the reference board class mentioned in
  `docs/routing-improvements-plan.md` (~15k items), this needs a real number
  from profiling, not a guess — flagged for Part VIII.
- **Engineering:** extract `BoardScore` (Part III, small); decide and
  implement the copy mechanism (small if `clone()` suffices, medium if not);
  write a K-worker driver using a standard `ExecutorService`
  (**note: the codebase currently has zero uses of
  `java.util.concurrent.ExecutorService`/`Executors`** — all existing
  concurrency is hand-rolled `Thread` subclassing, e.g.
  `InteractiveActionThread extends Thread`
  (`interactive/InteractiveActionThread.java:37`) — so this is new
  infrastructure, not a reuse of an existing pattern, though a small and
  standard one); decouple the RNG seed per Part IV.E; wire a parameter-sweep
  policy (grid, random, or manually curated points) across workers. **Estimate:
  3–6 days** for a first working version with a fixed small parameter set,
  assuming `BoardScore` and exception-logging (Part II.5) are done first.

### V.B — Parallelize the embarrassingly-parallel read-only work (corrected)

Real candidates: `ClearanceViolations` construction
(`interactive/ClearanceViolations.java:47-56`), `calc_weighted_trace_length`
(`BatchOptRoute.java:224-253`, a pure fold over `item_list` with no tree
queries — this one genuinely is safe to parallelize as-is, since it never
touches a `ShapeSearchTree`), and the search-tree bulk build at import
(`SearchTreeManager.insert_all_board_items`, `SearchTreeManager.java:277-295`,
which inserts sequentially into a `MinAreaTree` — parallelizing the *inserts*
into one shared tree is a much bigger undertaking than parallelizing reads,
because `MinAreaTree.insert` mutates tree structure, not just traversal
state, and is out of scope for a first pass).

**Corrected risk assessment:** `ClearanceViolations` construction is **not**
low-risk as-is, per Part IV.B.3 — it races on `MinAreaTree.node_stack` the
moment two items' `clearance_violations()` calls run concurrently against the
same default tree. The fix (make the traversal stack local/`ThreadLocal`,
Part IV.B.3) is itself small and self-contained (4 files: `MinAreaTree`,
`ShapeSearchTree`, `ShapeSearchTree90Degree`, `ShapeSearchTree45Degree`), and
should be done **regardless** of whether this specific parallelization is
pursued, because it's a latent correctness bug independent of any new
threading — it just happens to matter most here. **Estimate: 0.5–1 day** for
the `node_stack` fix plus verification (this can be checked with a simple
concurrent-access unit test, not just by inspection), then the
`ClearanceViolations` parallelization itself is genuinely small (a parallel
stream or fixed thread pool over the item list, each thread doing
independent, now-actually-independent tree reads).

### V.C — Speculative parallel routing with serialized commit

**Mechanism (unchanged from the brief's framing, still the right shape):**
route N connections concurrently, each against a read-only-as-possible view of
the board; commit sequentially; on commit, re-validate the candidate against
whatever has changed since its snapshot; on conflict, re-route that one
connection serially. Batch by spatial disjointness — group pending
connections whose airline bounding boxes, expanded by clearance, don't
overlap — so conflicts are rare and most commits land without a serial
re-route. This is a throughput heuristic, not load-bearing for correctness:
a bad batching choice just means more serial fallback, not a wrong board.

**Hard prerequisites, now precisely enumerated (Part IV):**
1. `ItemAutorouteInfo` + `Via.autoroute_drill_info` off shared items and onto
   per-worker storage (IV.A, ~22 call sites, 0.5–1.5 days).
2. Either a private `ShapeSearchTree` per concurrent worker (don't go through
   `SearchTreeManager.get_autoroute_tree`'s shared cache, build/insert-all a
   fresh tree per worker from the same item set) or a discipline that
   guarantees only one worker ever holds a given cached tree at a time
   (IV.B.1–2). Given K workers routing concurrently, a private tree per
   worker is simpler to reason about and avoids reintroducing serialization
   through the back door; its cost is one more full tree build per worker
   (bulk build is already a `SearchTreeManager.insert_all_board_items` loop,
   `SearchTreeManager.java:277-295` — existing code, just needs to run once
   per worker instead of once per board).
3. The `MinAreaTree.node_stack` fix (IV.B.3) — needed even with private
   trees, because within one worker's own tree, nothing currently prevents
   another latent reentrancy bug, and it's needed unconditionally for V.B.
4. A commit path that serializes through (or replaces) the single
   `UndoableObjects`/`stack_level` history (IV.C) — either literally take turns
   calling `generate_snapshot()`/commit/`undo()` one worker at a time (simple,
   but re-serializes exactly the step you were trying to parallelize), or give
   each worker a private board copy for its *speculative* attempt and replay
   only the winning diff onto the real board serially (more engineering, but
   preserves genuine overlap between "search extra connections" and "commit
   the last one").
5. A merge strategy for `RoutingBoard.changed_area` (IV.D) across workers
   whose candidates land.

**Assessment:** this is the "real" fine-grained answer and the natural next
step *if and only if* V.A's diminishing-returns curve turns out to be steep
(i.e., the portfolio approach stops paying off well before all available
cores are used). It is meaningfully more engineering than V.A — full
prerequisite list above, plus the batching heuristic, plus the conflict-detect
step, plus a decision on commit strategy in point 4. **Estimate: 2–4 weeks**
for a first working version, dominated by prerequisites 1 and 4, assuming V.A
and the `BoardScore`/exception-logging groundwork are already in place.
Reproducibility being off the table (per the brief) removes what would
otherwise be this approach's most annoying constraint: commits can land in
whatever order they complete, with no barrier waiting for "thread 3's turn" —
that simplification is real and already assumed above.

### V.D — Rejected: parallelize inside one maze search

Parallel best-first search (e.g., partitioning the open list across threads,
or speculative duplicate expansion with reconciliation) brings duplicate-work
and load-balancing problems that are a research topic in their own right, and
doing it here would mean rewriting `MazeSearchAlgo`'s core expansion loop —
directly against the brief's hard constraint that the existing algorithm stay
intact. It's also the approach least likely to pay off: expansion frontiers
per connection are typically small (bounded by the local free-space
decomposition around one net), so there's comparatively little to parallelize
within a single search compared to running many searches concurrently.
Rejected, not "deferred" — nothing here changes that conclusion as more
information becomes available, short of the whole cost model changing.

---

## Part VI — Interactive push-and-shove: a different problem

Manual push-and-shove is explicitly valued by the user and is **latency-bound,
not throughput-bound** — the question is "does the screen update within one
frame while the user is dragging," not "how many boards can we route per
minute." This is a different engineering problem from Parts I–V and needs its
own answer.

### VI.1 The pipeline runs synchronously on the thread that receives the mouse event

`DynamicRouteState.mouse_moved()` (`interactive/DynamicRouteState.java:42-46`):

```java
public InteractiveState mouse_moved()
{
    super.mouse_moved();
    return add_corner(hdlg.get_current_mouse_position());
}
```

`add_corner` (`interactive/RouteState.java:331-333`) calls
`route.next_corner(p_location)` directly, and `Route.next_corner`
(`interactive/Route.java:124`) calls `board.insert_forced_trace_segment(...)`
(`Route.java:178-181`) **synchronously, inline, on whichever thread dispatched
the mouse-motion event** — which for a Swing mouse listener is the EDT.
Confirmed: **`SwingUtilities.invokeLater`/`invokeAndWait`/`EventQueue`/
`SwingWorker` do not appear anywhere in this codebase** (checked by grep
across the whole source tree). There is no async escape hatch today; every
shove computed while dragging blocks the EDT for its full duration, which is
exactly the freeze users see on complex boards.

### VI.2 Time-limit coverage is real but inconsistent across the four algorithms

The brief's claim that these "accept no `Stoppable` and have incomplete
`TimeLimit` coverage" is **confirmed, with the specifics worth stating
precisely** because they change what a fix needs to cover:

| Class | Method | `TimeLimit` param? | `Stoppable` param? |
|---|---|---|---|
| `ShoveTraceAlgo` | main `check(...)` (`board/ShoveTraceAlgo.java:62`) | yes | no |
| `ForcedPadAlgo` | main `check(...)` (`board/ForcedPadAlgo.java:67`) | yes | no |
| `ForcedViaAlgo` | `check(...)` (`board/ForcedViaAlgo.java:99-100`) | **no** | no |
| `ForcedViaAlgo` | `check_layer(...)` (`ForcedViaAlgo.java:52`) | **no** | no |
| `MoveDrillItemAlgo` | `check(...)` (`board/MoveDrillItemAlgo.java:55-57`) | yes | no |

`ForcedViaAlgo.check` internally calls
`forced_pad_algo.check_forced_pad(..., null)` (`ForcedViaAlgo.java:91-92`),
passing a literal `null` where a time limit could go — **dragging or forcing a
via has zero worst-case time bound today**, unlike trace-shove and
drill-item-move, which at least bound the top-level call (though not
necessarily every recursive step inside it — that would need tracing every
recursive call site inside `ShoveTraceAlgo`/`MoveDrillItemAlgo` against every
`TimeLimit` check, which is beyond what source inspection alone can confirm;
flagged for Part VIII). None of the four accept a `Stoppable`, so even where a
`TimeLimit` exists, there's no way for a user action (e.g., pressing Escape
mid-drag) to cancel an in-flight shove computation early — it can only time
out on the clock, not be told to stop.

**Relationship to concurrent Task 4:** a parallel effort is fixing the
immediate EDT-stall symptom (per the coordinating plan). That is the right
near-term fix and is not in conflict with anything here. This section is
scoped to the architectural question behind it: *can* interactive shoving
become concurrent or speculative, and is it worth it.

### VI.3 Is concurrency the right fix here at all?

**Reasoning, not a recommendation to implement immediately.** Three shapes are
worth naming, in increasing order of complexity, none of them started:

1. **Move the computation off the EDT, keep it synchronous from the user's
   point of view.** Run `insert_forced_trace_segment` on a background worker
   thread per drag gesture, post the result back to the EDT for repaint. This
   doesn't parallelise the algorithm at all — it's the same single-threaded
   shove computation, just not blocking paint/input dispatch while it runs.
   This is the most valuable near-term latency fix and requires none of Part
   IV's hazards to be resolved, **provided only one such background
   computation is ever in flight at a time** (i.e., a new drag/mouse-move
   event while one is still running either cancels-and-restarts or is
   dropped, never runs concurrently with the previous one against the live
   board) — which itself needs the `Stoppable` support noted in VI.2 to cancel
   a stale in-flight computation cleanly rather than letting a stale result
   land after a newer one. This is architecturally simple compared to Parts
   IV–V and is probably the right first move for this half of the problem,
   independent of the batch-autorouter work above.
2. **Speculative shove-ahead:** while the mouse is moving, speculatively
   compute the shove result for a *predicted* next position (e.g.,
   extrapolated from recent mouse velocity) on a background thread, so that
   if the user's actual next position matches or is close, the result is
   already available. This is genuinely concurrent (prediction thread vs.
   commit-on-actual-event), buys latency at the cost of wasted speculative
   work on mispredictions, and — critically — touches the exact same shared
   board state as a live edit would, so it needs a private/scratch board view
   for the speculative computation (echoing V.C's snapshot idea, but at
   drag-frame timescales rather than whole-connection timescales) plus a
   cheap way to detect "prediction matched, promote it" vs. "didn't match,
   discard and recompute for real." This is meaningfully more engineering than
   (1) and its payoff depends entirely on how predictable drag paths actually
   are — **not something to size from source alone; would need instrumenting
   real interactive sessions** (Part VIII).
3. **Parallel shove exploration** (e.g., trying several shove strategies —
   different max recursion depths, different directions — concurrently and
   keeping whichever finishes first or looks best) is the least promising of
   the three: shove computations are already fast relative to a full maze
   search, the "quality" of a shove result during a live drag is judged
   instantly and visually by the user (who will just keep dragging if it
   looks wrong), and running several variants concurrently multiplies exactly
   the unbounded-recursion risk flagged in VI.2 (`ForcedViaAlgo` having no
   time limit at all) across N threads instead of one. Not recommended.

**Recommendation for this half of the problem:** (1) first — it's a latency
fix, not a parallelism project, and it's a prerequisite for (2) even being
safe to attempt (you need clean cancellation before you can have two
generations of speculative work in flight without one clobbering the other).
Do not start (2) or (3) before profiling real drag sessions to see whether
shove computation time is actually the bottleneck users feel, versus repaint
cost, versus something else entirely (Part VIII).

---

## Part VII — Recommended sequence

1. **Log the swallowed exceptions** in `autoroute_item`/`autoroute_pass`
   (`BatchAutorouter.java:379-382`, `:293-297`) — one line each, `FRLogger`
   already imported in the file. Do this before trusting any A/B comparison
   between serial and parallel results, since a swallowed race exception would
   look identical to a legitimate routing failure. *(hours)*
2. **Extract `BoardScore`** (Part III) as a pure function over a board. Every
   later step needs this to compare results. *(hours – 1 day)*
3. **Fix `MinAreaTree.node_stack`** to be local/`ThreadLocal` instead of a
   shared instance field (Part IV.B.3, 4 files: `MinAreaTree`,
   `ShapeSearchTree`, `ShapeSearchTree90Degree`, `ShapeSearchTree45Degree`).
   Worth doing on its own merits — it's a latent correctness bug — and is a
   prerequisite for V.B and V.C. *(0.5–1 day + test)*
4. **Profile a full serial `-rm finish` run** on the reference board (per
   `docs/routing-improvements-plan.md`'s ~15k-item, 438-net board) to find
   where time actually goes before assuming parallelism will help at all.
   *(hours, but blocks confident sizing of everything after this)*
5. **V.A — the portfolio router.** Given the reproducibility decision, this is
   the highest value-per-hour parallel work and may be the last one needed.
   Build the K-worker driver, decide the board-copy mechanism (try
   `BasicBoard.clone()` first, measure it), decouple the RNG seed (Part IV.E),
   wire a small initial parameter sweep. *(3–6 days)*
6. **Measure the diminishing-returns curve** from step 5 (score vs. K) on the
   reference board and at least one other board shape (denser, sparser) before
   deciding whether V.C is worth starting. This is the single most important
   decision gate in this whole plan.
7. **Only if step 6 shows real headroom beyond what V.A captures: V.C**,
   starting with the `ItemAutorouteInfo`/`Via.autoroute_drill_info` refactor
   (Part IV.A) on its own merits, then the private-tree-per-worker mechanism,
   then the commit/conflict machinery. *(2–4 weeks)*
8. **Independently, for interactive push-and-shove: VI.3's option (1)** — move
   shove computation off the EDT with proper cancellation — after adding
   `Stoppable` support to `ShoveTraceAlgo`/`ForcedPadAlgo`/`ForcedViaAlgo`/
   `MoveDrillItemAlgo` and closing the `ForcedViaAlgo` `TimeLimit` gap (Part
   VI.2). This track is independent of 1–7 and can proceed in parallel with
   them; it shares no code with the batch-autorouter parallelism work beyond
   the general principle of "don't block the thread that has to stay
   responsive."

Steps 1–4 are shared groundwork for everything after them and should not be
skipped even if the team is confident V.A is the right answer — without a
score and a profile, "it got better" is an opinion, not a measurement.

---

## Part VIII — What this document could not determine without profiling or instrumentation

Stated plainly, as the brief requires:

- **The shape of the diminishing-returns curve for V.A** — how much a 2nd,
  4th, 8th independent attempt buys over the 1st, on realistic boards. This is
  the load-bearing number for the whole plan and can only come from running
  it.
- **`BasicBoard.clone()`'s actual cost** on a board with thousands of items —
  whether serialization round-trip is fast enough to be the copy mechanism for
  V.A, or whether a purpose-built deep copy is needed instead.
- **Per-board-copy memory footprint** at realistic item counts, which bounds
  how large K can practically be on a given machine.
- **Where serial time actually goes** in a full `-rm finish` run (maze search
  itself vs. room construction vs. pull-tight vs. postroute optimization vs.
  something else) — without this, it's unknown whether *any* form of
  parallelism addresses the actual bottleneck, or whether, say, the postroute
  optimizer (Part III, itself fully serial via the single undo history) turns
  out to dominate wall-clock time on some boards, in which case it would need
  its own parallelization story not covered by V.A–V.D as scoped (V.A's "many
  independent full attempts" already includes each attempt's own postroute
  pass, so this mainly matters for whether postroute time makes each portfolio
  worker's total wall-clock cost much higher than the initial-route phase
  alone).
- **Whether `TimeLimit` checks inside `ShoveTraceAlgo`/`MoveDrillItemAlgo`
  cover every recursive call site**, or only the top-level entry — source
  inspection confirmed the top-level parameter exists (Part VI.2) but tracing
  every recursive path against every check was out of scope for this pass and
  would need either careful call-graph tracing or an instrumented worst-case
  test.
- **Whether drag paths during interactive push-and-shove are predictable
  enough** for speculative shove-ahead (Part VI.3, option 2) to pay off —
  needs instrumented real sessions, not source reading.
- **The actual variance in board quality between different net orderings and
  RNG draws**, which this document argues for from the structure of the code
  (Part I.5, Part II.5) but has not measured. If variance turns out to be
  small in practice, V.A's value proposition weakens considerably and V.C
  becomes relatively more attractive sooner than Part VII's sequencing
  assumes.

---

## Appendix — Verification log against the brief's six numbered claims

| # | Claim | Verdict | Note |
|---|---|---|---|
| 1 | A* via `sorting_value = expansion_value + destination_distance.calculate(...)`, open list a `TreeSet<MazeListElement>` ordered by `sorting_value` | **Confirmed exactly** | `MazeSearchAlgo.java:598` (within cited 595-604 range), `MazeListElement.java:57-59` |
| 2 | Expansion space is maximal convex free-space tiles from `ShapeSearchTree.complete_shape`, joined by `ExpansionDoor`s | **Confirmed**, and extended | `ShapeSearchTree.java:643`; additionally found that completed rooms are inserted back into the same tree as leaves (`AutorouteEngine.java:467`) and removed at teardown (`AutorouteEngine.java:255`) — a mutation path not mentioned in the original claim, material to Part IV.B |
| 3 | Rooms built lazily during search via `complete_neigbour_rooms` at `MazeSearchAlgo.java:235` | **Confirmed exactly** | line matches precisely |
| 4 | `DestinationDistance.calculate` is an admissible box-distance lower bound accounting for min via cost and per-layer trace costs | **Confirmed** | `DestinationDistance.java:136-454`; noted `min_normal_via_cost` is a mutable field, safe only because the object is single-owner today |
| 5 | Ripup/shove/via-add are priced edges in one search; ripup enters cost at `MazeSearchAlgo.java:358`, weighted by `get_half_width()`; shoving at `:348-350` → `shove_trace_room` → `MazeShoveTraceAlgo.check_shove_trace_line` | **Confirmed exactly**, all cited lines match | This is the strongest and best-supported claim in the brief |
| 6 | `AutorouteControl.add_via_costs[from].to_layer[to]` read at `MazeSearchAlgo.java:869`, zeroed at `AutorouteControl.java:83`, written by nothing | **Confirmed exactly**, verified by exhaustive grep | No other writer exists anywhere in the tree |

**None of the six numbered claims were wrong.** They held up under direct
citation-by-citation verification. The corrections and additions this review
contributes are all in the *surrounding* material the brief flagged as
"enumerate precisely" and "verify each" without giving numbered claims for:

1. **`Via.autoroute_drill_info`** (`Via.java:277`) is a second, structurally
   identical per-item autoroute cache that the `ItemAutorouteInfo` enumeration
   omitted — must move alongside it (Part IV.A).
2. **`MinAreaTree.node_stack`** (`MinAreaTree.java:253`) is a shared,
   unsynchronized traversal stack used by every tree query, including reads —
   this means `docs/routing-improvements-plan.md`'s characterization of
   `ClearanceViolations` construction as "low risk" embarrassingly-parallel
   read-only work is **incorrect as stated**; it needs a small, independent
   fix first (Part IV.B.3, Part V.B).
3. **The lazy room decomposition writes into the shared per-clearance-class
   search tree during the search itself**, not only at commit time
   (`AutorouteEngine.java:467`) — sharpens claim 2/3's implications for why
   concurrent engines on the same clearance class can't coexist without a
   private tree per worker (Part IV.B.2, Part V.C prerequisites).
4. **`UndoableObjects`'s map is concurrent-safe but its generation counter
   (`stack_level`) is not** — worth stating precisely because "single linear
   undo history" could otherwise be read as "obviously not thread-safe,
   nothing to check," when in fact the storage layer's superficial
   concurrency-safety is a trap for anyone assuming the whole class is safe
   because one field inside it uses a concurrent collection.
5. **A working whole-board deep-copy already exists**
   (`BasicBoard.clone()`, `BasicBoard.java:130-133`) — not a correction, but a
   material discovery for V.A that the brief didn't know to ask about, and
   that meaningfully de-risks the recommended first step.
