-- True Hordes: multiplayer, client side (sub-project 7). See Hordes_Net_Server.lua.
-- Every tick the Java side steers the real zombies this client owns (by the server's horde tags) and queues
-- messages for the server; this file carries them both ways. Only in a multiplayer game (isClient()).

if not isClient() then return end

TrueHordes = TrueHordes or {}
TrueHordes.net = TrueHordes.net or {}

local MODULE = "TrueHordes"
local KINDS = { [1] = "lure", [2] = "off", [3] = "chase", [4] = "chasefar" }

local function onServerCommand(module, command, args)
    if module ~= MODULE then return end
    local J = TrueHordesJ
    args = args or {}
    if command == "view" then
        TrueHordes.net.far = args.far
        TrueHordes.net.moan = args.moan
        if J and J.mpApplyTags and args.tags then J.mpApplyTags(args.tags, args.x, args.y, args.r) end
    elseif command == "play" then
        if J and J.playEvent then J.playEvent(args.s, args.x, args.y, args.r) end
    elseif command == "reply" then
        print("[TrueHordes] " .. tostring(args.text))
        local p = getPlayer()
        if p and p.addLineChatElement then pcall(function() p:addLineChatElement(tostring(args.text)) end) end
    elseif command == "map" then
        TrueHordes.net.map = args.snap
    end
end

local function onTick()
    local J = TrueHordesJ
    if not J or not J.clientTick then return end
    J.clientTick()
    local out = J.mpOut()
    if not out or not out.n or out.n == 0 then return end
    local p = getPlayer()
    if not p then return end
    for i = 1, out.n do
        local e = out[i]
        local cmd = KINDS[e.k]
        if cmd then sendClientCommand(p, MODULE, cmd, e) end
    end
end

Events.OnServerCommand.Add(onServerCommand)
Events.OnTick.Add(onTick)
