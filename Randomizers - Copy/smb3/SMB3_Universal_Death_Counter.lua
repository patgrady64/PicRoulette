--[[
SMB3 Universal Death Counter for FCEUX
Version 1.0

Designed for:
  * Super Mario Bros. 3 USA vanilla
  * Randomizers and hacks that retain the normal SMB3 gameplay RAM layout

What it watches:
  $00F1 = Player_IsDying
      0 = alive
      1 = dying
      2 = fell/dropped off screen
      3 = time-up death

The counter increases only when the value changes from 0 to 1, 2, or 3
during a normal SMB3 gameplay mode. Extra lives do not affect the count.

Controls:
  Ctrl+R          Reset the counter to 0
  Ctrl+Up         Add one death manually
  Ctrl+Down       Remove one death manually
  Ctrl+H          Show/hide the control reminder

Savestates:
  Loading a savestate does NOT rewind the Lua death total.
  The script resynchronizes with game RAM to prevent a false extra death.
--]]

local ADDR_PLAYER_IS_DYING = 0x00F1
local ADDR_UPDATE_SELECT   = 0x0100

-- Known SMB3 gameplay update modes:
-- $80 = vertical gameplay
-- $A0 = 32-pixel partition gameplay
-- $C0 = normal gameplay
local GAMEPLAY_MODES = {
    [0x80] = true,
    [0xA0] = true,
    [0xC0] = true
}

local deaths = 0
local previousDying = memory.readbyte(ADDR_PLAYER_IS_DYING)
local wasInGameplay = false
local showHelp = true
local flashFrames = 0

local previousResetHeld = false
local previousAddHeld = false
local previousSubtractHeld = false
local previousHelpHeld = false

local function isValidDeathState(value)
    return value == 1 or value == 2 or value == 3
end

local function readGameplayState()
    local updateMode = memory.readbyte(ADDR_UPDATE_SELECT)
    local dyingState = memory.readbyte(ADDR_PLAYER_IS_DYING)
    return GAMEPLAY_MODES[updateMode] == true, dyingState, updateMode
end

local function syncAfterStateLoad()
    local inGameplay, dyingState = readGameplayState()
    wasInGameplay = inGameplay
    previousDying = dyingState
    flashFrames = 0
end

savestate.registerload(syncAfterStateLoad)

local function handleKeyboard()
    local keys = input.get() or {}

    local resetHeld = keys.control and keys.R
    local addHeld = keys.control and keys.up
    local subtractHeld = keys.control and keys.down
    local helpHeld = keys.control and keys.H

    if resetHeld and not previousResetHeld then
        deaths = 0
        flashFrames = 45
        emu.message("SMB3 death counter reset")
    end

    if addHeld and not previousAddHeld then
        deaths = deaths + 1
        flashFrames = 45
        emu.message("Death added manually: " .. deaths)
    end

    if subtractHeld and not previousSubtractHeld then
        if deaths > 0 then
            deaths = deaths - 1
        end
        flashFrames = 45
        emu.message("Death removed manually: " .. deaths)
    end

    if helpHeld and not previousHelpHeld then
        showHelp = not showHelp
    end

    previousResetHeld = resetHeld
    previousAddHeld = addHeld
    previousSubtractHeld = subtractHeld
    previousHelpHeld = helpHeld
end

local function updateCounter()
    local inGameplay, dyingState = readGameplayState()

    if inGameplay then
        -- When gameplay has just begun, synchronize without counting.
        if wasInGameplay then
            if previousDying == 0 and isValidDeathState(dyingState) then
                deaths = deaths + 1
                flashFrames = 90
                emu.message("Death counted: " .. deaths)
            end
        end

        previousDying = dyingState
    else
        -- Avoid carrying a context-dependent zero-page value into gameplay.
        previousDying = 0
    end

    wasInGameplay = inGameplay
end

local function drawOverlay()
    local textColor = "white"
    local borderColor = "white"

    if flashFrames > 0 then
        textColor = "yellow"
        borderColor = "yellow"
        flashFrames = flashFrames - 1
    end

    gui.box(2, 8, 112, 27, "black", borderColor)
    gui.text(7, 13, string.format("DEATHS: %d", deaths), textColor, "black")

    if showHelp then
        gui.box(2, 30, 180, 51, "black", "gray")
        gui.text(6, 33, "Ctrl+R reset | Ctrl+Up add", "white", "black")
        gui.text(6, 42, "Ctrl+Down remove | Ctrl+H hide", "white", "black")
    end
end

local romName = rom.getfilename() or "Unknown ROM"
local romHash = rom.gethash("md5") or "unknown"
emu.print("SMB3 Universal Death Counter started")
emu.print("ROM: " .. romName)
emu.print("MD5: " .. romHash)
emu.print("Watching Player_IsDying at $00F1")
emu.print("Ctrl+R reset | Ctrl+Up add | Ctrl+Down remove | Ctrl+H help")

while true do
    handleKeyboard()
    updateCounter()
    drawOverlay()
    emu.frameadvance()
end
