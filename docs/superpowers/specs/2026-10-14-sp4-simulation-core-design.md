# Sub-project 4: Simulation core (off-screen wandering and hordes) — design

Status: approved in conversation 2026-10-14; written spec awaiting user review.
Branch: `sp4-simulation-core`. Builds on sub-project 2 (world map) and sub-project 3 (population takeover).

## 1. Goal

Every zombie the mod holds off screen moves. Wanderers wander, hordes form, follow, merge, shed members and dissolve,
**only by their own wandering, sight and hearing — never a director** (D5, D19). Over in-game weeks and months this
should be able to produce the stated goals in `design.md` section 1 (a city horde circling Louisville, a horde
drifting down the highway) without any rule that sends zombies anywhere.

The population is whatever the sandbox makes it (sub-project 3 seeding, growth, respawn). The core must work for any
size: tens of thousands up to 1M+ held zombies, without costing frame rate.

### In scope
Wandering (momentum, terrain preference, local avoidance, map edges), horde records, joining, following, merging,
break-aways, dissolving, stragglers, horde moaning (the first piece of the sound model) and one debug test sound,
levels of detail on a background thread, saving off the main thread, conservation checks, switching off vanilla
rally groups, save format 3, debug map/commands, laying horde members out when their area loads.

### Out of scope (later sub-projects)
- Sub-project 5: the full sound physics (gunshots incl. Louder Gunshots up to ~850 tiles, meta events, helicopter,
  thunder, cars), the horde split rule for sounds, lights/generators, and **letting the player hear a horde before
  it loads**.
- Sub-project 6: real (on-screen) zombies using the mod's wandering and joining (same sight rule), hordes walking
  across the loaded edge both ways, columns of real zombies. Until then real zombies behave as vanilla (minus rally
  groups).

## 2. Rules decided by the user (Q1 and Q8, 2026-10-14)

| Rule | Decision |
|---|---|
| Horde forms | 10 or more zombies **moving together** (never just standing close together) |
| Horde dissolves | at 4 or fewer members; 5 is the minimum horde |
| How hordes are born | (1) converging on the same sound and milling there together (main way, mostly sub-project 5); (2) following: a wanderer that sees another zombie walking past has a small chance to fall in behind it; a chain reaching 10 is a horde |
| Idle zombies | zombies idle in buildings or sitting do not follow anyone until something gets them moving |
| Wanderer joins a horde | sees a member: vanilla sight range and sight cone (section 4.3) |
| Horde meets horde | merge **only by touching** (spread circles overlap); seeing each other does nothing |
| Merged heading | the bigger horde's heading, nudged by the smaller one in proportion to size |
| Moaning | each horde is a constant weak sound source, louder with size; **always the lowest-priority sound**: any other sound heard makes a zombie ignore and forget moaning; members never react to their own horde's moaning; a horde's own moaning masks quieter moaning where it is, so smaller hordes hear and follow bigger ones while the big one does not notice them |
| Followers | at the same speed a follower horde does not catch up; it trails the leader (a convoy) and merges only when the leader pauses, slows or turns back |
| Movement | mostly moving along roads; brief pauses (a few in-game minutes); big hordes slower from crowding |
| Stragglers | 1 in 75 zombies never join hordes (sandbox option); they linger in or near buildings |
| Break-aways | in tight spots (alleys, gaps between buildings) each member has a small chance to be left behind there as a lingering wanderer; open road and fields shed almost nobody |
| Splits | only by sound (sub-project 5) and break-aways; no splitting at forks ("followers follow the leader") |
| Vanilla rally groups | switched off by the mod (sub-project 4), so groups form only through the mod's rules |

## 3. Architecture

Approach 1 (chosen): **individuals plus horde units**.

- **Wanderer** = one `ZombieRecord` (existing, sub-project 3) plus new fields: heading, pause timer, `straggler`
  flag, `followTarget` (record or horde id it is trailing, or none), `hordeId` (last horde, for rejoining after a
  real spell), `lingerUntil` (break-aways and stragglers stay put for a while).
