-- True Hordes: multiplayer, server side (sub-project 7).
-- The dedicated server runs the population, the simulation and the horde "brain" (who is in which horde, who follows
-- whom, where a horde is heading); each client steers the real zombies it owns. Once a second every player gets
-- "view": the horde tags of the zombies near them, the far-sight figures and the loudest horde moan they can hear.
-- Event audio (meta gunshots, screams, dogs) goes to every client, which plays it at its own player's distance.
-- Clients send "lure" (a zombie of theirs heard a sound), "off" (one reached the loaded edge heading out), and admins
-- "cmd" (the Hordes_* console commands) and "map" (the debug map).

if not isServer() then return end

local MODULE = "TrueHordes"
local VIEW_MS = 1000
local TAG_RADIUS = 90
local FAR_MAX = 250

local lastView = 0

local function sandbox(name, fallback)
    local v = SandboxVars and SandboxVars.TrueHordes and SandboxVars.TrueHordes[name]
    if v == nil then return fallback end
    return v
end

local function isAdmin(player)
    if not player then return false end
    local ok, admin = pcall(function()
        local role = player:getRole()
        return role ~= nil and role:hasAdminPower()
    end)
    if ok and admin then return true end
    local ok2, level = pcall(function() return player:getAccessLevel() end)
    return ok2 and level ~= nil and level ~= "" and string.lower(level) ~= "none"
end

local function reply(player, text)
    sendServerCommand(player, MODULE, "reply", { text = tostring(text) })
end

local function onTick()
    local J = TrueHordesJ
    if not J or not J.mpAudio then return end
    local audio = J.mpAudio()
    if audio and audio.n and audio.n > 0 then
        for i = 1, audio.n do sendServerCommand(MODULE, "play", audio[i]) end
    end
    local now = getTimestampMs()
    if now - lastView < VIEW_MS then return end
    lastView = now
    local players = getOnlinePlayers()
    if not players then return end
    local farOn, reach = sandbox("FarSight", true), sandbox("FarSightTiles", 200)
    for i = 0, players:size() - 1 do
        local p = players:get(i)
        if p and not p:isDead() then
            local x, y = p:getX(), p:getY()
            local args = { tags = J.mpTags(x, y, TAG_RADIUS), x = x, y = y, r = TAG_RADIUS }
            if farOn then args.far = J.farSight(x, y, reach, FAR_MAX) end
            args.moan = J.hordeMoan(x, y)
            sendServerCommand(p, MODULE, "view", args)
        end
    end
end

local function runCommand(J, player, args)
    local name = args.name
    local x, y = player:getX(), player:getY()
    if name == "roadHorde" then return J.roadHorde(args.a, x, y, args.b, args.c) end
    if name == "ringHordes" then return J.ringHordes(args.a, args.b, args.c, x, y) end
    if name == "sound" then return J.sound(args.a or x, args.b or y, args.c) end
    if name == "debug" then return J.debug(args.a, args.b) end
    if name == "status" or name == "stats" or name == "hordes" or name == "save" or name == "world" then
        return J[name]()
    end
    return "unknown command " .. tostring(name)
end

local function onClientCommand(module, command, player, args)
    if module ~= MODULE then return end
    local J = TrueHordesJ
    if not J or not J.mpLure then return end
    args = args or {}
    if command == "lure" then
        J.mpLure(args.o, args.x, args.y)
    elseif command == "off" then
        J.mpOff(args.o, args.x, args.y, args.v)
    elseif command == "chase" or command == "chasefar" then
        J.mpChase(args.o, args.x, args.y, args.v, command == "chasefar")
    elseif command == "cmd" then
        if not isAdmin(player) then
            reply(player, "True Hordes commands are for admins")
            return
        end
        local ok, result = pcall(runCommand, J, player, args)
        reply(player, ok and result or ("failed: " .. tostring(result)))
    elseif command == "map" then
        if not isAdmin(player) then return end
        sendServerCommand(player, MODULE, "map",
            { snap = J.snapshot(args.x0, args.y0, args.x1, args.y1, args.max) })
    end
end

Events.OnTick.Add(onTick)
Events.OnClientCommand.Add(onClientCommand)
