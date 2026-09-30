# The standard library generator

Most of the standard library — about 150 types, more than 780 functions and
properties, 104 events and the constant tables of `Material`, `Sound` and the other
keyed types — is generated from compact specifications by
[`tools/stdlib-gen/generate.py`](../tools/stdlib-gen/generate.py). One line describes
one declaration: its script signature, documentation, threading and side effects, and
the Java code implementing it on Paper.

The generated Java is committed, so building TachyonScript needs neither Python nor
the Paper sources. Run the generator after editing a specification:

```sh
python tools/stdlib-gen/generate.py                       # keep the constant tables
python tools/stdlib-gen/generate.py --paper-sources <dir> # also regenerate them
./gradlew -q :tachyon-cli:run --args=docs > docs/language/reference.md
```

`<dir>` is a folder with the Paper API sources (`org/bukkit/...`), for example the
content of the `paper-api-<version>-sources.jar`.

## Outputs

| File | Content |
|------|---------|
| `tachyon-stdlib/.../stdlib/generated/<Area>Api.java` | Declarations (Bukkit-free), one class per area |
| `tachyon-stdlib/.../stdlib/generated/GeneratedTypes.java` | The generated types, supertypes first |
| `tachyon-stdlib/.../stdlib/generated/CoreBindings.java` | Implementations of the server-independent `core` area |
| `tachyon-stdlib/src/main/resources/.../keys/*.txt` | Constant tables (`NAME minecraft:key`) |
| `tachyon-platform-paper/.../generated/<Area>Bindings.java` | Paper implementations and event classes |
| `tachyon-platform-paper/.../generated/GeneratedBindings.java` | Types, codecs, key lookups, events, live keys |

On a server, the constant tables are replaced by the contents of the server's
registries (`PaperPlatform.useServerKeys`), so data pack additions work too.

## Specification format

Files in `tools/stdlib-gen/spec/*.api` are read in name order. Lines starting with `#`
are comments.

```text
import org.bukkit.Bukkit                         # Java imports of the generated Paper bindings
area Players                                     # following declarations belong to this area
area Core core                                   # 'core': implemented without a server (JDK only)

type Vector storable = org.bukkit.util.Vector : A 3D vector.
  codec: Codecs.VECTOR                           # how values are saved
type Material keyed=enum:org/bukkit/Material.java lookup=keyed-enum = org.bukkit.Material : A block or item type.

prop Player.food: int rw entity : Food level, from 0 to 20.
  get: $.getFoodLevel()                          # $ is the receiver
  set: $.setFoodLevel(Math.clamp(v, 0, 20))      # v is the new value

fn Player.give(item: ItemStack) entity modifies : Gives items.
  do: Items.give($, item)
  ex: player.give(ItemStack(Material.BREAD, 16))  # example, compiled by the documentation tests

fn Material.matchMaterial(name: string): Material? static : A material by name.
  do: Items.material(name)

event player.respawn = org.bukkit.event.player.PlayerRespawnEvent entity : A player respawns.
  var player: Player = $.getPlayer() : The respawning player.
  prop respawnLocation: Location rw : Where the player respawns.
    get: $.getRespawnLocation()
    set: $.setRespawnLocation(v)
```

* An owner that is not a type (`server`, `time`, ...) declares a global function or
  property; `static` makes `Type.name` a global too (`Material.matchMaterial`). A global
  function named like a type reads like a constructor (`ItemStack(...)`).
* Threading: `entity`, `region`, `global` or `async`, optionally naming the parameter
  that decides the owning thread: `entity(player)`. Writes and `modifies` functions run on
  that thread through `PaperContext` (at once when already there, forwarded otherwise;
  functions returning a value wait when called off the server threads).
* Effects: `pure`, `reads`, `modifies`, `io`.
* Keys: `enum:<file>[#Nested]`, `fields:<file>:<factory>` or `list:A,B`; lookups:
  `enum`, `keyed-enum` or `registry:<RegistryKey constant>`.
* Event types are named after their Java class and get `Cancellable` for `cancellable`
  events; `extends` names a supertype (`extends EntityDamageEvent`).
* Bodies are single Java expressions (or `{ ... }` blocks). Longer code goes into the
  helper classes of `dev.tachyonscript.platform.paper.lib` (Paper) and
  `dev.tachyonscript.stdlib.support` (core); `ctx` is the `PaperContext`.

The generator checks the specifications (unknown types, duplicate or conflicting
members, missing `get`/`set`/`do`, reserved parameter names) and reports the file and
line of a mistake. Generated bindings carry a `// file:line` comment pointing back to
their specification, so a Java compile error leads straight to the line to fix.

## Tests

* `PaperBindingsTest.bindsEveryStandardDeclaration` fails if a declaration has no
  Paper implementation or a type no Java class.
* The documentation tests compile every `ex:` example (they appear in the reference).
* `CoreLibraryTest` runs the core area through real scripts; the test platform stubs
  the declarations it does not fake, so every script links in tests.
