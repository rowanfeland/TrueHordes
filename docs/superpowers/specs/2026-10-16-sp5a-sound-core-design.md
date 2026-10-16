# Sub-project 5a: sound core (design)

Status: approved section by section with the user, 2026-10-16. No separate plan document (user, to save usage): the
build order is section 8. Decision record: `design.md` D20 and section 5.

## 1. Goal

Every sound in the game moves zombies by **energy falloff**, for every zombie: real ones near players and held ones off
screen. Hordes drown themselves out with their own moaning and split by share; the hive mind sends the whole horde when
half or more hear. A viewer on the hordes map shows every sound. The player hears big hordes just outside the loaded
area. 5b (events: meta events, A-Life off-screen fights, thunder and weather, environment sounds, lights) builds on this.

## 2. The model (all tuning values are constants or sandbox options as marked)

- **Source loudness** from the game's radius (already scaled by Louder Gunshots and by the Hearing setting as vanilla
  scales it): `dB = 30 + 20·log10(radius)`. Threshold 30 dB, so reach in tiles = radius.
- **Level** at distance d: `L = dB − 20·log10(max(1, d))`. Margin `m = L − 30`.
- **Lone chance** `p(m)`: 0 if m < 0; else `E + (1 − E)·min(1, m / F)`. E = edge floor, sandbox `SoundEdgeChance`
  default 0.40. F = full-hearing margin = `20·log10(1 / q)` where q = sandbox `SoundCertainShare` (fraction of the
  radius heard by everyone), default 0.25 → F = 12.04 dB.
- **Horde share** for n members: `p(m) × h`, `h = b + (1 − b)·clamp(m / 20, 0, 1)`, `b = clamp(1 − D·log10(n / 10),
  0.25, 1)` with D = drown-out per tenfold size, constant `DROWN` 0.25 (1,000 → b 0.5). Hive mind: share ≥ 0.5 → 1.
- **Break-off:** k = round(share·n). k = n: the whole horde gets the sound as its target. Else the k members nearest
  the sound side of the horde leave: k ≥ hordeFormSize → a new horde at the horde's edge facing the sound, heading
  there; else k wanderers there with the target. The rest carry on. Conservation holds (internal moves).
- **One roll per sound.** Each discrete sound is its own roll (each gunshot). A sound re-sent every tick (vanilla
  `repeating`, or the same source and spot again within 2 real seconds) is one ongoing sound with one id; it ends 3
  real seconds after the last re-send. Walkers already heading for a spot within 20 tiles of a new sound's spot do not
  roll or split again: their investigate time is refreshed.
- **Switching:** a walker with a target switches only for a sound ≥ 6 dB louder where it stands (`SWITCH_DB`).
- **Heard → target:** the sound's spot, investigate until end + 6 h (sub-project 4 rules for arriving and gathering).
- **No distance cap** anywhere.

Reference table (850-tile gunshot, share that goes, hive mind applied) — tested exactly within ±2 points:

| Distance | Lone | 100 | 1,000 | 10,000 |
|---|---|---|---|---|
| 50 | 100 | 100 | 100 | 100 |
| 100 | 100 | 100 | 100 | 100 |
| 213 | 100 | 100 | 100 | 100 |
| 425 | 70 | 100 | 46 | 33 |
| 640 | 52 | 40 | 29 | 17 |
| 800 | 43 | 33 | 22 | 12 |
| 850+ | 0 | 0 | 0 | 0 |

## 3. Capture

- ZombieBuddy patch on the last `WorldSoundManager.addSound` overload (all others funnel into it); after the original
  runs, read the returned `WorldSound` (null = nothing). Skip `sourceIsZombie`. Record x, y, z, radius × vanilla's
  hearing multiplier (`getHearingMultiplier(hearing)` as `addSound` computes it), repeating, kind.
- Kind (viewer only): source `IsoGameCharacter` with a ranged primary weapon → GUNFIRE; `BaseVehicle` or
  `VehiclePart` owner → VEHICLE; `IsoGenerator`, `IsoRadio`, `IsoTelevision`, washers/dryers, jukebox, stove →
  APPLIANCE; alarm sources → ALARM; otherwise OTHER. 5b adds METAEVENT and THUNDER.
- Sound ids: an `IdentityHashMap<WorldSound, Long>` on the main thread assigned at capture, entries dropped when the
  sound leaves `soundList` (checked each tick) — the game pools WorldSound objects.
