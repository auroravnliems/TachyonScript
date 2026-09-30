# Modules and imports

Every script file is a module, named after its path (`shop/items.tys` is
`shop.items`); `module <name>` at the top of the file chooses another name. Other
scripts use a module's functions, variables, records and constants by importing it:

```text
// scripts/economy.tys
module economy

persistent var bank: Map<string, int> = {}

function balance(p: Player): int {
    return bank[p.uuid.toString()] ?? 0
}

function pay(p: Player, amount: int) {
    bank[p.uuid.toString()] = balance(p) + amount
    p.send("<green>+{amount} coins")
}
```

```text
// scripts/rewards.tys
import economy                         // use as economy.pay(...)
import {balance} from economy          // or bring names in directly

event player.join {
    economy.pay(player, 10)
    player.send("Balance: {balance(player)}")
}
```

* `import economy as eco` gives the module another name in the file.
* The importing script sees the module's functions, script variables (`eco.bank`),
  records and constants.
* Modules can import other modules; importing each other in a cycle is an error that
  shows the cycle (`a -> b -> a`).
* When a module changes and is reloaded, every script that imports it is compiled
  again against the new version, so they never run against a different API than the
  one they were checked against. A script that only imports a module can be reloaded
  on its own; the module and its variables are kept.
