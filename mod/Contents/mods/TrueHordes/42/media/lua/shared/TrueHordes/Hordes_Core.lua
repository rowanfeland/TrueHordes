-- True Hordes: Lua helper.
-- The Java core is exposed by ZombieBuddy as the table TrueHordesJ. It is nil when ZombieBuddy is missing or the
-- jar was not approved; the Hordes_* console commands then print that instead of failing.

TrueHordes = TrueHordes or {}
TrueHordes.net = TrueHordes.net or {}

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

--- In a multiplayer game the population lives on the server (sub-project 7): commands go there (admins only) and
--- the answer comes back as a chat line and in console.txt.
local function mp() return isClient and isClient() end

local function remote(name, a, b, c)
    local p = getPlayer and getPlayer()
    if not p then return "no player" end
    sendClientCommand(p, "TrueHordes", "cmd", { name = name, a = a, b = b, c = c })
    return "sent to the server (admins only): " .. name
end

local function server(name, a, b, c)
    if mp() then
        local r = remote(name, a, b, c)
        print(r)
        return r
    end
    -- Only the arguments given: Java methods count trailing nils as arguments.
    if c ~= nil then return call(name, a, b, c) end
    if b ~= nil then return call(name, a, b) end
    if a ~= nil then return call(name, a) end
    return call(name)
end

function Hordes_status() return server("status") end
function Hordes_stats() return server("stats") end
function Hordes_debug(category, on) return server("debug", category, on) end
function Hordes_save() return server("save") end
function Hordes_world() return server("world") end
function Hordes_hordes() return server("hordes") end

-- Test: a horde of n (default 300) walking down the nearest highway towards you from about 250 tiles away, or from x, y.
function Hordes_roadHorde(n, x, y)
    if mp() then return remote("roadHorde", n, x, y) end
    local p = getPlayer and getPlayer()
    if not p then return call("roadHorde", n, 0, 0, x, y) end
    return call("roadHorde", n, p:getX(), p:getY(), x, y)
end

-- Test: count (default 6) dormant hordes of size (default 100) on a circle of radius (default 120) round you.
-- They stay idle until a gunshot within 400 tiles (or a louder horde's moaning) sets them loose.
function Hordes_ringHordes(count, size, radius)
    if mp() then return remote("ringHordes", count, size, radius) end
    local p = getPlayer and getPlayer()
    if not p then return call("ringHordes", count, size, radius, 0, 0) end
    return call("ringHordes", count, size, radius, p:getX(), p:getY())
end

--- Far-sight figures round (x, y) (see LuaApi.farSight), or nil.
function TrueHordes.farSight(x, y, reach, max)
    if mp() then return TrueHordes.net and TrueHordes.net.far end
    local j = J()
    if not j or not j.farSight then return nil end
    return j.farSight(x, y, reach, max)
end

-- Debug: a real game sound (default: where the player stands, radius 1,000 tiles). Real and held zombies may hear it.
function Hordes_sound(x, y, radius)
    if mp() then return remote("sound", x, y, radius) end
    if x == nil or y == nil then
        local p = getPlayer and getPlayer()
        if not p then return call("sound", nil, nil, radius) end
        x, y = p:getX(), p:getY()
    end
    return call("sound", x, y, radius)
end

--- The loudest held horde the player can hear (see LuaApi.hordeMoan), or nil.
function TrueHordes.hordeMoan(x, y)
    if mp() then return TrueHordes.net and TrueHordes.net.moan end
    local j = J()
    if not j then return nil end
    return j.hordeMoan(x, y)
end

-- Sound viewer data (see LuaApi.sounds), or nil when the Java core is not running.
function TrueHordes.sounds()
    if mp() then return nil end
    local j = J()
    if not j then return nil end
    return j.sounds()
end

-- Debug-map data for a rectangle (see LuaApi.snapshot), or nil when the Java core is not running.
local lastMapAsk = 0

function TrueHordes.snapshot(minX, minY, maxX, maxY, maxPoints)
    if mp() then
        -- The server's data (admins only), asked for at most once a second; the last answer meanwhile.
        local now = getTimestampMs()
        local p = getPlayer and getPlayer()
        if p and now - lastMapAsk >= 1000 then
            lastMapAsk = now
            sendClientCommand(p, "TrueHordes", "map", { x0 = minX, y0 = minY, x1 = maxX, y1 = maxY, max = maxPoints })
        end
        return TrueHordes.net and TrueHordes.net.map
    end
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