- Lock-free queue (`ConcurrentLinkedQueue`) to the simulation thread; the main thread never takes the core lock here.

## 4. Simulation (held zombies)

- `Simulation.step` drains the queue first. New sounds are handled in **one pass**: the union of their circles; a
  circle wider than the populated area walks occupied chunks only (`SpatialGrid.wanderersIn`); hordes by 64-tile
  blocks. Each walker inside a circle rolls once per sound (wanderers: lone chance; hordes: share and break-off).
- Ongoing sounds stay in an active list; a walker entering an ongoing sound's circle rolls once when it next updates
  (`ZombieRecord.rolledSoundId` / `Horde.rolledSoundId`, transient, not saved).
- Existing `Hearing.choose` keeps moaning as lowest priority; event pulls now come only from heard sounds (targets).
- Debug test sound (`Hordes_sound`) goes through the same capture path.

## 5. Real zombies

- Patch `WorldSoundManager.getSoundAttract(WorldSound, IsoZombie)` and `getBiggestSoundZomb(x, y, z, ignoreBySameType,
  IsoZombie)`: same filters as vanilla (stressZombies, radius, zombieIgnoreDist, sourceIsZombie / ignoreBySameType);
  score = `10^(L/20)` with L from the model at the zombie, minus 0.8 dB if the rooms differ and a further 1.5 dB if one
  side is outdoors (vanilla's 1.2 and 1.4 factors as dB); a sound the zombie did not hear (one remembered roll) scores
  as absent. Vanilla's `soundAttract * 2 < attract` switch is then the 6 dB rule.
- Remembered rolls: `WeakHashMap<IsoZombie, small LRU of (soundId → heard)>` on the main thread.
- The scoring is a pure function in `truehordes.sound.RealHearing` (testable offline); the patch only gathers inputs.

## 6. Viewer and hearing big hordes

- `Hordes_sounds()` (LuaApi, under the core lock): active and recently ended sounds (30 real seconds), each with id,
  x, y, radius, kind, age, ended flag, wanderers responded, hordes responded, zombies split off; and response lines
  (horde x, y → sound x, y) for hordes whose target is a live sound.
- Hordes map: a toggle button; circles as 6 concentric rings, alpha by the lone chance at that ring's distance, colour
  by kind; lines; a list panel (newest first, 20 rows: kind, radius, responded, split, age), click → centre the map.
- Hearing big hordes: each second, the loudest held horde within 3 chunks outside the loaded area whose moaning level
  at the player (same falloff, from its circle edge) is ≥ threshold plays a looped vanilla zombie-crowd sound from its
  direction; volume from the level (constant `MOAN_VOLUME`), minimum size `HEAR_HORDE_MIN` 50. Stops when none.

## 7. Error handling and performance

- Patches catch everything and fall back to vanilla behaviour (the original result) on error, logged once.
- Firefight speed test: 1.05M records, 10 shots per real second of radius 3,000 for an in-game hour: p99 update < 4
  ms, worst step slice within budget, CPU < 25 s per in-game hour (existing SimSpeedTest limits).
- Save format unchanged (4): targets and investigate times are already saved; sounds and rolls are not.

## 8. Build order (each step: tests first, suite green, commit, deploy)

1. Model: `truehordes.sim.SoundModel` (dB from radius, level, lone chance, horde share, hive mind) with the table test;
   sandbox options `SoundEdgeChance`, `SoundCertainShare`.
2. Held zombies: sound queue, one-pass broadcast, break-off, one roll per sound, repeat folding, 6 dB switch, no cap;
   firefight test; speed test; `Hordes_sound` through the queue. LouisvilleScenario firefight run.
3. Capture patch on `addSound` with kinds and ids (HookTargets/VerifyTargets updated).
4. Viewer: `Hordes_sounds()`, map layer, list panel.
5. Real zombies: `RealHearing` + the two patches.
6. Hearing big hordes.
7. `docs/TESTING.md` rows; self-review of the branch (no subagent review: user's usage).

## 9. In-game acceptance (TESTING.md)

Louder Gunshots on/off changes reach; an A-Life NPC firing shows on the viewer and pulls zombies; viewer circles,
fade, lines, list and click; a big horde nearby is heard; real zombies near the edge of a shot ignore it about half the
time; a firefight near a big held horde splits it.
