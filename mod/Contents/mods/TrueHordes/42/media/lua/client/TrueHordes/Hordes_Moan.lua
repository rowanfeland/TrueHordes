-- True Hordes: the player hears big hordes just outside the loaded area (sub-project 5a spec section 6).
-- Once a second, the loudest horde of 50+ (held off screen or real) whose moaning reaches the player (same energy falloff as every sound)
-- is voiced by a chorus of the game's own zombie chase voices: 3 to 10 at once, more the bigger the horde, without
-- getting louder (user, 2026-10-21: "use the gurgling/chase sounds, overlay 3-10 at a time"). Audio only (free
-- emitters): it makes no world sound, so zombies do not hear it.
-- Sandbox HordeMoan turns it off (user, 2026-10-21: an option until the moan sounds right; design.md says to drop it then).
--
-- The voices are the events a zombie plays (MaleZombieVoiceA...), looping, with the FMOD parameters a zombie gives them
-- set by hand: ZombieState LockTarget (3, the chase gurgle) and PlayerDistance. Without those they started but were
-- silent (in-game test 2026-10-21). The events fade with distance on their own, so the chorus stands at most
-- VOICE_RANGE tiles away, on the line to the horde, and the volume carries the rest of the distance.
-- Hordes_moanTest(d) starts or stops one chase voice d tiles east of you, to hear how far it carries.

local POLL_MS = 1000
local VOICES = { "MaleZombieVoiceA", "MaleZombieVoiceB", "MaleZombieVoiceC",
    "FemaleZombieVoiceA", "FemaleZombieVoiceB", "FemaleZombieVoiceC" }
local LOCK_TARGET = 3 -- ParameterZombieState.State.LockTarget
local MIN_VOICES, MAX_VOICES = 3, 10
local VOICE_RANGE = 12 -- tiles: the farthest the chorus stands from the player
-- Volume of the whole chorus at the far end (just above the hearing threshold) and close in, before the sandbox volume.
-- Halved after the 2026-10-21 test: held hordes were too loud.
local FAR_VOLUME, NEAR_VOLUME = 0.08, 0.5
local NEAR_MARGIN = 40 -- dB above the threshold at which a horde is at its loudest

local lastPoll = 0
local told = false
local chorus = {} -- { emitter, id, ox, oy } per playing voice

local function sandbox(name, fallback)
    local v = SandboxVars and SandboxVars.TrueHordes and SandboxVars.TrueHordes[name]
    if v == nil then return fallback end
    return v
end

local function setParams(emitter, id, distance)
    pcall(function()
        emitter:setParameterValueByName(id, "ZombieState", LOCK_TARGET)
        emitter:setParameterValueByName(id, "PlayerDistance", math.ceil(distance))
        emitter:setParameterValueByName(id, "CharacterInside", 0)
    end)
end

-- Starts one looping chase voice at (x, y); returns { emitter, id } or nil.
local function start(x, y, distance)
    local emitter = getWorld():getFreeEmitter(x, y, 0)
    if not emitter then return nil end
    local id = emitter:playSound(VOICES[ZombRand(#VOICES) + 1])
    if not id or id == 0 then return nil end
    setParams(emitter, id, distance)
    return { emitter = emitter, id = id }
end

local function stop(v)
    pcall(function() v.emitter:stopSound(v.id) end)
end

local function silence()
    for i = #chorus, 1, -1 do
        stop(chorus[i])
        chorus[i] = nil
    end
end

local function onTick()
    local now = getTimestampMs()
    if now - lastPoll < POLL_MS then return end
    lastPoll = now
    local player = getPlayer()
    local m = player and sandbox("HordeMoan", true) and TrueHordes.hordeMoan(player:getX(), player:getY())
    if not m then return silence() end
    local px, py = player:getX(), player:getY()
    local dx, dy = m.x - px, m.y - py
    local d = math.sqrt(dx * dx + dy * dy)
    if d < 1 then return silence() end
    local margin = m.level - 30 -- dB above the hearing threshold
    local k = math.max(0, math.min(1, margin / NEAR_MARGIN))
    local total = (FAR_VOLUME + (NEAR_VOLUME - FAR_VOLUME) * k * k) * sandbox("HordeMoanVolume", 60) / 100
    if total <= 0 then return silence() end
    local size = m.size or 50
    local want = math.max(MIN_VOICES, math.min(MAX_VOICES, MIN_VOICES + math.floor((size - 50) / 50)))
    -- Same loudness however many voices: their energies add up to the chorus volume.
    local each = total / math.sqrt(want)
    local r = math.min(d, VOICE_RANGE)
    local cx, cy = px + dx / d * r, py + dy / d * r
    local spread = math.min(r * 0.5, 2 + math.sqrt(size) * 0.5) -- a big horde sounds wide
    while #chorus > want do
        stop(chorus[#chorus])
        chorus[#chorus] = nil
    end
    for i = #chorus, 1, -1 do
        local v = chorus[i]
        if not v.emitter:isPlaying(v.id) then
            table.remove(chorus, i)
        end
    end
    while #chorus < want do
        local a = ZombRandFloat(0, 6.2832)
        local s = spread * math.sqrt(ZombRandFloat(0, 1))
        local v = start(cx + math.cos(a) * s, cy + math.sin(a) * s, r)
        if not v then break end
        v.ox, v.oy = math.cos(a) * s, math.sin(a) * s
        chorus[#chorus + 1] = v
    end
    for _, v in ipairs(chorus) do
        v.emitter:setPos(cx + v.ox, cy + v.oy, 0)
        v.emitter:setVolume(v.id, each)
        setParams(v.emitter, v.id, r)
    end
    if not told then
        told = true
        print(string.format("[TrueHordes] horde moan: %d zombies, %d tiles away, %.0f dB, %d voices at %.2f each, %s",
            size, d, m.level, want, each, (#chorus > 0) and "started" or "FAILED to start"))
    end
end

local test = nil

--- Starts (or, called again, stops) one chase voice d tiles east of you (default 10) at full volume.
function Hordes_moanTest(d)
    if test then
        stop(test)
        test = nil
        return "stopped"
    end
    local player = getPlayer()
    if not player then return "no player" end
    d = d or 10
    test = start(player:getX() + d, player:getY(), d)
    if not test then return "FAILED" end
    test.emitter:setVolume(test.id, 1.0)
    return "chase voice playing " .. d .. " tiles east; call again to stop"
end

Events.OnTick.Add(onTick)
