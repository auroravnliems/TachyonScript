"""Inventory a local Skript checkout without importing its parser or runtime.

This is a source inventory, not a claim of feature equivalence. A feature may span
several classes, and referenced Bukkit events may only be used by expressions.
"""
import argparse
import csv
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("skript", type=Path)
parser.add_argument("--output", type=Path, default=ROOT / "docs/skript-audit")
args = parser.parse_args()
source = args.skript.resolve() / "src/main/java"
if not source.is_dir():
    parser.error("Expected a Skript checkout with src/main/java")
features = []
references = {}
digest = hashlib.sha256()
for path in sorted(source.rglob("*.java")):
    relative = path.relative_to(args.skript.resolve()).as_posix()
    data = path.read_bytes()
    digest.update(relative.encode() + b"\0" + data)
    text = data.decode("utf-8")
    name = re.search(r'@Name\("((?:[^"\\]|\\.)*)"\)', text)
    prefix = re.match(r"(Expr|Eff|Cond|Evt|Sec)(?=[A-Z])", path.stem)
    if name or prefix:
        kind = {"Expr": "expression", "Eff": "effect", "Cond": "condition", "Evt": "event", "Sec": "section"}.get(prefix[1] if prefix else "", "other")
        features.append({"class": path.stem, "name": name[1] if name else path.stem,
                         "kind": kind, "source": relative, "review": "unreviewed"})
    for event in re.findall(r"import ((?:org\.bukkit|io\.papermc\.paper|com\.destroystokyo\.paper)\.event\.[\w.]+Event);", text):
        references.setdefault(event, []).append(relative)

specs = "\n".join(path.read_text(encoding="utf-8") for path in sorted((ROOT / "tools/stdlib-gen/spec").glob("*.api")))
bound_events = dict((fqn, name) for name, fqn in re.findall(r"^event ([\w.]+) = ([\w.]+)", specs, re.M))
bound_events.update({"org.bukkit.event.player.PlayerJoinEvent": "player.join", "org.bukkit.event.player.PlayerQuitEvent": "player.quit",
    "org.bukkit.event.entity.PlayerDeathEvent": "player.death", "io.papermc.paper.event.player.AsyncChatEvent": "player.chat",
    "org.bukkit.event.player.PlayerMoveEvent": "player.move", "org.bukkit.event.block.BlockBreakEvent": "block.break",
    "org.bukkit.event.entity.EntityDamageEvent": "entity.damage"})
decisions_file = ROOT / "docs/skript-audit/reviewed.json"
decisions = json.loads(decisions_file.read_text(encoding="utf-8")) if decisions_file.exists() else {}
for feature in features:
    if feature["class"] in decisions:
        feature.update(decisions[feature["class"]])
args.output.mkdir(parents=True, exist_ok=True)
with (args.output / "features.tsv").open("w", encoding="utf-8", newline="") as handle:
    writer = csv.DictWriter(handle, fieldnames=["class", "kind", "name", "review", "tachyon", "source"], delimiter="\t", extrasaction="ignore")
    writer.writeheader()
    writer.writerows(features)
with (args.output / "event-references.tsv").open("w", encoding="utf-8", newline="") as handle:
    writer = csv.writer(handle, delimiter="\t")
    writer.writerow(["bukkit_event", "tachyon_event", "skript_sources"])
    for event, paths in sorted(references.items()):
        writer.writerow([event, bound_events.get(event, "unreviewed gap"), "; ".join(paths)])
summary = {"source": str(args.skript.resolve()), "java_source_sha256": digest.hexdigest(),
    "classes": len(features), "by_kind": dict(Counter(row["kind"] for row in features)),
    "by_review": dict(Counter(row["review"] for row in features)),
    "event_references": len(references), "referenced_events_with_binding": sum(e in bound_events for e in references),
    "note": "Counts are source classes/references, not distinct features or proof of semantic parity."}
(args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
