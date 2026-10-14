# Status / handoff

Last updated 2026-10-14. Branch `master` at `d3cbc820`, pushed. 104 tests green.

This is the resume point for the Altium round trip on the B500 ESAM board
(`165-6689-800`), a 6-layer high-voltage safe-and-arm board heading for the
FIST-45 Layout Review.

## Standing constraints — read before touching anything

1. **Never commit board data.** No `Constraints.xml`, no `.dsn`/`.rte`/`.ses`,
   no net lists, nothing from `/tmp/b500*`. Net names are export-controlled
   (`DETONATE`, `DETONATE RTN`, `RELEASE CONSENT`) and `origin` is a
   GitHub-hosted fork of a public project. Test fixtures are synthetic
   (`src/test/resources/.../constraints-sample.xml`) or upstream's
   `tests/*.dsn`. The real board files `target.do`/`target.dsn`/`target.rte`
   sit in the working tree and are gitignored — leave them that way.
2. **Do not save the Altium board** after importing an `.rte` until the result
   has been inspected, so a bad import stays discardable.
3. **Mixed line endings.** Java sources here are a mix of LF and CRLF. Preserve
   each file's existing endings; a scripted rewrite that normalises them
   produces a 600-line diff for a 30-line change.
4. **Never rebuild while a JVM is reading the build jar.** Classes load lazily
   for the life of a process, so overwriting `build/libs/*.jar` breaks the
   running instance with an arbitrary `NoClassDefFoundError`. The `./freerouting`
   launcher already copies the jar to a private snapshot before running. (This
   bit once: a rebuild killed the user's running Windows instance.)

## Where the round trip stands

Working end to end: Altium export → `.frpcb.json` → freerouting → `.rte` →
Altium import, with clearances from `Constraints.xml`.

| Metric | Was | Now |
|---|---|---|
| Import warnings | 171 | 4 |
| Clearance violations | 2799 | 825 |
| Pad collisions | 85 | 11 |
| Transposed padstacks | 1 | 0 |
| Incompletes (finish mode) | 400 | 0 |

Baseline to beat on the reference board: 10,526 wires, 1,031 vias, 186 closed
loops, 0 degenerate wires.

Standing regression:

```
./freerouting -de <board>.frpcb.json -dc Constraints.xml -rm verify -do report.txt
./freerouting -de <board>.frpcb.json -dc Constraints.xml -rm finish -mt 180 -do out.rte
```

Use `--headless` for any test run — otherwise the window steals keyboard focus
under WSLg. A first run may show a yes/no "overwrite stored rules" dialog;
`-rm` batch paths suppress it, but don't mistake it for a slow run when
benchmarking.

## Decision waiting on the user

**Pull-tight region default.** `Settings.java:49` sets
`trace_pull_tight_region_width = Integer.MAX_VALUE`, so pull-tight pass 2 runs
unclipped — measured at 491 ms, which is the interactive push/shove freeze the
user reported. Clipping it would make manual routing feel responsive but
changes pull-tight behaviour for everyone. It does **not** affect the
autorouter. Asked three times, never answered — ask once more before assuming
either way.

## Open work, roughly in value order

### Parallel routing — unblocked, not started
The single-core ceiling is measured: a full 1897-connection from-scratch run
used **111% CPU over 15 minutes**. The blocker is gone (`MinAreaTree.node_stack`
is now a local per traversal, with `SearchTreeConcurrentReadTest` proving the
old shared field corrupted concurrent reads).

Design is written up in `docs/autorouter-parallelism.md` (1155 lines). The
recommended first move is the **portfolio approach**: K independent routers with
varied seeds and weight schedules, keep the best `BoardScore`. The user has
explicitly said **determinism is not required** — only final board quality
(aesthetics, length, via count) matters, which is what makes the portfolio
viable.

Remaining hazard for anything finer-grained: `ItemAutorouteInfo` hangs off each
shared `Item` (`Item.java:1209`), so two concurrent searches on one board still
corrupt each other silently.

### Wave 2 features
- **Live clearance / permitted-area display while routing.** Scope confirmed
  with the user: current trace, current layer only. Much of it exists —
  `ShapeSearchTree.complete_shape` plus `CompleteFreeSpaceExpansionRoom.draw`,
  and `ExpandTestState` (the `'e'` key) is already a working viewer. The work is
  seeding from a cursor-local box and hanging it in `Route.draw`.
- **Automatic neck-down for any net, with separately settable weights** per
  category. **Hard requirement: a per-net width floor must land in the same
  change as the scope widening.** Today's only floor is
  `Pin.get_trace_neckdown_halfwidth` (`Pin.java:625`), derived from pad
  geometry with an absolute floor of one internal unit — no relation to a
  manufacturing minimum or current capacity. Widening neck-down beyond
  pin-local while keeping that floor would let the router thin
  current-carrying copper on a high-voltage board arbitrarily.

### Known board issues, not yet diagnosed
- **290 of the 825 remaining violations are board-outline against a 100 mil
  rule.** Suspected `CHASSIS` over-constraint from FRPCB flattening edge
  clearance to a single scalar.
- **11 pad collisions at 45°/135°/200°** (Q8, U26). The orthogonal case is
  fixed; these non-orthogonal pads are a separate mechanism.
- **J5's own pads overlap by 1–2 mil** on a 10 mil rule near (11810, 13300).
  Pre-existing in the source board — for the user's eye, not a tool bug.

### Reachability check — residual gap
`RoomReachabilityAlgo` now models ~99% of connections (was ~19%). On the
stripped board: 3021 attempted, 2614 found a corridor, 12 proved blocked, 9 not
modellable, and **386 ran out of budget** — now the dominant gap. Caps are
`MAX_ROOMS = 2000` (line 198) and `TIME_LIMIT_MILLISECONDS = 500` (line 218).
Budget exhaustion fails open (reports reachable), so raising these can only find
more blocked connections, never create false positives.

### Lower priority
- Port the Constraints.xml reader to the sibling `reerouting` /
  `freerouting-rs` project. (`reerouting` is a real sibling project, not a typo
  for this one — an agent once tried to "fix" it.)
- `fix/shapeless-padstack-parse` (`d4b78005`) is **not mergeable**: it tracks
  upstream's rewritten `app.freerouting` package tree, 2054 commits diverged
  from our `eu.mihosoft` fork, and its files don't exist here. Its NPTH fix is
  conceptually superseded (we now insert non-plated holes as keepouts). Porting
  would be a re-implementation, not a merge.

## GUI checks only the user can do

1. Does the pass number render in the status bar during an autoroute run?
2. During a manual push/shove, does the blocking-obstacle highlight appear
   along with its status text? (`hilight_routing_obstacle` now defaults on.)
3. Is the status bar readable at small window sizes without clipping?
