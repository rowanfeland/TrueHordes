-- True Hordes: crops a big horde trampled off screen (sub-project 8).
-- When an area loads, the Java side finds farm plants in the trail of a horde that walked through while nobody was
-- there; this destroys them through the game's own farming system, so its records and the plant agree. Server and
-- single player only (the farming system lives there). Any error is logged once and the layer switches off.

local dead = false

local function onTick()
    if dead or isClient() then return end
    local j = TrueHordesJ
    if j == nil or type(j.takeCrops) ~= "function" then return end
    local ok, err = pcall(function()
        local s = j.takeCrops()
        if s == nil or s == "" then return end
        local farming = SFarmingSystem and SFarmingSystem.instance
        if farming == nil then return end
        for x, y, z in string.gmatch(s, "(%-?%d+),(%-?%d+),(%-?%d+);") do
            local plant = farming:getLuaObjectAt(tonumber(x), tonumber(y), tonumber(z))
            if plant and plant.state ~= "plow" and plant.state ~= "destroyed" and plant.state ~= "dead" then
                plant:destroyThis()
            end
        end
    end)
    if not ok then
        dead = true
        print("[TrueHordes] trampling crops switched off: " .. tostring(err))
    end
end

Events.OnTick.Add(onTick)
