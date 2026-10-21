# True Hordes: design

Project Zomboid Build 42.21 mod. Single player first, then dedicated multiplayer.
Status: **approved by the user (2026-10-10). Sub-projects 1 to 3 built, merged and accepted in game. Sub-project 4
(simulation core) built on branch `sp4-simulation-core` (2026-10-14), in-game acceptance pending; Q1 and Q8 decided.** Progress per sub-project: section 7.
Each sub-project (section 7) gets its own design → plan → build cycle (Superpowers workflow, per the user's
global CLAUDE.md).

Source documents in this folder:
- `TrueHordes - Limitations Report.md`: what vanilla allows, with Java and native evidence and tests T1–T12.
- `TrueHordes - Research 2 - Off-screen Simulation.md`: existing horde mods, map collision data, absorbing native
  zombies, thunder, lights, generators, off-screen damage, tests T13–T14.
- `TrueHordes - Decisions.md`: dated decision log (this document supersedes it as the single reference).
- `docs/superpowers/specs/` and `docs/superpowers/plans/`: each sub-project's spec and implementation plan
  (sub-project 1: `2026-10-10-sp1-foundation-design.md`, `2026-10-10-sp1-foundation.md`).
- `docs/TESTING.md`: in-game acceptance checklists.

---

## 1. Vision

The Walking Dead feel. Every zombie on the map wanders, alone or in hordes, along roads and through forests,
fields and towns. Nowhere is safe. Hordes are not placed or scripted: they **happen**.
- Zombies wander until something changes their course: a sound, a horde they can see, another zombie they bump into.
- Zombies that bump into each other form a group. Individuals that see a horde of about 10 or more go to join it.
  Hordes that meet merge into bigger hordes. Hordes can split.
- A loud enough sound turns a horde, wholly or partly depending on how far away it is. Individuals converging on
  the same sound meet on the way and form new hordes.
- Over in-game weeks, a town can empty into a roaming horde. There are still plenty of stragglers.

### The scenario we are aiming for

> You're in your house in the woods. A thunderstorm is over you; lightning strikes and thunder roars nearby. You lie
> down to sleep, and suddenly hear the moans: one, then five, then a hundred, all wandering past your house. They
> haven't noticed you yet; they're too busy following the sound. A few bang on windows and doors, break some, then
> give up. You didn't make too much noise. The herd passes, and you live to fight another day.

Nowhere is safe, and none of it is scripted: it happens naturally from wandering, stimuli and hordes forming.

### Stated goals: cities and highways over months (user, 2026-10-13)

These are goals the simulation should be able to produce **naturally**, from wandering, pathfinding, joining and
merging, never from a director or a script:
- **A city as a moving threat.** At the start Louisville is a nightmare, zombies everywhere. Over an in-game year a
  horde wanders the city and gathers up nearly every zombie in it. The city becomes like a day-night cycle: the parts
  where the horde is right now are deadly, and the horde keeps moving through the city.
- **A city emptying onto the highway.** That 10,000+ horde may, on its own, find the highway out of Louisville and
  leave the city nearly empty, tear down the military checkpoint, and head down the highway towards Muldraugh and
  West Point.
- **And the reverse.** A horde from Ekron may follow the Muldraugh highway all the way up to Louisville.
- **Smaller towns work the same way.** West Point's 2,000 zombies might merge into a horde and leave town with about
  100 staying behind in alleys and buildings; or the horde might stay in town. Sooner or later another horde passes
  through, because that is just probability.

### Success looks like
- A player in a quiet house sees a horde come down the road with nothing scripted bringing it there.
- A gunshot in town draws in the town's stragglers and part of a passing horde, and a new horde leaves afterwards.
- A town left alone for weeks has changed: emptier in some places, a horde parked in others.
- Performance stays playable at the measured real-zombie budget.

---

## 2. Constraints and workflow

