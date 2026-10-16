# Round 8: walkers and herds (design)

Status: approved with the user, 2026-10-16, after the first sub-project 5a in-game test ("it just feels like zombies on
the streets"). Builds on branch `sp5a-sound-core`. No plan document (user's usage); build order is section 8.

## 1. Why

The user's day-6.5 save: two thirds of 65,000 lone zombies on roads, 105 walking hordes (biggest 427), about 2,600
crowded at the map's rim, and held zombies bouncing off the loaded area so a sound near the player never brings them.
Cause: since round 6 every lone zombie travels the roads like a horde. Wanted: The Walking Dead's walkers and herds —
lone zombies mill in their neighbourhood, neighbourhoods clot into hordes, town hordes roam their town, and when a horde
"runs out of food" it leaves along the highways; hordes, not loners, make the crossings.

## 2. Lone zombies: millers and travellers

- Rolled once per zombie (with the straggler roll): **traveller** with chance `TravellerShare` (sandbox, default 0.05),
  else **miller**. Stragglers stay stragglers (they linger).
- **Travellers** move exactly as today (road travel, waves) and ignore lockdown.
- **Millers** move with the same walking rules but a **soft home pull**: home = where the zombie spawned, or where it
  ended up after following a sound, joining/leaving a group, or being left behind by a horde. Beyond `HOME_RADIUS`
  (150 tiles) each heading decision bends towards home with chance `(d − R) / R` (capped at 1); never a wall, never a
  snap; a heard sound or a pull ignores home entirely. Home is transient (not saved: after a load, home = where it is).

## 3. Neighbourhoods clot

- Millers drift towards the biggest group they can see or hear nearby (a horde in sight or moaning in range, or a
  cluster of 3+ millers in sight): a pull with chance per hour `CROWD_DRIFT` (ramped by the day ramp, like follow and
  join). Existing bump/follow/join/gather rules unchanged.
- Target (tuning, offline Louisville): **about 65% of a town's zombies in hordes by day 7**.

## 4. Towns and the hunger clock

- **Town mask** from the world map: a 64-tile block is town if at least `TOWN_BUILDINGS` (12) of the 64 chunks within
  its 3×3 block neighbourhood... (computed once from building chunks; tuning value).
- **Hunger day** per horde: `LockdownDays × (500 / n)^0.15 × jitter`, jitter uniform 0.8..1.2 fixed per horde (from its
  id); n = current size, so growing makes it leave sooner; a merge keeps the earlier day.
  (10 → day 12, 50 → 10, 500 → 7, 5,000 → 5; `LockdownDays` sandbox, default 7.)
- Before its day, a horde whose centre is in town turns back softly at the town's edge (same bend as the home pull);
  after it, it travels as a wave. Hordes outside towns: no lockdown.

## 5. Walking into the loaded area

- Held wanderers no longer turn back near the loaded area: a wanderer whose step ends in a loaded chunk with a floor
  becomes real there (the existing spawn path); otherwise it waits at the edge.
- Hordes: the centre may move into the near-loaded band; members whose slot is in a loaded chunk are laid out (existing
  partial layout) each update; when the centre is inside a loaded chunk all members are laid out (no floor → they stay
  held at the edge as wanderers).

## 6. Map edge

- The map's edge (out of map) turns a wave back (the one exception to "waves don't turn round"); millers never reach it.

## 7. Sound labels

- `SoundEvent.HELICOPTER`: a sourceless sound at the active helicopter's position. `SoundEvent.META`: sounds made while
  vanilla's ambient meta-event code runs (`AmbientStreamManager` / `AmbientSoundManager` methods that call addSound),
  flagged by enter/exit patches. Viewer colours and names for both.

## 8. Build order (tests first; commit and deploy each)

1. Sound labels; map edge turns waves back.
2. Walking into the loaded area.
3. Millers and travellers (home pull).
4. Crowd drift.
5. Town mask and hunger clock.
6. Tuning: replay the user's save and LouisvilleScenario — roads share, % in hordes by day 7 (~65%), departure
   spread, nothing piled at the rim.
