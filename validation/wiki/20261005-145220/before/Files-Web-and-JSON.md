# Files, web and JSON

For Java tests, `TestPlatform` keeps text files in memory and records HTTP requests without
connecting to the network. Configure `platform.interactions().http(status, body)` before
the call; the reply runs on the next simulated tick. Status `-1` can model a connection
failure. These tests exercise script decisions, not real transport or filesystem security.

## Files

Scripts can read and write text files in one folder: `plugins/TachyonScript/files/`. Paths are
relative to it and cannot leave it, so a script can never read or overwrite other files of the
server.

```tys
event player.join {
    let date = time.format(time.now, "yyyy-MM-dd HH:mm:ss")
    files.append("logs/joins.log", "{date} {player.name} {player.address ?? "?"}")
}

@permission("server.rules.edit")
command rules.add(line: string...) {
    files.append("rules.txt", line)
    sender.send("<green>Rule added.")
}

command rules {
    let lines = files.lines("rules.txt")
    if lines.isEmpty {
        sender.send("<gray>No rules yet.")
        return
    }
    sender.send("<gold>Server rules:")
    for i in 0..<lines.size {
        sender.send("<yellow>{i + 1}.</yellow> {lines[i]}")
    }
}
```

| Function | Result |
|----------|--------|
| `files.read(path)` | the whole text, or `null` if the file does not exist |
| `files.lines(path)` | the lines (an empty list if the file does not exist) |
| `files.write(path, text)` | replaces the file's text, creating folders as needed |
| `files.append(path, line)` | adds a line at the end (creates the file) |
| `files.exists(path)` | whether it exists |
| `files.delete(path)` | deletes it; `false` if it did not exist |
| `files.list(folder)` | the names of the files in a folder |

* Paths look like `"rules.txt"` or `"logs/2026/joins.log"`. An absolute path, a drive letter or
  `..` that leaves the folder is a runtime error.
* Files are UTF-8 text; reading is limited to 16 MB per file.
* File operations wait for the disk. For big files, or files written very often, do the work in
  an `async { }` block (see [Timers and tasks](Timers-and-Tasks#background-work-async-and-sync)).

Files are good for logs, exports and text other programs produce or read. For values scripts use
themselves, [saved variables](Saving-Data) and [databases](Databases) are simpler and safer.

## Web requests

`web.get` and `web.post` send HTTP requests in the background. When the answer arrives, your
function is called on the server thread with the status code and the body:

```tys
@playerOnly
command uuid(name: string) {
    web.get("https://api.mojang.com/users/profiles/minecraft/{name}", (status, body) => {
        if status == 200 {
            let profile = json.parseMap(body)
            player.send("<green>{name}'s UUID is {json.asString(profile["id"]) ?? "?"}")
        } else if status == 404 || status == 204 {
            player.send("<red>No account is called {name}.")
        } else {
            player.send("<red>Mojang did not answer (status {status}).")
        }
    })
}
```

| Function | Sends |
|----------|-------|
| `web.get(url, (status, body) => ...)` | a GET request |
| `web.post(url, body, contentType, (status, body) => ...)` | a POST request with a body, e.g. `"application/json"` |

* Only `http://` and `https://` addresses are allowed.
* A request that fails (no connection, a timeout after 30 seconds, an invalid certificate) calls
  the function with status `-1` and the error message as `body`.
* Redirects are followed. Requests carry the header `User-Agent: TachyonScript (...)`.
* The function only runs if the script is still loaded when the answer arrives.

### Sending to a webhook

```tys
const WEBHOOK = "https://discord.com/api/webhooks/000000000000000000/your-token"

event player.join {
    if !player.playedBefore {
        let payload = json.stringify({"content": "New player: {player.name}"})
        web.post(WEBHOOK, payload, "application/json", (status, body) => {
            if status < 200 || status >= 300 {
                log.warn("Webhook failed with status {status}: {body}")
            }
        })
    }
}
```

Build JSON with `json.stringify` rather than by joining strings: it escapes quotes and special
characters in player names and messages correctly.

## JSON

| Function | Result |
|----------|--------|
| `json.parse(text)` | the value: objects become `Map<string, any?>`, arrays `List<any?>`, numbers `long` or `double`, plus text, `bool` and `null`. Invalid JSON is an error |
| `json.parseMap(text)` | a JSON object as a map (an error if the text is not an object) |
| `json.parseList(text)` | a JSON array as a list (an error if the text is not an array) |
| `json.asMap(v)`, `json.asList(v)` | a value as a map / list, or `null` if it is not one |
| `json.asString(v)`, `json.asNumber(v)`, `json.asBool(v)` | a value as text / `double` / `bool`, or `null` |
| `json.stringify(value)` | a value as compact JSON (maps, lists, text, numbers, booleans, null; other values as text) |
| `json.pretty(value)` | the same, indented |

Values from JSON have the type `any?` — anything. The `as...` helpers read them safely, returning
`null` for anything unexpected:

```tys-body
let data = json.parseMap("\{\"name\": \"Arena\", \"size\": 32, \"spawns\": [[0, 64, 0], [10, 64, 10]], \"open\": true}")
let name = json.asString(data["name"]) ?? "unnamed"
let size = json.asNumber(data["size"]) ?? 16.0
let open = json.asBool(data["open"]) ?? false
let spawns = json.asList(data["spawns"]) ?? []
player.send("{name}: size {size}, open {open}, {spawns.size} spawn points")
for spawn in spawns {
    let xyz = json.asList(spawn) ?? []
    if xyz.size == 3 {
        let x = json.asNumber(xyz[0]) ?? 0.0
        let y = json.asNumber(xyz[1]) ?? 0.0
        let z = json.asNumber(xyz[2]) ?? 0.0
        player.send("<gray>- {x}, {y}, {z}")
    }
}
```

`\{` in a string literal writes a literal `{` — in JSON written directly in a script, every `{`
that does not start a `{value}` needs it.

JSON files are a convenient way to configure a script:

```tys
var arenas: Map<string, any?> = {}

on load {
    let text = files.read("arenas.json")
    if text != null {
        arenas = json.parseMap(text)
    }
    log.info("Loaded {arenas.size} arenas")
}

@permission("arena.admin")
command arena.save {
    files.write("arenas.json", json.pretty(arenas))
    sender.send("<green>Saved.")
}
```

## Next

* [Databases](Databases) — structured data
* [Timers and tasks](Timers-and-Tasks) — `async` for slow files
* [Lists, maps and records](Lists-Maps-and-Records)
