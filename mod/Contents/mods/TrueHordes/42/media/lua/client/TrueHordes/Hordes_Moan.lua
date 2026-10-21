-- True Hordes: the player hears big hordes just outside the loaded area (sub-project 5a spec section 6).
-- Once a second, the loudest horde of 50+ (held off screen or real) whose moaning reaches the player (same energy falloff as every sound)
-- plays a few groans from where the horde is: more voices and louder the bigger and closer it is. Audio only (a free
-- emitter): it makes no world sound, so zombies do not hear it.
-- Sandbox HordeMoan turns it off (user, 2026-10-21: an option until the moan sounds right; design.md says to drop it then).
--
-- The clips (media/scripts/TrueHordes_sounds.txt) are the game's own groan files: its zombie voice events
-- (MaleZombieVoiceA...) started but were silent on a free emitter, without a zombie's sound parameters (in-game test
-- 2026-10-21). The game does not fade the clips with distance (distanceMin past any moan's reach), so they come from
-- the horde's real position (user, 2026-10-21: the first version, 10 tiles ahead at up to 80%, was "right in my ear")
-- and the volume carries the distance. Hordes_moanTest(n) plays clip n near you, to pick the clips.

local POLL_MS = 1000
-- Clips used (TrueHordesMoan1-26: 1-9 female, 10-18 fzombierand, 19-26 male). The male ones sound like burping.
local CLIPS = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18 }
local CLIP_COUNT = 26
-- Volume at the far end (just above the hearing threshold) and close in, before the sandbox volume.
local FAR_VOLUME, NEAR_VOLUME = 0.15, 1.0
local NEAR_MARGIN = 40 -- dB above the threshold at which a horde is at its loudest

local lastPoll = 0
local told = false

local function sandbox(name, fallback)
    local v = SandboxVars and SandboxVars.TrueHordes and SandboxVars.TrueHordes[name]
    if v == nil then return fallback end
    return v
end

local function play(x, y, z, clip, volume)
    local emitter = getWorld():getFreeEmitter(x, y, z)
    if not emitter then return nil end
    local id = emitter:playSound("TrueHordesMoan" .. clip)
    if id and id ~= 0 then emitter:setVolume(id, volume) end
    return id
end

local function onTick()
    local now = getTimestampMs()
    if now - lastPoll < POLL_MS then return end
    lastPoll = now
    if not sandbox("HordeMoan", true) then return end
    local player = getPlayer()
    if not player then return end
    local px, py = player:getX(), player:getY()
    local m = TrueHordes.hordeMoan(px, py)
    if not m then return end
    local dx, dy = m.x - px, m.y - py
    local d = math.sqrt(dx * dx + dy * dy)
    if d < 1 then return end
    local margin = m.level - 30 -- dB above the hearing threshold
    local voices = math.max(1, math.min(5, 1 + math.floor(margin / 8)))
    local k = math.max(0, math.min(1, margin / NEAR_MARGIN))
    local volume = (FAR_VOLUME + (NEAR_VOLUME - FAR_VOLUME) * k * k) * sandbox("HordeMoanVolume", 60) / 100
    if volume <= 0 then return end
    -- From inside the horde's circle, so a big horde sounds wide.
    local spread = 2 + math.sqrt(m.size or 50) * 0.5
    for i = 1, voices do
        local a = ZombRandFloat(0, 6.2832)
        local r = spread * math.sqrt(ZombRandFloat(0, 1))
        local id = play(m.x + math.cos(a) * r, m.y + math.sin(a) * r, 0, CLIPS[ZombRand(#CLIPS) + 1], volume)
        if not told and id ~= nil then
            told = true
            print(string.format("[TrueHordes] horde moan: %d zombies, %d tiles away, %.0f dB, %d voices at volume %.2f, sound %s",
                m.size or 0, d, m.level, voices, volume, (id ~= 0) and "started" or "FAILED to start"))
        end
    end
end

local nextTest = 0

--- Plays groan clip n (1-26; none: the next one) 6 tiles east of you at full volume, to hear which clips to keep.
function Hordes_moanTest(n)
    local player = getPlayer()
    if not player then return "no player" end
    if not n then
        nextTest = nextTest % CLIP_COUNT + 1
        n = nextTest
    end
    n = math.floor(n)
    if n < 1 or n > CLIP_COUNT then return "clips are 1-" .. CLIP_COUNT end
    local id = play(player:getX() + 6, player:getY(), player:getZ(), n, 1.0)
    local msg = "TrueHordesMoan" .. n .. ((id and id ~= 0) and " playing" or " FAILED")
    print("[TrueHordes] " .. msg)
    return msg
end

Events.OnTick.Add(onTick)
