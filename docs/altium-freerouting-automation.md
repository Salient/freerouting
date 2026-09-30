# Driving freerouting from inside Altium (`RouteWithFreerouting`)

How far the export → route → import round trip can be automated from a
DelphiScript running inside Altium Designer, what is confirmed vs. assumed,
and what one manual step is still required.

**None of this has been run against a live Altium.** This environment has no
Altium and no Windows. Everything below is either grounded in this repo's own
Java source (confirmed by reading the code that writes the files this script
reads/produces) or flagged explicitly as an assumption that needs checking on
a real workstation.

## The short answer

Yes, most of it. `RouteWithFreerouting` (in `altium-scripts/ExportFrpcb.pas`)
exports the board, launches freerouting, and waits for it to finish, all from
one Run Script menu item, with no dialogs blocking an unattended run. It
cannot also trigger Altium's own Specctra-file importer — no documented,
scriptable process id for that action could be found — so it stops one step
short and tells you exactly what to do:

```
File > Import Wizard > Specctra Session or Route File, then select:
<path>.rte
```

That remaining step is not a workaround or a guess: it is the same Specctra
route importer Altium ships natively, which this project has already spent
several commits making compatible with (see "The import leg" below).

## What `RouteWithFreerouting` does

1. Calls the same export logic as the existing `ExportFrpcb` entry point,
   with its two confirmation dialogs suppressed (`DoExportFrpcb(True)`), so a
   `<board-name>.frpcb.json` is written next to the board with no prompt.
2. Resolves a launcher path — `FREEROUTING_LAUNCHER` environment variable if
   set, otherwise the `FREEROUTING_LAUNCHER_DEFAULT` constant at the top of
   the procedure — and checks it exists, with a clear error naming both ways
   to fix it if not.
3. Runs that launcher (`freerouting.cmd`, repo root) with
   `-de <frpcb.json> -do <rte> -rm reroute`, using absolute, always-quoted
   paths for both.
4. Waits for it to actually finish and checks its real exit code (see
   "Launching and waiting" below).
5. Checks the resulting `.rte` exists and is non-empty.
6. Reports the outcome in one final dialog, with the one manual step left to
   do if everything up to that point succeeded.

## Launching and waiting

The pattern usually cited for Altium scripting — `Client.SendMessage` with
`ResetParameters`/`AddStringParameter`/`RunProcess` — dispatches Altium's own
*registered* commands (the same mechanism behind menu items like
`PCB:Place`). It is not a documented way to launch an arbitrary third-party
executable, and no confirmed process id for "run an external program and
wait for it" was found in the time available. Guessing at one and shipping
it would be exactly the "plausible-looking script that fails in front of the
user" this project explicitly wants to avoid.

Instead, `RouteWithFreerouting` uses:

```pascal
WSHShell := CreateOleObject('WScript.Shell');
ExitCode := WSHShell.Run(CmdLine, 1, True);
```

`WScript.Shell.Run`'s third argument (`bWaitOnReturn`) is Microsoft-documented
Windows Script Host behaviour: when `True`, `Run` blocks until the child
process exits and returns its real exit code. This sidesteps the "does it
block, and if not how do I wait" question entirely — it is not Altium-specific
plumbing with uncertain semantics, it is the same OS-level wait any script
host gets. `CreateOleObject` (late-bound OLE Automation) is itself a
long-established DelphiScript capability — the same mechanism real-world
Altium scripts use to drive Excel, Word or Outlook — but this specific call
has not been exercised against a live Altium session here.

Because `Run` only returns after the process exits, the "poll for the output
file's existence and stability" concern (a partially-written file being worse
than a missing one) mostly does not apply: there is no process still writing
the file by the time `Run` returns. `RouteWithFreerouting` still loads the
`.rte` fully and checks it is non-empty before reporting success, as a cheap
extra check against the unlikely case of a 0-byte file surviving a 0 exit
code.

## The import leg

This is the part that is **not** scripted, and the reasoning for that is the
main point of this document.

Altium's native Specctra route/session importer demonstrably exists and
works — not a guess, this repo's own git history shows it being debugged
against a live Altium session:

- `SessionFile.java`'s `write_route_file`/`write_wire`: "Altium's Specctra
  route importer rejects LF-only route files ... it requires CRLF line
  endings" and "Emit the whole wire on ONE line ... Altium's Specctra route
  importer cannot parse a wire whose `(path ...)` coordinates are split
  across multiple lines - it silently drops every such trace".
- Recent commits on this branch: *"Never write a trace that has collapsed to
  a single point"*, *"Extend wire ends into pads instead of moving them"* —
  both framed as fixes for what Altium's importer does with the file, which
  only makes sense if that importer was actually run.
- The untracked `target.do`/`target.dsn`/`target.rte` files present in this
  workspace's root at the start of this task are Altium's own "Template Do
  File For Altium Designer -> Specctra Autorouter" mechanism in use — further
  evidence of the manual Specctra hand-off being exercised today. (Not
  committed here: `target.do` names a machine-specific path, and the
  `.dsn`/`.rte` carry real board data.)

So the importer is real and reachable — through Altium's interactive
**File > Import Wizard > Specctra Session or Route File**. What could not be
confirmed is a way to *trigger that action from a script*. This is a
different question from the launch/wait question above: it is about
Altium's own internal command surface, not a third-party process, so
`WScript.Shell.Run` does not apply here at all. Sources consulted:

- This repo's own Java source (`SessionFile.java`, `Network.java`,
  `WriteScopeParameter.java`) — confirms the *writer* side and the file
  format precisely, but says nothing about Altium's scripting API for the
  *importer* side.
