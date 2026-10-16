-- True Hordes: Lua helper.
-- The Java core is exposed by ZombieBuddy as the table TrueHordesJ. It is nil when ZombieBuddy is missing or the
-- jar was not approved; the Hordes_* console commands then print that instead of failing.

TrueHordes = TrueHordes or {}

local warned = false

local function J()
    return TrueHordesJ
end

local function notLoaded()
    if not warned then
        warned = true
        print("[TrueHordes] WARN Java core is not loaded (ZombieBuddy missing, or the True Hordes jar was not approved).")
    end
    return "True Hordes Java core is NOT loaded"
end

local function call(name, ...)
    local j = J()
    if not j then return notLoaded() end
    local result = j[name](...)
    print(result)
    return result
end

function Hordes_status() return call("status") end
function Hordes_stats() return call("stats") end
function Hordes_debug(category, on) return call("debug", category, on) end
function Hordes_save() return call("save") end
function Hordes_world() return call("world") end
function Hordes_hordes() return call("hordes") end

-- Debug: a real game sound (default: where the player stands, radius 1,000 tiles). Real and held zombies may hear it.
function Hordes_sound(x, y, radius)
    if x == nil or y == nil then
        local p = getPlayer and getPlayer()
        if not p then return call("sound", nil, nil, radius) end
        x, y = p:getX(), p:getY()
    end
    return call("sound", x, y, radius)
end

--- The loudest held horde the player can hear (see LuaApi.hordeMoan), or nil.
function TrueHordes.hordeMoan(x, y)
    local j = J()
    if not j then return nil end
    return j.hordeMoan(x, y)
end

-- Sound viewer data (see LuaApi.sounds), or nil when the Java core is not running.
function TrueHordes.sounds()
    local j = J()
    if not j then return nil end
    return j.sounds()
end

-- Debug-map data for a rectangle (see LuaApi.snapshot), or nil when the Java core is not running.
function TrueHordes.snapshot(minX, minY, maxX, maxY, maxPoints)
    local j = J()
    if not j then return nil end
    return j.snapshot(minX, minY, maxX, maxY, maxPoints)
end

--- Debug-map terrain codes for a rectangle (see LuaApi.terrain), or nil when the world map is not ready.
function TrueHordes.terrain(minX, minY, maxX, maxY, step)
    local j = J()
    if not j then return nil end
    return j.terrain(minX, minY, maxX, maxY, step)
end

--- Debug-map collision tiles for a small rectangle (see LuaApi.tiles), or nil.
function TrueHordes.tiles(minX, minY, maxX, maxY, max)
    local j = J()
    if not j then return nil end
    return j.tiles(minX, minY, maxX, maxY, max)
end

if Events and Events.OnGameStart then
    Events.OnGameStart.Add(function()
        if not J() then notLoaded() end
    end)
end