- Build 42.21. B42 layout: `42\` and `common\`, `mod.info`, `sandbox-options.txt`, JSON translations.
- Work in `Desktop\Zomboid Mods Personal\TrueHordes`; copy to `%USERPROFILE%\Zomboid\Workshop\TrueHordes` for testing.
- Never edit under `C:\Program Files (x86)\Steam\...`. Do not copy code from other mods (reading their docs for
  ideas is fine). If something cannot be done, say so.
- **ZombieBuddy (Workshop 3619862853) is a required dependency.** Java code lives in the mod's own jar, built with
  JDK 25 (`%USERPROFILE%\.jdks\jdk-25.0.4.1+1`) against `projectzomboid.jar` and `ZombieBuddy.jar`, following
  ViewpointTrueBallistics' layout (patch classes directly in the `javaPkgName` package, wrapped hooks that never
  throw into the game, a Lua API, unit tests and a patch-target check). On a dedicated server, ZombieBuddy and the jar
  must be installed server-side too.
- Single player first; dedicated server after the single-player mod works.
- Declared incompatible with Wandering Zombies (`WanderingZombies`, `WanderingZombiesWIP`).
- **Git:** the TrueHordes folder is a git repository published to GitHub (`origin`, branch `main`). Each piece of work
  goes on its own branch (one per sub-project, e.g. `sp1-foundation`). When work is done, Claude says so; the user
  reviews and merges in GitHub Desktop. Claude does not merge or push on its own, and does not hand over merge steps.
- **Build and test:** `tools\build.ps1` (compile with JDK 25, run the offline tests, package the jar; fails if any
  test fails); `tools\deploy.ps1` copies `mod\` into the Workshop folder. In-game checks: `docs/TESTING.md`.
- **Execution style:** foundations that offline tests can catch are built inline ("native"); actual gameplay systems
  use subagent-driven development (a fresh implementer and reviewer per task), as the user prefers.

---

## 3. Decisions

| # | Decision | Date |
|---|---|---|
| D1 | **Full replace.** The mod owns every off-screen zombie, individuals as well as hordes. The game only handles real zombies near players. Vanilla off-screen zombies never wander (they stand until a sound wakes them, then walk a straight line), so they can't deliver the vision. | 2026-10-10 |
| D2 | **Everything wanders**, individuals and hordes, on and off roads. | 2026-10-10 |
| D3 | **Hordes form naturally**: bumping into each other; individuals going to join a visible horde of about 10+; hordes merging on contact; splitting. Exact rules are left open until the user adds them before sub-project 4 (Q1). | 2026-10-10 |
| D4 | **No pre-programmed routes.** Each wanderer or horde picks its next heading from its surroundings. Roads are cheaper to walk, so they get used more, but are never assigned. | 2026-10-10 |
| D5 | **A simulation manager, not a storyteller.** It does bookkeeping: scheduling, encounters, joining, merging, splitting, real/off-screen conversion, saving. It does not send hordes at players. | 2026-10-10 |
| D6 | **Real zombies near players: as many as the machine can handle**, measured (test T10) and then capped by a setting. | 2026-10-10 |
| D7 | **Zombies respond to sound off-screen.** Example: a 1,000-strong horde on the highway by Muldraugh is reached by a gunshot. It turns and heads for the source; once near there, the simulation manager has it decide where to wander next. | 2026-10-10 |
| D8 | **Hordes react by loudness, which fades with distance.** Far out only part of a horde breaks off (example given: about half in the outer 700–850 tiles of an 850-tile sound); the closer, the larger the share; inside a threshold, the whole horde goes. The rest stays a horde and keeps wandering. Numbers are tuning values. | 2026-10-10 |
| D9 | **Individuals (not in a horde) always respond to a sound they can hear**, out to the edge of its reach. Converging individuals meet and form hordes. | 2026-10-10 |
| D10 | **The mod owns individuals too.** They wander with the mod's logic until they see a horde and join it, or meet enough others to form one. | 2026-10-10 |
| D11 | **The mod has its own sound model with realistic distance falloff, across the whole map.** Loudness depends on the source and fades with distance like real life, instead of a flat radius where everything inside reacts equally. Examples: a gunshot is about town-wide; a helicopter is map-wide; storms are map-wide. It covers everything that moves zombies. See section 5. | 2026-10-10 |
| D12 | **ZombieBuddy Java is required**, not an optional companion. | 2026-10-10 |
| D13 | Sandbox `FollowSoundDistance` = 1000 (affects only vanilla off-screen behaviour; mostly superseded by D1 and D11). | 2026-10-09 |
| D14 | **Java core, Lua as helper.** The simulation lives in Java (ZombieBuddy): world knowledge, population store, update loop, saving, population takeover. Lua is the helper for game-facing glue: sandbox options, events, steering real zombies, debug UI, console commands, talking to Java through ZombieBuddy-exposed functions. Reason: performance at tens of thousands of zombies and whole-map terrain, and only Java can reach the save's collision data and the native population. The offline test bench is plain Java tests. | 2026-10-10 |
| D15 | **Idea, not final: structure damage scales with crowd size.** A lone zombie is slow to break in; a crowd is fast; a huge horde crushes through. Example numbers (not final): 1 zombie ≈ 5 in-game hours for a window, 24 for a door; 10 together ≈ 1 hour for a window, 5 for a door; 1,000 ≈ 10 minutes, zombies crushing themselves against doors and windows. Applies to real zombies near players (sub-project 6) and to off-screen wear (sub-project 7). | 2026-10-10 |
| D16 | **Water blocks zombies everywhere**, off-screen and near players alike, as for real zombies in vanilla. Rivers and streams are crossed only on bridges and roads, so a wide river can make the far bank safe. Supersedes "narrow water crossable at a cost". River and water behaviour may be revisited late, before the mod is finished. | 2026-10-10 |
| D17 | **Zombies cross the real/simulated boundary by wandering, both ways.** A real zombie near a player can wander out of the loaded area on its own and become simulated, carrying on its way off-screen; a simulated zombie or horde can wander into the loaded area and become real. Crossing is not only caused by the player moving: the population flows in and out around the player continuously, so the edge of the loaded area is never a wall or a spawn line. | 2026-10-13 |
| D18 | **Built for the "Urban focused" zombie distribution** (the sandbox default): hordes form in towns, then leave them or wander around them. "Uniform" is supported as an afterthought only (its mapping is read from the DLL, not tuned or tested). | 2026-10-13 |
| D19 | **Hordes form anywhere there are enough zombies, then leave or roam by their own wandering, never by a director.** A horde forms wherever enough zombies come together (D3; the minimum size, about 10 or maybe 5, is part of Q1). The most populated places, the towns, produce the biggest hordes. Every horde follows the same joining and wandering rules: **only through its own wandering and pathfinding** it either leaves along highways, roads and trails (and may meet and join a bigger horde out there) or keeps roaming the area it is in, mostly through its own pathfinding, meeting other hordes there and merging with them. Nothing sends a horde anywhere (D5). See the stated goals in section 1 and Q8. | 2026-10-13 |
| D20 | **Every zombie hears with energy falloff (replaces D8 and D9).** Real and held zombies alike: a chance to hear that fades with the sound's energy (100% in the inner quarter, 40% at the edge); hordes drown themselves out by size and send a share, the whole horde if half or more hear; one roll per sound; no distance cap. See section 5. | 2026-10-16 |

---

## 4. Architecture

Six layers. **Java is the core (D14)**: every layer below except the parts that must touch Lua-only game hooks.
Lua is the helper: sandbox options, events, steering real zombies, debug UI and console commands.

```
          sounds, thunder, lights, players                 map files + save
                      │                                         │
              ┌───────▼────────┐                       ┌────────▼────────┐
              │ Stimuli and    │                       │ World knowledge │  terrain cost, water width,
              │ sound model    │                       │                 │  walls, roads, zones
              └───────┬────────┘                       └────────┬────────┘
                      │  "what each zombie/horde perceives"     │ "how hard is it to walk here"
              ┌───────▼─────────────────────────────────────────▼───────┐
              │ Simulation manager (population store + update loop)     │
              │ individuals and hordes, wandering, encounters,          │
              │ join / merge / split, saving                            │
              └───────┬─────────────────────────────────────────┬───────┘
                      │ near players                            │ while away
              ┌───────▼────────┐                       ┌────────▼────────┐
              │ Materialisation│  real zombies,        │ Consequences    │  damage ledger, generators,
              │ bridge         │  their wandering      │                 │  corpses, applied on load
              └───────┬────────┘                       └─────────────────┘
                      │
              ┌───────▼────────┐
              │ Population     │  vanilla off-screen population switched off,
              │ takeover       │  initial population seeded from the map
              └────────────────┘
