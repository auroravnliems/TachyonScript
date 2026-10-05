# Skript source comparison

The supplied local Skript checkout was used to identify useful API gaps. TachyonScript
keeps its lexer, compiler, typed registry, IR, interpreter and engine; this work does
not embed or replace them with Skript's engine.

`summary.json` and `features.tsv` inventory 941 source classes: 29 have a recorded
review decision and 912 remain unreviewed. The 29 decisions are 12 implemented,
6 adapted, 6 existing, 1 partial and 4 missing. These count classes, not distinct
features. Similarly, 79 of 162 explicit Bukkit event references have a TachyonScript
binding; base and deprecated classes make the remainder unsuitable as a feature count.

| Area | Decision |
|---|---|
| Metadata | Own Entity/Block/World store; values belong to the latest writer's script version, with retirement and host lifecycle cleanup. It does not read another plugin's Bukkit metadata. |
| Block data | Typed `Block.data` and `BlockData` operations; retain the old `Block.blockData` string API. |
| Signs | Typed FRONT/BACK sides, lines 0–3, color, glowing text and wax; errors for non-sign blocks. |
| Displays | Typed billboard/brightness/transforms and item/block displays; retain existing TextDisplay helpers. |
| Rotations and collision | Radian axis angles, normalized finite quaternions, float range checks, copied box operations. |
| Statistics | Typed online-player overloads for no parameter, material or entity. `PLAY_ONE_MINUTE` is ticks despite its name; offline statistics remain unimplemented. |
| Loot and anvils | LootContext/LootTable and AnvilView text/cost remain missing. |

`reviewed.json` maps individual source classes to decisions. `tools/skript-audit.py`
can regenerate the inventory from the supplied checkout; its Java-source fingerprint
is `67783f8582a4f78a343a97c7be3da05372e5d52cc823c76dbd2d972a500bad48`.
Unreviewed items have no implied compatibility guarantee.