- Web search in this environment has no live results (the tool falls back to
  unlabelled model recollection, which produced a plausible-sounding but
  unverifiable process id on request — not used here for exactly that
  reason). Direct fetches of specific Altium documentation URLs mostly
  404'd; nothing that actually loaded named a Specctra-import process id.
- A separate, unversioned sibling project on this machine
  (`reerouting/altium/VERIFICATION_STATUS.json`) has live-Altium-confirmed
  that constructing `Track`/`Via` PCB objects directly via
  `PCBServer.PCBObjectFactory` + `Board.AddPCBObject` (wrapped in
  `PCBServer.PreProcess`/`PostProcess`) works for tracks (via creation
  specifically was *not* confirmed even there). That is a real, viable
  alternative technique for closing this gap completely — but it means
  parsing the `.rte` grammar by hand in DelphiScript and re-implementing what
  Altium's own importer already does, including details this project has
  already spent effort getting Altium to accept (CRLF, one-line wires,
  degenerate-wire handling) plus new ones (padstack-to-via-size resolution,
  quoted-identifier handling for net names with spaces/parens). That is a
  bigger, materially riskier undertaking than this task's scope, entirely
  untested here, and was deliberately not attempted — writing it would have
  meant shipping code with no real confidence it works, which is the one
  thing this task asked not to do.

If someone finds a confirmed process id for the Specctra importer (for
example by turning on Altium's process-call logging while running the import
manually, then reading the log), replacing the final `ShowMessage` in
`RouteWithFreerouting` with a `RunProcess` call is a small, contained change.

## Where the launcher lives

`freerouting.cmd` (repo root) is the Windows counterpart to the repo's
`./freerouting` bash script. It intentionally does *not* copy that script's
rebuild-if-stale behaviour (assuming a working Gradle/JDK setup on an Altium
workstation is a bigger ask than on a dev/CI box) or its xvfb fallback
(meaningless on Windows, which always has a desktop session outside of a
Session-0 service context). It does keep the one piece that matters
regardless of OS: running from a private temp-directory *copy* of the jar,
never from `build\libs` directly, so a rebuild in another window cannot pull
the rug out from under a running instance (see the bash script's own comment,
reproduced in `freerouting.cmd`, for the exact `NoClassDefFoundError` failure
mode this avoids).

`RouteWithFreerouting` finds it via, in order:

1. The `FREEROUTING_LAUNCHER` environment variable, read through
   `WScript.Shell.ExpandEnvironmentStrings` (the same OLE object already
   needed for the launch itself, rather than a second, separately-unconfirmed
   environment-variable API).
2. `FREEROUTING_LAUNCHER_DEFAULT`, a constant at the top of the procedure —
   edit it to point at wherever `freerouting.cmd` lives on your workstation.

Either way, a missing launcher is a clear dialog naming both ways to fix it,
not a silent no-op.

## A hazard this script deliberately avoids

`StartupOptions.java` matches flags with `startsWith` and, for any flag that
takes a value, silently keeps the default if the next token starts with
`-` — there is no error, the flag is just dropped. `RouteWithFreerouting`
always passes absolute, quoted paths for `-de`/`-do`, and a literal
(`reroute`) for `-rm`, specifically so nothing it constructs can trip this.
A future edit to this procedure that builds a flag value dynamically should
keep that guarantee.

## What is confirmed vs. assumed, summarized

Confirmed (grounded in this repo's Java source or a real generated sample):

- `.rte` grammar for wires/vias/nets/resolution, from an actual freerouting
  run in this environment against `tests/pic_programmer.dsn`.
- For FRPCB-sourced boards specifically, resolution is `(resolution mil
  100)` (`Communication communication = new Communication(Unit.MIL, 100,
  ...)` in `FrpcbFile.java`) — i.e. `mils = raw / 100`.
- No coordinate sign flip or offset between Altium's board space and the
  `.rte` output for this pipeline (`CoordinateTransform` in
  `write_route_file` is constructed with base `(0, 0)`; `FrpcbFile.java`
  reads `x`/`y` straight through).
- Via padstack names in a `.rte` produced from an FRPCB-sourced board are
  `via_<outerMils>_<holeMils>` — the exact string `WriteViaRules`/
  `PadstackKey` already write into `padstacks`/`vias` in the JSON, preserved
  verbatim through `FrpcbFile.java`'s `read_via` (`Padstack.name`) and
  echoed unchanged by the Specctra writer (`IdentifierType`/
  `via_padstack.name` in `SessionFile.java`). This was worked out in case a
  from-scratch importer is written later; `RouteWithFreerouting` itself does
  not need it, since it does not parse the `.rte` at all.
- Altium's native Specctra route importer exists, is reachable via File >
  Import Wizard, and this project has iteratively fixed writer-side
  compatibility issues against it (see "The import leg").

Assumed, not tested against live Altium:

- `CreateOleObject('WScript.Shell')` and its `.Run`/
  `.ExpandEnvironmentStrings` methods are available in this DelphiScript
  engine. Standard, long-established DelphiScript/OLE capability, but not
  exercised here.
- `FileExists` and `TStringList.LoadFromFile` behave as in standard Pascal.
  `TStringList.Create`/`.Add`/`.SaveToFile`/`.Free` are already relied on
  elsewhere in `ExportFrpcb.pas`; `.LoadFromFile`/`.Count` are the same
  class's natural counterparts but are new to this script.
- No scriptable process id exists for triggering Altium's Specctra importer.
  Absence of evidence, not evidence of absence — see "The import leg" for
  what was actually checked.
