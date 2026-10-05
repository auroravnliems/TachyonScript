# Modules

As a server grows, so do its scripts. Modules let one script use the functions, variables,
records and constants of another, so shared code — an economy, a permission helper, a
formatting library — is written once.

Every script file is a **module**. Its name comes from its path inside the scripts folder:

| File | Module name |
|------|-------------|
| `scripts/economy.tys` | `economy` |
| `scripts/eco/bank.tys` | `eco.bank` |
| `scripts/minigames/spleef/arena.tys` | `minigames.spleef.arena` |

A `module` line at the top of the file chooses the name instead. That keeps the name stable
when the file is moved or renamed — which also matters for its [saved data](Saving-Data),
stored under the module name.

## A module

```tys
// scripts/eco/bank.tys
module bank

const CURRENCY = "coins"

record Account(owner: UUID, balance: long)

persistent var balances: Map<UUID, long> = {}
var transfers = 0

/// The balance of a player (0 for players who never had coins).
function balance(p: OfflinePlayer): long {
    return balances[p.uuid] ?? 0
}

function deposit(p: OfflinePlayer, amount: long) {
    balances[p.uuid] += amount
}

function withdraw(p: OfflinePlayer, amount: long): bool {
    if balance(p) < amount {
        return false
    }
    balances[p.uuid] -= amount
    return true
}
```

A module is an ordinary script: it can also have event handlers, commands and timers of its
own.

## Importing

Another script imports the module and uses its members:

```text
// scripts/shop.tys
import bank

@playerOnly
command buy(material: Material, amount: int = 1) {
    let price = 10L * amount
    if !bank.withdraw(player, price) {
        player.send("<red>You need {price} {bank.CURRENCY}; you have {bank.balance(player)}.")
        return
    }
    bank.transfers += 1
    player.give(material, amount)
}
```

The three forms of `import`:

| Form | Use it as |
|------|-----------|
| `import bank` | `bank.balance(player)`, `bank.CURRENCY`, `bank.balances` |
| `import bank as b` | `b.balance(player)` — a shorter or clearer name in this file |
| `import {balance, deposit, Account} from bank` | `balance(player)`, `Account(...)` — the names directly |

Imports are written at the top of the file, after the `module` line if there is one. They can
be combined: `import bank` and `import {balance} from bank` in the same file are fine.

An importing script sees the module's:

* **functions** — `bank.deposit(player, 5)`;
* **script variables**, including `persistent` ones — `bank.balances`, and it may change them:
  `bank.transfers += 1`;
* **records** — `bank.Account(player.uuid, 0)` or `Account(...)` when imported by name;
* **constants** — `bank.CURRENCY`.

`playerdata var` values of a module are properties of players everywhere once the module is
imported: a module declaring `playerdata var coins: int = 0` lets importers write
`player.coins`.

## Cycles

Modules cannot import each other in a circle (`a` imports `b`, `b` imports `a`), because each
must be checked before the other. The compiler shows the circle:

```text
ERROR scripts/cyc1.tys:1:8 [TYS0237]

  1 | import cyc2
    |        ^^^^

Modules import each other in a cycle: cyc1 -> cyc2 -> cyc1.

Move what both modules need into a third module that imports neither of them.
```

## Reloading modules

TachyonScript keeps modules and the scripts that import them consistent:

* When a **module changes**, every script that imports it is compiled again against the new
  version during the same reload — a script never runs against a different module than the one
  it was checked against. If the new module breaks an importer (a function was renamed), the
  compiler reports it and, in the default lenient mode, the previous working versions stay
  active.
* When **only an importing script changes**, the module is not touched: its variables, timers and
  menus keep running.
* Variables of a module keep their values while the module itself is unchanged, even when its
  importers reload. `persistent` values survive any reload.

## Organising a server with modules

A pattern that scales well:

```text
scripts/
├── lib/
│   ├── format.tys       module format    — prefixes, colors, number formatting
│   └── perms.tys        module perms     — rank checks shared by everything
├── eco/
│   ├── bank.tys         module bank      — balances (persistent), pay/withdraw functions
│   └── shop.tys         imports bank, format — the /shop menu
├── games/
│   └── spleef.tys       imports bank, format — rewards winners
└── -old/                ignored: names starting with '-' are disabled
```

* Put things several scripts need in small library modules (`lib/...`) without event handlers.
* Give shared modules an explicit `module` name so they can be moved freely.
* Keep commands and handlers in the scripts that own the feature.

## Next

* [Saving data](Saving-Data) — why a stable module name keeps saved values
* [Functions and lambdas](Functions-and-Lambdas)
* [Admin commands](Admin-Commands) — `/tys reload` and friends
