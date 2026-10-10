-- True Hordes: Lua helper.
-- The Java core is exposed by ZombieBuddy as the table TrueHordesJ. It is nil when ZombieBuddy is missing or the
-- jar was not approved; the TH_* console commands then print that instead of failing.

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

function TH_status() return call("status") end
function TH_stats() return call("stats") end
function TH_debug(category, on) return call("debug", category, on) end
function TH_save() return call("save") end
function TH_clearTest() return call("clearTest") end

function TH_seedTest(n)
    local player = getPlayer()
    if not player then
        print("TH_seedTest: no player")
        return "no player"
    end
    return call("seedTest", n or 100, player:getX(), player:getY())
end

--- Debug-map data for a rectangle (see LuaApi.snapshot), or nil when the Java core is not running.
function TrueHordes.snapshot(minX, minY, maxX, maxY, maxPoints)
    local j = J()
    if not j then return nil end
    return j.snapshot(minX, minY, maxX, maxY, maxPoints)
end

if Events and Events.OnGameStart then
    Events.OnGameStart.Add(function()
        if not J() then notLoaded() end
    end)
end
