title @a title {"selector":"@s","color":"red"}
title @a subtitle [{"text":"¡","color":"aqua"},{"text":"ha muerto","color":"yellow"},{"text":"!","color":"aqua"}]
playsound terranova.death player @e[type=minecraft:player] 0 0 0 99999999 1 1
scoreboard players set @a[scores={muertes=1..}] muertes 0
