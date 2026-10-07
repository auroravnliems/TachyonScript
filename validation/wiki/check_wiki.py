"""Checks a wiki checkout against this repository, beyond what :tachyon-cli:test covers.

* every internal link points at an existing page, and every #anchor at a heading of that page;
* every diagnostic code of DiagnosticCode is documented;
* the YAML block under "The whole file" in Configuration.md is the plugin's config.yml.

Usage: python validation/wiki/check_wiki.py <wiki folder> [--json result.json]
Examples and API-Reference.md are checked by: gradlew :tachyon-cli:test -Ptachyon.wiki=<wiki folder>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LINK = re.compile(r"(?<!!)\[([^\]]*)\]\(([^)\s]+)\)")
FENCE = re.compile(r"^(\s*)(```|~~~)")


def prose_lines(text: str):
    """Lines outside fenced code blocks, with their numbers; inline code is blanked."""
    inside = False
    for number, line in enumerate(text.splitlines(), 1):
        if FENCE.match(line):
            inside = not inside
            continue
        if not inside:
            yield number, re.sub(r"`[^`]*`", lambda m: " " * len(m.group(0)), line)


def slug(heading: str) -> str:
    """GitHub's anchor for a heading: lower case, punctuation dropped, spaces to hyphens."""
    text = re.sub(r"`", "", heading.strip().lower())
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def anchors(text: str) -> set[str]:
    found: set[str] = set()
    counts: dict[str, int] = {}
    inside = False
    for line in text.splitlines():
        if FENCE.match(line):
            inside = not inside
            continue
        match = re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
        if inside or not match:
            continue
        base = slug(match.group(1))
        index = counts.get(base, 0)
        counts[base] = index + 1
        found.add(base if index == 0 else f"{base}-{index}")
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wiki", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    pages = {path.stem: path.read_bytes().decode("utf-8") for path in sorted(args.wiki.glob("*.md"))}
    page_anchors = {name: anchors(text) for name, text in pages.items()}
    problems: list[str] = []

    checked = 0
    for name, text in pages.items():
        for number, line in prose_lines(text):
            for _, target in LINK.findall(line):
                if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
                    continue  # external
                checked += 1
                page, _, anchor = target.partition("#")
                page = page or name
                if page not in pages:
                    problems.append(f"{name}.md:{number}: missing page {target}")
                elif anchor and anchor.lower() not in page_anchors[page]:
                    problems.append(f"{name}.md:{number}: missing anchor {target}")

    codes = sorted(set(re.findall(r'"(TYS\d{4})"', (REPO / "tachyon-language/src/main/java/dev/tachyonscript/language/"
                                                       "diagnostic/DiagnosticCode.java").read_text(encoding="utf-8"))))
    everything = "\n".join(pages.values())
    undocumented = [code for code in codes if code not in everything]
    problems += [f"undocumented diagnostic {code}" for code in undocumented]

    config = (REPO / "tachyon-plugin/src/main/resources/config.yml").read_bytes().decode("utf-8").replace("\r\n", "\n")
    configuration = pages["Configuration"].replace("\r\n", "\n")
    block = re.search(r"## The whole file\n.*?```yaml\n(.*?)```", configuration, re.S)
    yaml_matches = bool(block) and block.group(1).strip("\n") == config.strip("\n")
    if not yaml_matches:
        problems.append("Configuration.md: the YAML block differs from tachyon-plugin/src/main/resources/config.yml")

    result = {
        "pages_total": len(pages),
        "internal_links_checked": checked,
        "broken_internal_links": sum(1 for p in problems if "missing page" in p or "missing anchor" in p),
        "diagnostic_ids_total": len(codes),
        "diagnostic_ids_documented": len(codes) - len(undocumented),
        "default_yaml_matches_source": yaml_matches,
        "problems": problems,
        "page_sha256": {f"{name}.md": hashlib.sha256(text.encode("utf-8")).hexdigest() for name, text in pages.items()},
    }
    if args.json:
        args.json.write_text(json.dumps(result, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"pages {result['pages_total']}, internal links {checked}, broken {result['broken_internal_links']}, "
          f"diagnostics {result['diagnostic_ids_documented']}/{len(codes)}, yaml matches config.yml: {yaml_matches}")
    for problem in problems:
        print("  " + problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