- **Horde** = new `Horde` record: id, centre x/y, heading, speed, spread radius (grows with size), pause timer, member
  list (the members' `ZombieRecord`s, kept whole: outfit, persistent ID, state flags, ModData incl. True Hits state).
  Members are **not moved individually off screen**; only the horde centre moves.
- **Walker** = what the movement rule moves: a wanderer or a horde. One rule for both (section 4.1).
- **Spatial grid** (per chunk) of walkers for encounter queries; updated as walkers move.
- **Background simulation thread** owns the walkers and grid. The main thread never touches them directly; it
  exchanges work through the core's existing lock: hand-over at the loaded edge (sub-project 3 paths), snapshots for
  the debug map and status, save snapshots.
- New package `truehordes.sim` (pure Java, offline-tested like `truehordes.population`): `Walker`, `Horde`,
  `Movement`, `Encounters`, `Moaning`, `SoundEvent`, `LevelOfDetail`, `SimThread`, `Conservation`. Bridge code
  (`truehordes` package) only for the rally patch, the test sound command and the member layout at hand-over.

## 4. Behaviour

### 4.1 Movement (wanderers and hordes alike)
Each step, a walker scores a fan of candidate headings: straight, ±45°, ±90°, and turning back (rare). For each it
looks a few chunks ahead on the sub-project 2 world map and scores:
- **Momentum:** closer to the current heading scores higher.
- **Terrain preference, best first:** 1. roads (including highways); 2. trails; 3. farmland; 4. open ground and
  forest (equal). Building chunks must be walked around; water is never entered (D16); unknown chunks are treated as
  open ground.
- **Moaning pull** (wanderers and quieter hordes only; section 4.4).
- **Randomness.**
The heading is a **weighted random pick** (not the best score), so roads get used most but are never assigned (D4).
Example (Muldraugh): a horde leaves town, reaches the highway, faces trees; it usually turns left or right onto the
highway (roads score best), but can roll to walk into the woods until its next roll. One knob, **off-road chance**,
sets how often walkers leave roads. Hordes collecting on highways is accepted behaviour; that knob and sub-project 5
sounds are the controls.

**Local avoidance:** a heading whose next chunk is water, or solid buildings with no gap, is dropped. In town the
sub-project 2 tile collision data finds gaps (streets, alleys, gaps between buildings); a chunk passed through such a
gap counts as a **tight spot** (section 4.5).

**Map edges:** within a few chunks of the map's edge (or of map void) headings out of the map are dropped; at a dead
end (a road that leaves the map) the walker turns around. Walkers never leave the map and never get stuck at an
edge. Zombies may still arrive from the edges (sub-project 3 respawn).

**Speed:** the base speed comes from the sandbox Speed setting (sprinters / fast shamblers / shamblers, using the
game's own walking speeds, read from the game's code in the plan), averaged over time. Hordes are slower from
crowding: full speed at 10 members, about half at 10,000 (log-scaled; tunable).

**Pauses:** walkers pause now and then for a few in-game minutes, then move on. Pauses are when followers catch up.

### 4.2 Horde formation and following
- **Following:** a moving wanderer that sees (section 4.3) another moving zombie or horde has a small chance per
  update (`FollowChance`) to fall in behind it: it takes the target's heading and keeps near it. Stragglers and
  idle/sitting zombies never follow. A follow chain (wanderers following each other, transitively) of 10 or more
  moving together becomes a horde.
- **Converging on a sound:** zombies responding to the same `SoundEvent` that arrive and mill within about a chunk of
  each other become a horde when the sound fades, if 10 or more and not stragglers. In sub-project 4 the only sounds
  are moaning and the debug test sound.

### 4.3 Sight (vanilla rule, off screen)
A wanderer sees a target when it is within the **vanilla vision radius** and inside the **vanilla sight cone**,
both read from `IsoZombie` (42.21): radius 20 tiles base, reduced by the game's darkness, rain and fog penalties,
Eagle and Poor sight settings adjusted as the game does, clamped as the game does (10 to 20); the cone uses the
game's angle bands (seeing is likely in front, impossible well behind). Off screen, "sees a horde member" means the
horde's spread circle is within that radius and cone. The plan pins the exact numbers from the game code.

### 4.4 Moaning
- Each horde has a loudness from its size (more members, louder; log-scaled) and a hearing area from that loudness
  with real-life falloff with distance: about a street for 10, a neighbourhood for thousands. The exact physics is
  tuned in sub-project 5; sub-project 4 builds the core (loudness, falloff, who hears).
- **Lowest priority:** a walker hearing any other `SoundEvent` ignores and forgets moaning.
- A horde's members never react to their own horde.
- **Masking:** a horde ignores moaning quieter (where it stands) than its own. So a smaller horde hears a bigger one
  and drifts towards it; the bigger one does not notice the smaller.
- Wanderers drift towards the loudest moaning they hear until they see a member, then join (4.5).

### 4.5 Encounters
Run on the spatial grid, so each walker only checks nearby cells:
- **Wanderer sees horde member** → joins the horde (not stragglers).
- **Horde touches horde** (spread circles overlap) → merge: members combined, heading = size-weighted towards the
  bigger horde's, spread grows.
- **Tight spots:** each member of a horde passing a tight spot has a small chance (`BreakAwayChance`) to be left
  behind as a wanderer lingering there.
- **Dissolve:** a horde of 4 or fewer becomes wanderers at its centre.

### 4.6 Stragglers
1 in `StragglerOneIn` (default 75) zombies is flagged straggler when it enters the population (seeding, growth,
respawn, take-over of a real zombie). The flag is saved. Stragglers wander slowly near buildings, linger, and never
join or follow.

### 4.7 Test sound (debug)
`Hordes_sound(x, y, loudness)` creates a `SoundEvent` at a point. Walkers that hear it (same falloff as moaning, any
non-moaning sound beats moaning) head for it, mill there for a while, and may form a horde (4.2). Used to watch
convergence in testing; sub-project 5 adds the real sound sources on the same event type.

## 5. Levels of detail, threads and cost

- **Background thread** runs the simulation in time slices and sleeps between them.
- **Update interval by distance to the nearest player:** near the loaded edge every few real seconds (small step);
  within a few cells every in-game minute; far away every 10 to 30 in-game minutes (big steps). A horde costs the
  same as one wanderer.
- **Budget:** if the thread falls behind, far walkers wait longer; walkers near players are never skipped.
- **Main thread** does only the sub-project 3 hand-over, taking snapshots, and the rally patch. Target: no
  measurable frame-time cost from the simulation itself.
- **Saving off the main thread:** the game's save takes a quick snapshot under the lock; a background thread writes
  the file. A full save on quit waits for the write to finish. (This also addresses the possible 20 s main-thread
  save stutter seen in sub-project 3 testing.)
- **Conservation:** held + real + deaths (+ sandbox-driven growth/respawn) must always balance; a mismatch is logged
  with where it happened. Checked every in-game hour in debug mode, and by offline tests over long runs.
- `Hordes_stats()` adds: simulation thread time per tick, walkers updated per second, conservation result.

## 6. Hand-over with the loaded area (until sub-project 6)

- When a held horde's area loads, its members are laid out in a column behind the centre along its heading, on
  walkable tiles, then spawned through sub-project 3's existing spawn path.
- Real zombies keep their `hordeId`. If one unloads again while its horde is still held nearby off screen, it rejoins
  that horde; otherwise it becomes a wanderer.
- Wanderers keep working as in sub-project 3 (spawned at their position when their area loads).

## 7. Vanilla rally groups off

The game's rally-group behaviour (`ZombieGroupManager`) is patched to do nothing while True Hordes runs the
population (same condition as the native-spawning switch). The sandbox values are untouched. Logged at start-up.
The hook target becomes a required target (checked like the others).

## 8. Settings (new sandbox options)

| Option | Default | Meaning |
|---|---|---|
| `TrueHordes.StragglerOneIn` | 75 | 1 in N zombies never joins hordes |
| `TrueHordes.HordeFormSize` | 10 | moving group size that becomes a horde |
| `TrueHordes.HordeMinSize` | 5 | a horde below this dissolves |
| `TrueHordes.FollowChance` | set in the plan (small; chains over weeks in town) | chance per update to fall in behind a seen moving zombie |
| `TrueHordes.OffRoadChance` | set in the plan | how often a walker leaves roads |
| `TrueHordes.BreakAwayChance` | set in the plan (small) | per-member chance to be left behind in a tight spot |

Sight and walking speed follow the game's own Sight and Speed settings.

## 9. Save format 3

Adds horde records and the new per-zombie fields (heading, straggler, follow target, horde id, linger). Format 2
saves upgrade on load: everyone becomes a wanderer and the straggler flag is rolled. Format 1 keeps its existing
upgrade path.

## 10. Debug tools

- `Hordes_map()`: hordes drawn as circles sized by spread, labelled with member count, with a heading arrow;
  wanderers stay dots; stragglers in their own colour.
- `Hordes_sound(x, y, loudness)`: test sound (4.7).
- `Hordes_hordes()`: the biggest hordes (size, position, heading, moving or paused).
- `Hordes_stats()`: additions in section 5.

## 11. Testing

Offline (pure `truehordes.sim`, like earlier sub-projects):
- movement: momentum, terrain preference order, never entering water, map edges and dead ends turn back;
- following chains become a horde at 10; standing crowds never do;
- joining by sight (radius and cone), stragglers never join;
- merging by touch only; seeing does not merge; merged heading weighting;
- moaning: masking, lowest priority, own horde ignored; follower convoy trails and merges when the leader pauses;
- break-aways only in tight spots; dissolve at 4;
- conservation over long runs; save format 3 round trip and format 2 upgrade;
- speed: 1M held zombies, update cost per in-game hour within budget.

In-game rows go in `docs/TESTING.md` (sub-project 4): dots spreading over days; `Hordes_sound` forming a horde;
followers trailing a bigger horde; a horde reaching the highway; rally groups off in the log; no first-person frame
loss; save/reload keeps hordes; conservation clean in `Hordes_stats()`.

## 12. Risks

- Off-screen sight uses the horde's spread circle, not individual members: approximate but cheap.
- Following tuned too high makes towns organise in days; too low and nothing forms before sub-project 5. Defaults set
  in the plan, tuned in testing and sub-project 9.
- Highways may collect most hordes over time (accepted; `OffRoadChance` and sub-project 5 sounds are the controls).