```

### 4.1 World knowledge
- Source: the map-wide collision grid in `chunkdata_X_Y.bin` (map folders) with the save's updated copies in
  `Saves\...\chunkdata\` (solid, wall north, wall west, water, room bits per tile), plus map zones (`Nav` roads and
  streets, Forest, DeepForest, FarmLand, TownZone…), building definitions and the zombie density map.
- Output: a coarse cost grid (for example 8×8-tile cells) for wandering and local avoidance, with tile-level detail
  available near players. Water blocks (D16); bridges are walkable.
- Java reads the files (Lua can't read the save copies). Works on modded maps because they ship the same files.

### 4.2 Population store and simulation manager
- **Levels of detail by distance to the nearest player:**
  - far: stragglers as counts per area that drift between neighbouring areas; hordes as single objects
    (position, heading, size, composition);
  - mid-range: individuals as coordinates with outfit and state;
  - near: real game zombies (section 4.4).
- **Scheduling:** far objects are updated rarely in large steps, near ones often in small steps, so cost stays flat
  as the population grows. Hordes cost the same off-screen whatever their size.
- **Wandering:** each wanderer or horde picks its next heading from its current heading (momentum), terrain cost
  ahead, perceived stimuli, nearby hordes, and randomness. Local avoidance steers around walls, buildings and wide
  water. No long routes are planned in advance.
- **Encounters:** a spatial index finds who is near whom; joining, merging and splitting follow the rules from Q1.
- **Composition:** hordes keep what they're made of (outfits, crawlers, special zombies) so they give back the
  right zombies when they become real. Whether individual identities are kept (`persistentId`) is decided in
  sub-project 4.
- **Saving:** the mod's own save data, with conservation checks (no zombies appear or vanish except by death,
  rules or settings).

### 4.3 Stimuli and sound model
See section 5.

### 4.4 Materialisation bridge
- Off-screen ↔ real conversion near players, within the measured budget (D6). The criterion is distance, and
  possibly visibility (the engine's own "can the player see this" test), decided in sub-project 6.
- Real zombies get the mod's wandering through path orders (`pathToLocation`, integer version), within a per-tick
  budget; real hordes walk as a column behind their leader. Vanilla rally groups are switched off (done in sub-project 4).
- Leaving range turns a real zombie back into the mod's data, keeping its outfit, state and heading.
- **Both directions by wandering (D17):** real zombies wander out across the loaded area's edge and become simulated,
  continuing the same heading and destination off-screen; simulated zombies and hordes wander in and become real at
  the edge, already walking. Neither side treats the edge as a wall or a spawn line, and a horde that straddles the
  edge stays one horde (part real, part simulated) until it is wholly on one side.

### 4.5 Population takeover
- The vanilla off-screen population (`PZPopMan64.dll`) is switched off or emptied, and the mod seeds its own
  population from the map's density, starting conditions and sandbox settings. Respawn behaviour is the mod's.
- Candidate methods (decided by a spike in sub-project 3): zero the native targets and spawn our own; absorb native
  zombies chunk by chunk through `n_loadChunk` (Research 2, section 4); or a mix. Every real zombie the game creates
  is caught and attributed to the mod's population (no duplicates).

### 4.6 Consequences while away
- A ledger of pending effects per chunk or structure, applied when the area loads: broken windows and doors, player
  structures worn down by their real HP, corpses and blood.
- Generators: predicted run-out time from fuel and consumption recorded at unload; a lit base can hold a horde until
  then.

### 4.7 Multiplayer (after single player)
- The simulation manager runs on the server. Real zombies near players are steered by their owning client, ordered by
  the server. ZombieBuddy and the jar are installed on the server. The vanilla zombie cull is reviewed (the user will
  test it at 0).

---

## 5. Sound model (D7, D10, D11; D8/D9 replaced by D20, 2026-10-16)

The mod replaces "everything inside a radius reacts the same" with **loudness that fades with distance**, for every
sound in the game and **every zombie**, real ones near players and held ones off screen (Q4, decided 2026-10-16).

- **Loudness from the game's circle.** Every sound arrives with a radius in tiles (already enlarged by Louder Gunshots
  and scaled by the Hearing setting) and becomes a source loudness `dB = 30 + 20·log10(radius)`. The circle's edge is
  where the sound fades to the 30 dB hearing threshold, so reach in tiles is unchanged and vanilla, modded and Louder
  Gunshots radii all work without a list.
- **Energy fades like real sound:** `level = dB − 20·log10(distance)` (6 dB lost per doubling of distance).
- **Lone zombies:** the chance to hear is 100% inside the inner quarter of the circle (12 dB above the threshold),
  fading with the energy to a **floor of 40% at the edge**, 0 outside. Inner quarter and edge floor are sandbox options.
- **Hordes drown themselves out.** A horde's share = the lone chance × the part of the horde not drowned out by its
  own moaning: half for 1,000 zombies, more for smaller hordes, less for bigger ones (`1 − 0.25·log10(n/10)`, clamped
  to 0.25..1); it rises to the whole horde as the sound gets louder than the moaning (full at 20 dB above the
  threshold). Drown-out strength is tuning.
- **Hive mind:** if half or more of a horde hears a sound, the whole horde goes. Below half, the share that heard it
  breaks off from the horde's edge on the side facing the sound: 10 or more become a new horde heading there, fewer
  become single zombies heading there; the rest carry on.
- **One roll per sound.** Every discrete sound (each gunshot) is its own roll, so a firefight chips at a horde shot by
  shot and the shrinking horde gets easier to pull (it drowns itself out less) until the hive mind sends the rest.
  A sound the game re-sends every tick (alarm, generator) is one ongoing sound. Walkers already heading for a spot do
  not split again for more sounds from that spot; each one refreshes their 6-hour investigate time.
- **Switching:** a walker heading for one sound switches only for one at least 6 dB louder where it stands (tuning).
  Horde moaning stays the lowest priority (sub-project 4).
- **No distance cap.** The only limit is the sound's own energy: wherever it is above the threshold, zombies hear it
  and can follow it all the way. Vanilla's `FollowSoundDistance` only affects the native off-screen zombies the mod
  replaces.
- **Arriving:** unchanged from sub-project 4 (mill about, investigate the spot for 6 hours, 10+ gathered = a horde).

Same 850-tile gunshot (share that goes, hive mind applied):

| Distance | Lone zombie | Horde of 100 | Horde of 1,000 | Horde of 10,000 |
|---|---|---|---|---|
| 50 | 100% | 100% | 100% | 100% (93% hear, rest follow) |
| 100 | 100% | 100% | 100% | 100% (~85% hear) |
| 213 | 100% | 100% | 100% | 100% (~70% hear) |
| 425 | 70% | 100% (58% hear) | 46% | 33% |
| 640 | 52% | 40% | 29% | 17% |
| 800 | 43% | 33% | 22% | 12% |
| 850+ | 0% | 0% | 0% | 0% |

- **Capture (5a):** one Java patch on `WorldSoundManager.addSound` records every world sound (position, radius as
  scaled by the Hearing setting, repeating or not, a best-guess kind for the viewer from the source: gun-holding player
  or NPC = gunfire, vehicle, appliance, other); sounds made by zombies are skipped as vanilla skips them. Sounds go
  through a lock-free queue to the simulation thread. A new sound is rolled at once for every held wanderer and horde
  inside its circle (spatial grid), not at their next scheduled update. Sounds re-sent every tick (alarms, generators)
  fold into one ongoing sound per source and spot that ends a few seconds after the game stops re-sending it; walkers
  entering its circle roll once when they first come into range.
- **Real zombies near players (5a):** patched where a real zombie scores sounds (`WorldSoundManager.getSoundAttract`
  for its current sound, `getBiggestSoundZomb` for the biggest where it stands); vanilla keeps the reaction delay, the
  scatter around the spot, pathing and dropping a thump target. Score = level at the zombie (`dB − 20·log10(distance)`,
  vanilla's room penalties kept as about −1 dB for another room and −2.3 dB inside to outside); the lone-zombie chance
  is rolled once per zombie per sound and remembered (weak map per zombie; repeating sounds are one sound); a heard
  sound scores `10^(level/20)` so vanilla's own "twice as strong" switch rule (`soundAttract * 2 < attract`) is the
  6 dB rule; a sound not heard is skipped. The capture patch tags every sound with its own id (the game pools and
  reuses sound objects). Real zombies use the lone rule; horde identity on screen (drowning, hive mind) comes with
  sub-project 6. Every sound is captured for the off-screen model by one patch on `WorldSoundManager.addSound`.
- **Sound viewer (5a):** a toggle layer on the hordes map: each active or recent sound drawn as its reach, shaded by
  the same energy curve (the shade is the chance of being heard), coloured by kind, fading over about 30 real seconds
  after it ends; thin lines from hordes heading to a sound; a list panel of recent sounds (kind, radius, wanderers and
  hordes that responded, zombies split off, age), click to centre the map. Data from a new `Hordes_sounds()` call.
- **Hearing big hordes (5a):** a held horde just outside the loaded area plays distant moaning from its direction,
  louder with size and closeness on the same energy falloff, silent when too quiet, stopping when it becomes real.
  Uses the game's own zombie sound files; volume and starting size are tuning.
- **Round 8, walkers and herds (2026-10-16, after the first 5a test):** 95% of lone zombies are millers (wander
  within about 150 tiles of home, a soft pull; a sound or being left behind moves home), 5% travellers
  (`TravellerShare`) walk the roads; millers drift towards nearby groups (fall in behind the one nearest a cluster's
  middle, or head for a horde within 40 tiles); towns (64-tile blocks with 12+ building chunks around) hold their
  hordes until each horde's hunger day (`LockdownDays` for 500 zombies, bigger sooner, smaller later, +-20%); held
  zombies and hordes walk into the loaded area and become real; the map's edge turns waves back; helicopter, meta
  events and thunder are labelled. Tuned: bump-follow peak 3%, crowd drift 2%/h: Louisville about 69% in hordes on
  day 7. Spec: `docs/superpowers/specs/2026-10-16-sp5a-round8-walkers-and-herds-design.md`. Goals noted by the user
  for later: a map-wide run to check towns are not empty for too long (emptying is fine if emergent), and a rare
  emergent Louisville mega-horde circling inside the city ("Dead City").
- **Sub-project 5 is split:** 5a sound core (model, capture of every game sound incl. Louder Gunshots and A-Life
  gunfire, real and held zombies, the sound viewer on the hordes map, the player hearing big hordes just outside the
  loaded area); 5b events (random meta events far away and near the player, custom events, A-Life off-screen fights if
  A-Life offers a clean hook, thunder and weather, environment sounds, lights as a lure). Far meta events land in
  towns and on roads (some rural-only); a lone-survivor gunshot event can land anywhere.
- **5b far meta events (user, 2026-10-16; "be quiet or else"):** replace vanilla's gun-event schedule. Month 1: one
  every 1-3 in-game days (gap leaning to 3 as the month ends); months 1-12 the gap stretches to about 31 days; after
  that about monthly (Claude's call); the day is random. A-Life NPC noise and its events do not count. Kinds: vanilla's
  set as data (the user may add sound files later): gunshots (vanilla picks 6 files: assault rifle, 3 pistols, shotgun
  x2; a burst file becomes several shots, each a separate 5a roll), screams and dog barks (attract, quieter: Claude's
  call radius 60 and 80), owls and wolf howls ambience only. Gunshot loudness 1:1 with the user's Louder Gunshots mod:
  a rifle (radius 200) x its `LouderGunshots.Multiplier` when on, vanilla's when off. Same falloff as every sound. The
  player hears direction and distance (500 tiles faint, 50-100 loud) in iso and Viewpoint. Placement: towns and roads,
  rural-only kinds in the countryside, a lone gunshot anywhere. Feel: "oh shit, I might have to hide" as streets fill.
- **5b near events (user, 2026-10-16; "dread from noise": get out, hide in base, or fight):** 50-100 tiles from the
  player, own schedules, can overlap far events. Car alarm: a real parked car's alarm goes off (the player turns it off
  or lets the battery drain); every 2-3 days in month 1, then a 10% chance every 20 days, none after month 12; kept
  sounding while unloaded, battery drained by the elapsed time on reload (Claude's call). Near gunshot (a soon-dead
  survivor): its own event on the far gunshots' schedule (1-3 days in month 1, then a 10% chance per 31 days).
- **5b custom events:** undecided; the event system is built as data (kind, sound files, radius, burst, placement,
  schedule) so custom events slot in later.
- **5b A-Life (Project A-Life NPCs, workshop 3803984183):** its on-screen gunfire and its meta, raid and heli events
  already call `addSound` (captured by 5a). Off-screen squad fights (`ProjectALife.OfflineCombat.apply(outcome, hours)`)
  are silent: a compatibility layer (its own Lua file; runs only if A-Life and that function exist; wrapped in pcall;
  logs once and does nothing if A-Life changes) wraps it and fires a burst of gunshots at the fight's spot, sized by
  the fight (fighters and casualties), each shot its own 5a roll. Their code is not copied.
- **5b weather (user, 2026-10-16):** a storm dampens hearing inside it, by rain or storm strength (a gunfight in a heavy
  thunderstorm draws far fewer zombies than on a clear day). Thunder is heard by the player and by zombies; inside the
  storm zombies do not react to it, outside it they do (lone zombies: the 5a lone roll, so some not all; a horde rolls
  once to turn towards it, Claude's call). Storms away from the player also thunder (rate by storm strength, Claude's
  call), so weather moves zombies. Fog does nothing. Where storms are comes from True Weathers - Gameplay
  (`ViewpointTrueWeathersGameplay`, regional storms) through its own compatibility layer: reflection on
  `vtwg.Api.precipAt(x, y)` / `regional()`, fail-safe like A-Life's. Without it the whole map is inside vanilla's
  weather. Real zombies: True Weathers already muffles their hearing (rain, and ~3.5 s after nearby thunder), so ours
  applies only when it is off (no double muffling).
- **5b environment and lights (user, 2026-10-16):** vanilla thumping/breaking, generators, alarms and fires already make
  world sounds (5a captures them). Lights lure only at night and only when lit after the shutoff (generator power): one
  roll per light per lone zombie or per horde (go or wander past): flashlight beam on a zombie 10%, building and
  player-placed lights 25%, generator streetlights 1% (stays until morning). Spec:
  `docs/superpowers/specs/2026-10-16-sp5b-events-design.md`.

---

## 6. Facts the design relies on

From the limitations report and Research 2 (42.21):
- Vanilla off-screen simulation is native (`PZPopMan64.dll`), server or single-player host only. Its zombies only move
  toward a sound or target, in straight lines, stopping at obstacles; path search is Last Stand only.
- Real zombies exist within about 76 tiles of a player at 1080p (19×19 chunks), less on smaller screens; on a server
  per player.
- Lua has no stable zombie ID; Java can read `IsoZombie.persistentId`. Per-zombie ModData is lost when a zombie goes
  virtual.
- `pathToLocation` (integers) always takes effect; `pathToLocationF` is ignored during the repath delay. A path whose
  target is unloaded keeps walking.
- `OnWorldSound` fires for every sound; the server sees all player sounds. Sounds under radius 50 never reached the
  native simulation (irrelevant once the mod owns sound).
- `Nav` zones (including streets) cover the whole map; the collision grid covers the whole map at tile level and
  includes player changes in the save's copy.
- Thunder events carry map coordinates; zombies ignore light natively; generators deduct off-screen fuel on reload.
- In MP, the nearest relevant client runs a real zombie's AI; the server culls outdoor, target-less zombies beyond
  `ZombiesCountBeforeDelete` (default 300) and deleted zombies are gone for good.
- Existing mods (Hordes B41/B42, Living Hordes) use paper hordes and are single-player only.

Learned while building sub-project 1 (verified against the decompiled 42.21 game and the final review):
- **Hooks that work in single player and on a dedicated server** (all names unique, checked against the installed
  jar by the offline tests and at start-up):
  - every frame / server frame: `ZombiePopulationManager.updateMain` (exit);
  - full saves (quit, exit to menu, sleep, Lua `saveGame()`, the server's "save world every N minutes"):
    `MapCollisionData.save` (exit), the same moment the vanilla population saves;
  - **B42 also hot-saves the world between full saves**: single player after each batch of chunk saves
    (`ChunkSaveWorker` gathers game time on the main thread via `GameTime.saveToBufferMap`); dedicated server every
    600 s (`ServerChunkLoader.saveLater`). The mod hooks both and writes at most every 20 real seconds;
  - world unloading: `ZombiePopulationManager.stop` (enter). The save always runs before the stop.
- **Threads:** everything runs on the main thread except a dedicated server's shutdown hook (Ctrl+C, panel stop),
  which saves and stops on its own thread. The core's entry points are synchronized for that reason.
- **Save folder:** `ZomboidFileSystem.getFileNameInCurrentSave` is correct at both start and save, including on a
  server and on the game's crash-recovery save; the mod's file is `<save>\truehordes\state.bin` (+ `.bak`).
- **Sub-project 3 must reconcile vanilla chunk saves** (zombies saved inside chunks continuously) with the mod's
  own file on load; hot saves narrow the gap but do not remove it.
- **Lua bridge:** ZombieBuddy exposes a Java class as a Lua table (`TrueHordesJ`, `@Exposer.LuaClass`); Lua calls its
  static methods with a dot. Kahlua tables take `Double` keys and `Boolean` values.
- **Lua can be syntax-checked offline** with the game's own compiler (`org.luaj.kahluafork.compiler.LexState` in
  `projectzomboid.jar`); the build does this for every mod Lua file.

Learned while building sub-project 4:
- Zombie sight (IsoZombie, 42.21): radius 20 tiles minus darkness (5), rain (2.5) and fog (7) penalties, Eagle x1.75,
  Poor x0.35, clamped 10 to 20; spotting weight by facing angle (cos) bands, 0 behind (cos < -0.4).
- Vanilla rally groups are only created in `ZombieGroupManager.update(IsoZombie)`; skipping it switches them off.
- The game's zombie speed is animation-driven; off screen the mod uses estimates (shambler 0.45 tiles/s, walking half
  the time), checked in testing.
- 1.05M held zombies cost about 15 s of one background core per in-game hour (10% at 1-hour days); 99% of 2 ms slices
  stay under 2.5 ms.

Learned while building sub-project 3 (formulas in the sub-project 3 spec, section 5):
- The native population only creates zombies from the values `ZombiePopulationManager.onConfigReloaded` sends it;
  sending zeros (population, start, peak, respawn multiplier and hours) switches native spawning off without touching
  the sandbox.
- Zombies leave the loaded area through `removeChunkFromWorld` (area unloads) and `virtualizeZombie` (one zombie walks
  onto an unloaded square); they come back through `addChunkToWorld` and the private `addZombieStanding/Moving`.
  ZombieBuddy's `@Patch.OnEnter(skipOn = true)` skips a game method when the advice returns true.
- Building zombies are created only when a room is first seen (`VirtualZombieManager.roomSpotted`); Wake Them Up just
  spots every room of loaded cells. Seeding rooms and skipping that spawn makes such mods unnecessary.
- B42's zombie Voronoi noise is applied to each chunk's intensity at map load; `LotHeader.getZombieIntensityForChunk`
  already returns the masked value.
- Vanilla respawn creates moving groups of at least 10 zombies, not single ones.

Learned while building sub-project 2:
- Collision chunk type 5 ("no map here") exists in the vanilla files' edge strips; the game's Java reader does not know
  it. Every one of the 4,065 vanilla files parses exactly with it read as a uniform chunk.
- The whole vanilla map (4,065 files, 5,104 road zones, plus `streets.xml` and `worldmap.xml` roads) builds into the
  coarse grid in about 2 s using about 29 MB, so no cache file is needed.
- `Nav` zones miss many rural roads; `streets.xml` (centre lines with widths) and `worldmap.xml` (300-tile cells,
  `highway` = primary/secondary/tertiary/trail polygons) fill them. Long road lines must be rasterised segment by
  segment: testing their whole bounding box made the build take 20 s.
- Live changes: after `MapCollisionData.addChunkToWorld` the chunk's 64 tile bits sit in its private `squares` array;
  for `squareChanged` the game's private `isSolid` / `isBlockedN` / `isBlockedW` / `isWater` / `isRoom` give the bits.
- The game's `Zone` geometry shares a static `Clipper`, so zones are copied on the main thread and the mod does its own
  geometry off it.

---

## 7. Sub-projects

| # | Sub-project | Delivers | Depends on | Key risks / spikes |
|---|---|---|---|---|
| 0 | **Tests (user)** | Results of T1, T4, T6–T11, T13, T14 (T2, T3, T5 optional) | — | — |
| 1 | **Foundation and tools** | Mod skeleton (Lua + Java jar on ZombieBuddy), sandbox options scaffold, save data, logging, console commands, debug map/overlay of the mod's population, and an offline test bench that runs simulation logic outside the game | 0 | Build pipeline with JDK 25 |
| 2 | **World knowledge** | Collision grid reader (map + save copies), zones and roads, coarse cost grid and queries, live updates from player changes | 1 | Memory and load time for the whole map; keeping up with player changes |
| 3 | **Population takeover** | Vanilla off-screen population switched off, mod population seeded, every real zombie caught and attributed | 1, (2) | **Spike first:** which takeover method is clean and safe (native internals); reconciling vanilla chunk saves with the mod's file (section 6) |
| 4 | **Simulation core** | Off-screen wandering (terrain preference roads > trails > farmland > open/forest, momentum, map edges turn back), levels of detail on a background thread, horde records, following, joining by sight, merging by touch, horde moaning (first piece of the sound model) and a debug test sound, break-aways, stragglers, vanilla rally groups off, save off the main thread, conservation checks. Spec: `docs/superpowers/specs/2026-10-14-sp4-simulation-core-design.md` | 2, 3 | Tuning following and off-road chances |
| 5 | **Stimuli and sound model** | Loudness falloff model, source capture (world sounds, helicopter, thunder), horde share rule, individual response; lights and generators as stimuli; Louder Gunshots (up to ~850 tiles) with falloff; the player hears a big horde just outside the loaded area; builds on sub-project 4's moaning core and sound events | 4 | Q2–Q4; MP thunder needs Java |
| 6 | **Materialisation bridge** | Off-screen ↔ real conversion within budget, both ways by wandering across the loaded edge (D17), real wandering, joining (same sight rule) and columns, state kept both ways, crowd-scaled break-in speed for real zombies (D15) | 4 | Real-zombie budget (T10); pathfinder load; edge cases at the loaded boundary |
| 7 | **Multiplayer** (moved up, user 2026-10-16) | Server-side manager, owner-client steering, server ZombieBuddy install, cull settings, dedicated-server tests; far-sight figures sent to clients; test commands as admin commands; S6 in-game acceptance on the server | 1–6 | Ownership changes; bandwidth |
| 8 | **Consequences while away** | Damage ledger applied on load (crowd-scaled wear, D15), generator run-out, corpses | 4, 6 | Knowing player structures off-screen |
| 9 | **Tuning and compatibility** | Sandbox options, performance scaling, Wandering Zombies incompatibility, Starving Zombies and Louder Gunshots behaviour, presets | all | — |

Each sub-project's design starts by re-reading this document and the relevant research sections.

### Progress

| # | Status | Notes |
|---|---|---|
| 0 | **Pending (user)** | In-game tests from section 9 |
| 1 | **Built and merged to `main` (2026-10-10); in-game acceptance passed in single player (rows 1, 3–7b; row 2 blocked by a `TH_` name clash with the user's True Hits mod, so the commands were renamed to `Hordes_`; rows 8–10 skipped by the user)** | 42 offline tests. Java core (lifecycle, game-time clock, scheduler with update buckets, entity store, crash-safe versioned save file matched to full and hot saves), ZombieBuddy hooks, Lua console commands (`Hordes_status`, `Hordes_stats`, `Hordes_debug`, `Hordes_save`, `Hordes_seedTest`, `Hordes_clearTest`, `Hordes_map`), debug map, build and deploy scripts. Acceptance: `docs/TESTING.md` rows 1–9 (and 7b). Deferred minor issues from its final review are listed below |
| 2 | **Built and merged (2026-10-12); accepted in game (terrain map; live-change rows deferred to the full end test)** | Coarse 8×8-tile grid of the whole map (about 30 MB) built on a background thread from the collision files (save copy first), zones, and the roads and trails in each map folder's `streets.xml` and `worldmap.xml` (new `TRAIL` terrain); tile detail on request; live updates from `MapCollisionData.addChunkToWorld` / `squareChanged`; `Hordes_world()`; terrain and wall layer on the debug map. Vanilla map build: 2.0 s, 28.8 MB with 1,098 streets, 2,867 world-map roads and 502 trails (RealMapTest). Spec and plan: `docs/superpowers/specs|plans/2026-10-10-sp2-world-knowledge*`. Acceptance: `docs/TESTING.md` sub-project 2 rows 1–7 |
| 3 | **Built and merged; accepted in game (2026-10-13)** | Native off-screen spawning switched off; zombies leaving the loaded area (unloading or walking off) held by the mod with outfit, state flags, ID, target and ModData; held zombies spawned back through the game's own spawn code when their area loads; duplicates resolved by persistent ID; a new game seeded across the whole map on a background thread with vanilla's formulas (Voronoi-masked chunk intensity, Population, start/peak curve, rooms by option `BuildingZombiesSeeded`); hourly growth for unvisited cells and respawn groups moving from unseen chunks to the map's edges over `EdgeArrivalMonths`; existing saves taken over gradually; save format 2. Spec and plan: `docs/superpowers/specs|plans/2026-10-13-sp3-population-takeover*`. Acceptance: `docs/TESTING.md` sub-project 3 rows |
| 4 | **Built on branch `sp4-simulation-core` (2026-10-14); in-game acceptance pending** | Held zombies wander on a background thread (2 ms slices; edge every in-game minute, near 5, far 30); hordes form from follow chains and sounds, merge by touch, follow louder hordes' moaning, shed members in tight spots, dissolve below 5; stragglers 1 in 75; rally groups off; hordes laid out as columns where they meet the loaded area; save format 3 written off the main thread for hot saves; growth now adds only the start-to-peak curve's rise. Spec and plan: `docs/superpowers/*/2026-10-14-sp4-simulation-core*`. Acceptance: `docs/TESTING.md` sub-project 4 rows. Tuned after the first in-game test (2026-10-14): followers walk right behind their leader; leaving a road needs a 15% roll, taking a trail 30%; joining a horde in sight and noticing moaning take a chance per hour (user decision A: 5% and 1%); follow 1% per hour; tuned on the real map with `LouisvilleScenario` (two city-sized hordes after 2 to 3 months). Save encode is one sized buffer. Second in-game test (2026-10-15): fast-forward put the simulation hours behind (fixed: each update simulates at most an hour, the thread catches up with 1 ms pauses, `Hordes_stats` shows the lag); zombies keep heading to a sound's spot for 6 h after it ends. User design: idle building zombies wait per chunk as dormant **building hordes** until a sound or a louder horde's moaning sets them loose (10+ come out as a horde); 1 in 10 are **building stragglers** that stay inside (option `BuildingStragglerOneIn`); save format 4. Third in-game test (2026-10-15): wandering redone as **travel** (straight down roads and trails, follow bends, side roads at real junctions, dead ends turn back, occasional straight cross-country legs to the next road, turn only when blocked), no random pauses, touching a horde joins at once, bumping into a walker 50% falls in behind (option `BumpFollowChance`). Fourth in-game test (2026-10-15, user design "waves don't turn around"): a road two or more chunks wide (highway, main road) is one road: its lanes are never side roads, walkers on it never wander off across country and take a road leaving it only 2% of the time; town junctions stay 30%; bends are followed but never back the way the walker came; where a road ends the walker carries straight on across country; decisions are made once per chunk entered, not re-rolled every update; a diagonal that clips a blocked corner steps round it. Bump-follow ramps up with the days: none on day 1 (pure chaos), 1-2% on day 2, 25% by day 7, 50% by day 14, the full `BumpFollowChance` (now default 100%) on the population peak day. User option "both": sight-follow, joining a horde in sight and noticing moaning ride the same ramp, and a bump is one roll when two zombies first touch (not one per update). Offline Louisville (30,000): days 1-2 no hordes, day 3 33% in hordes, day 7 89%, day 30 98% (biggest 12,117). Known: held zombies and hordes still turn back 2 chunks from the loaded area (walking in is sub-project 6). |
| 5a | **Built and merged to `main` (2026-10-16); in-game test of the round-9 fixes pending** | Sound core: every zombie hears by energy falloff (D20); see the round-8/9 notes below |
| 5b | **Built on branch `sp5b-events` (2026-10-16), no plan doc (user choice); in-game acceptance pending** | Spec `docs/superpowers/specs/2026-10-16-sp5b-events-design.md`; acceptance `docs/TESTING.md` sub-project 5b rows. Far meta events replace vanilla's gunshot schedule and random dogs/screams (`doGunEvent` and `addRandomAmbient` skipped; owls and wolves replayed as vanilla did); kinds as data (`events/EventKind`), schedule (`events/Schedule`), placement (`events/Placement`), Louder Gunshots 1:1 (`events/Gunfire`); each shot a real `addSound` (5a capture labels it) plus positional FMOD audio at the spot. Near gunshot and a real car alarm (held at "now" so vanilla never times it out, battery drained over `CarAlarmBatteryHours`; the game itself keeps a sounding car updating while unloaded, VirtualVehicleManager). Rain muffles (up to 12 dB; real zombies only when True Weathers' own hearing is off), thunder ignored inside storms and one roll per horde, far storms thunder (`STORM_THUNDER`); lights lure at night after the shutoff (`LightLure`, `Simulation.Lure`). Compatibility layers in Lua: `Hordes_Compat_TrueWeathers.lua` (samples `VTWG.precipAt` into a 300-tile grid), `Hordes_Compat_ALife.lua` (wraps `ProjectALife.OfflineCombat.apply`). Event state saved in `events.properties` beside the mod save. Found while building: the six vanilla meta gun names are only three FMOD events (`Meta/AssaultRifle`, `Meta/Pistol` x3, `Meta/Shotgun`), each a random clip, so shots per burst are ranges (rifle 3-8, pistol 1-4, shotgun 1-2), not measured per file |
| 6 | **Built on branch `sp6-bridge` (2026-10-16); in-game acceptance deferred to sub-project 7 (user)** | Spec `docs/superpowers/specs/2026-10-16-sp6-bridge-design.md`. Real zombies are piloted (`Pilots`, `real/Pilot`): when vanilla is not busy with them they get `pathToLocation` waypoints from their own `Movement` (travel, milling anywhere, lures from sounds and lights, column slots behind a real horde's leader); real hordes form, join, merge and break up by the sim's rules (`real/Crowd`); the horde tag and lure live in the zombie's ModData. Real zone (hide unseen far zombies) built then removed after the user's test (invisible zombies pushing the player; not worth it): the whole loaded area is real. Far sight first drew through a screen-sized UI element, which made zombies and cars invisible with Viewpoint; now drawn with the sprite renderer in OnPreUIDraw. Performance pass: Core getters lock-free (volatile), sim slices 1 ms / 5 ms sleep, layout of hordes at the loaded area one pass instead of per chunk, pilot orders 8 per tick and every 8-15 s (24-tile waypoints), crowd pass nearest-only. Crowd break-in (`BreakInHook`, `real/BreakIn`): thumps on doors, windows, barricades and player-built objects patched (strength set before, top-up after). Millers wander anywhere; hordes cut across at town junctions (OffRoadChance); highway junctions leading into a town turn 10% (Muldraugh probe: the 2% rule plus the dual carriageway meant about 1 in 40 entered town; now about 1 in 4 from the east carriageway). Map-edge respawns batch into hordes (`min(2000, 50 + 5 x days)`, option `EdgeHordes`; the pool is not saved). Far sight (`sim/FarSight`, `Hordes_FarSight.lua`, single player). Test commands `Hordes_roadHorde`, `Hordes_ringHordes` (single player). Held zombies hear real hordes' moaning (published to the sim as stand-in hordes); the edge pool is saved in events.properties. |
| 7 | **Built (branch `sp7-multiplayer`, merged into local `main` d4f5726, not pushed); user's dedicated-server test 2026-10-20: works, hordes formed** | Server = brain (forming, tags, lures, walk-off takes, events, far sight, moan); each client steers the real zombies it owns by the server's tags (`Pilots.clientTick`). Net in Lua (`Hordes_Net_Server.lua` / `Hordes_Net_Client.lua`). Admin-only test commands. Setup: `docs/MP_SETUP.md`. Workshop: `mod/preview.png`, `mod/workshop.txt` (WIP / Early Access, unlisted) |
| 7+ | **Chase memory and fixes (branch `chase-memory`, 2026-10-20/21; untested in game)** | User decisions: a zombie that loses a player walks to the last-seen spot, then on along the player's heading for `ChaseHours` (default 1); only the ones that saw the player chase, unless more than half of a real horde did (then all). `Hordes_stats` shows walked in / walked off / chases. Far sight in Viewpoint's 3D view via `compat/ViewpointBridge` (reflection, fails closed to 2D); 2D draws off-screen figures as screen-edge markers; reach counted from the loaded edge. Moan voices 10 tiles out (40 was beyond the voices' range) |
| 8 | **Built (branch `chase-memory`, a5f09bd, 2026-10-21; untested in game)** | `sim/Aftermath`: a horde standing at its target off screen is a siege (per chunk: biggest crowd, hours); `AftermathBridge` on chunk load breaks or wears doors, windows, barricades and player-built objects within 3-10 tiles by the D15 times, with blood and glass. Off-screen gun events and A-Life fights kill 1 zombie per 3 shots within 25 tiles (`Simulation.shootDead`), left as corpses (own outfit, max 40 per chunk) on load. A running generator unloading: its building lights stop luring at the predicted fuel run-out. Windows now take 0.72 x the door times (user). Saved in events.properties. Defaults Claude's (hordes only, kill rate, reach, corpse cap). Extras (2c654ba, fd47743, user 2026-10-21): kill rate is option `OffscreenShotsPerKill` (0 = none). Hordes of 20+ walking off screen leave a trail (`Aftermath.trample`, footprint every few tiles, per chunk); on load crops there are destroyed through the farming system (`Hordes_Trample.lua`, server/SP), low fences flattened from 50 (sure at 150), tall wire from 150 (sure at 500); option `HordeTrample`. `DoorSwing`: an open door may swing shut behind a zombie walking in from outside (`DoorSwingChance` 10%, x3 in full wind), slam heard by zombies; the shut-in zombie claws at it for 1 in-game hour (pilots leave it alone). Doors (and barricades on them, player-built doors) need `DoorBreakMinCrowd` (default 3) zombies to take any damage, fewer only claw, also for sieges. `RainWash`: rain fades outdoor floor blood (~3/4 per day of full rain) near players, and map wide per 300-tile weather cell (True Weathers regional rain when present) for areas away; option `RainWashesBlood`. Car alarms already drained batteries (S5b). |
| 6+ | **Far view to Viewpoint's reach (4e8a85c, 2026-10-21; untested in game)** | User: see as far as Viewpoint draws (its far world, up to 64 blocks x 64 tiles = 4,096 tiles, 1 tile = 1 m). `ViewpointBridge.farReach()` reads `viewpoint.far.FarWorld.reach()` by reflection (0 when off/missing). `FarSight.farMarks`: beyond FarSightTiles from the loaded edge out to that reach, each horde one mark (nearest 80, with its radius) and lone zombies (nearest 300), refreshed every 3 s, single player with Viewpoint's 3D view only. LOD by on-screen height (user: real inside the loaded area, lower LOD outside, down to black dots far away): 14 px+ a figure with a head, 4-14 px a bar, under 4 px a dot; far hordes are 6-40 dots spread over their circle moving together. Same LOD in 2D by zoom. Crossing the loaded edge both ways (S6) is built; user unsure it happens: check `Hordes_stats` walked in / walked off while standing still |
| 6++ | **Test round 1 fixes (5251688..2f68dea, 2026-10-21; untested)** | Far figures occluded (Occlusion.java: walls, closed doors, cars, 3+ trees in the loaded area; sim/Sightline: building chunks beyond). Edge ping-pong fixed: a horde re-formed by members leaving (Horde.cameBack) is laid out again only once its centre is real, not when its circle touches the edge; own-horde rejoin reach 200 tiles. Lone zombies never had this: they come back only by walking into a loaded chunk themselves. Real hordes moan (sim.realHordes); far moan floor 35%. A-Life ambient sounds become world sounds (events.ALifeSounds, wraps AudioPolicy.playLocal, SP). Game lists (PZArrayList) are never iterated (iterator throws); Lua calls drop trailing nils |
| 6+++ | **Test round 2 fixes (b412e54..., 2026-10-21; untested)** | Moan: the game's zombie voice events are silent on a free emitter (no zombie sound parameters), so the moan plays the game's own groan files (media/scripts/TrueHordes_sounds.txt, TrueHordesMoan1-26; male 19-26 unused, "burping"), from inside the horde's circle, not faded by the game (distanceMin 2000), volume from the horde's dB margin. **Sandbox HordeMoan (+ HordeMoanVolume) is temporary: remove the toggle once the moan sounds right** (user, 2026-10-21). Hordes_moanTest(n) auditions clips. FarSight now defaults off (figures drift, look janky; experimental until reworked). Loaded hordes: a loaded zombie touching any member joins (not only near the middle); a member vanilla holds (sitting, lying, targeting, dormant, clawing) never leads; a follower whose slot is blocked aims near it; a horde at a sound roams round it (8-30 tiles, sweeping one way) for an hour, picking up wanderers, then walks on (lone zombies still investigate 6 h). Hordes_stats() lists why horde members stand. Moan redone after the test (user: still too loud, wanted the chase gurgles): a looping chorus of the zombie voice events (Male/FemaleZombieVoiceA-C) with ZombieState LockTarget and PlayerDistance set by hand, 3-10 voices by size (3 + 1 per 50 over 50) at the same total loudness (each at total/sqrt(n)), at most 12 tiles out on the line to the horde; volume halved (0.08-0.5); the groan-file script removed |
| 9 | Not started | |

Fixes after the round-8 in-game test (2026-10-16, branch `sp5a-sound-core`): no hordes formed in game (drifters each
followed a different leader; now they join the biggest group in reach, chains count within 40 tiles, drift 3%/h; replay
of the real save: 64% of town zombies in hordes on day 7); hordes walk at the game's zombie speed (no idling, no size
slowdown); hordes sweep up lone zombies along the path they walked; the player hears held hordes (+18 dB); zombies
arriving on a floorless tile step to the nearest floor and retry spawning; a horde stays the same horde while real
(members re-form it when they unload, one-hour gathering window). Gap: the horde tag on real zombies is lost on
save/reload (store it on the zombie in sub-project 6).

Deferred to sub-project 6 (user, 2026-10-16):
- millers favour roads; let them wander anywhere (yards, fields, roads);
- hordes now and then cut through a yard, house or field while roaming, like the off-road chance, until they reach
  another road or trail;
- check why the Muldraugh highway horde never turns into town (2% turn-off per junction; rolls unchecked);
- seeing held zombies at Viewpoint's max draw distance (and hearing them), beyond the loaded area.
- map-edge respawns arrive as big hordes from other counties, sized by the world's age (user, 2026-10-16);
- real zombies in the loaded area must walk out again when sounds draw them (1,000 stood still in the user's day-12
  house after car alarms; real zombies never leave the loaded area yet).

Deferred minor issues from sub-project 1's final review (fix when the code they touch is next changed):
- the scheduler's pending time is not reset between worlds (matters once systems run on an interval);
- a save file with duplicate entity IDs makes start-up retry every frame instead of falling back;
- the number of entities saved is not compared with the number loaded back;
- no test for upgrading older save formats yet (only format version 1 exists);
- if the game's `MapCollisionData.save` throws, the mod's full save is skipped (`@Patch.OnExit` default);
- the debug map draws outside its panel and stops dragging when the mouse leaves it;
- `Hordes_seedTest` has no upper limit;
- `Hordes_status` shows the previous world's load result after exiting a world.

---

## 8. Open questions

| # | Question | Needed by |
|---|---|---|
| Q1 | **Decided 2026-10-14 (see the sub-project 4 spec, section 2).** A horde forms from 10+ zombies moving together (converging on a sound, or follow chains: a wanderer that sees another zombie walking past has a small chance to fall in behind it); never from zombies just standing close. It dissolves at 4 or fewer (5 is the minimum). Wanderers join by vanilla sight (radius and cone). Hordes merge only by touching; seeing does nothing. Horde moaning (louder with size) is always the lowest-priority sound, masks quieter moaning, is never heard by its own members; smaller hordes hear and follow bigger ones, trail them as a convoy and merge when the leader pauses or slows. Splits only by sound (sub-project 5) and break-aways; no splitting at forks. Vanilla rally groups are switched off. | Decided |
| Q2 | Stimuli other than sound, and their weights: thunder or lightning, base lights after the power goes off, players seen, other hordes | Sub-project 5 |
| Q3 | Sound numbers: loudness per source, falloff rate, hearing threshold, horde share curve, "switch to a louder sound" rule | Sub-project 5 (tuning) |
| Q4 | **Decided 2026-10-16:** real zombies near players use the mod's sound model too (D20). | Decided |
| Q5 | Population start: how many zombies, where, and whether any arrive from the map edges over time | Sub-project 3 |
| Q6 | Is "real only if the player could see it" wanted near players, or distance only? | Sub-project 6 |
| Q7 | Crowd-scaled structure damage (D15): final numbers, the curve between 1, 10 and 1,000 zombies, and how it works for real zombies (vanilla thumping changed in Java) versus off-screen wear | Sub-projects 6, 7 |
| Q8 | **Towns can empty out, but it should be hard and it must always be emergent (needs more iteration by the user).** There is **no fixed rule** such as "x zombies can never leave town": whether a town empties, partly empties or keeps its horde is decided by horde wandering and pathfinding, so it is effectively random. Example: West Point's 2,000 zombies merge into a horde and leave, while about 100 stay behind in alleys and buildings; or the horde stays in town. Another horde will eventually pass through any town, by probability. Mechanisms that make emptying hard without forbidding it (all to tune; rates and triggers are the user's): (1) **stragglers / leftovers**: a small share of zombies (about 1 in 100 to 1 in 1,000) refuse to join hordes and stay behind, sitting or lingering in or outside buildings (also noted under Q1); (2) **break-aways**: zombies occasionally drop out of a moving horde, not many but enough that a town keeps some; possible triggers to explore: an in-game meta event, a zombie getting stuck on a door or window, a horde squeezing through an alley and leaving a few behind in it. **Decided 2026-10-14:** stragglers 1 in 75 (sandbox option); break-aways happen in tight spots (alleys, gaps between buildings), almost never on open road; stuck-on-door break-aways come with real zombies (sub-project 6), meta-event ones with sound (sub-project 5). | Decided |

---

## 9. Tests to run before execution

From `TrueHordes - Limitations Report.md` and `TrueHordes - Research 2 - Off-screen Simulation.md`:

| Test | Informs |
|---|---|
| T1 virtual → real conversion and arrival pattern | Sub-projects 3, 6 |
| T4 `createHordeFromTo` | Sub-project 3 |
| T6 ModData lost when virtual | Sub-projects 4, 6 |
| T7 `persistentId` survives (ZombieBuddy experimental mode) | Sub-projects 3, 4 |
| T8 one long path order and hand-off | Sub-project 6 |
| T9 what interrupts an order | Sub-project 6 |
| T10 scale: FPS and steering cost at 200 / 500 / 1,000 real zombies, Viewpoint on and off | D6, sub-project 6 |
| T11 vanilla rally groups off | Sub-project 6 |
| T13 can Lua read the collision files | Sub-project 2 |
| T14 thunder events | Sub-project 5 |
| T2, T3, T5 | Optional now (vanilla off-screen behaviour, superseded by D1) |
| T12 multiplayer ownership | Sub-project 8 |
