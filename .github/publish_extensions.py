#!/usr/bin/env python3
"""Publish freshly built extension APKs into the public extensions repo.

Used by the GitHub Actions publish job. For every release APK produced by the
build job it:

  * copies the APK into <extensions-repo>/apk/ (replacing the previous one),
  * bumps the matching entry in index.json / index.min.json (apk, code, version),
  * preserves the entry's source id, name, lang and other fields,
  * commits and pushes the change to the "repo" branch.

Source ids are preserved from the existing index; a package that is not already
listed is skipped with a warning (its id cannot be derived reliably here).

Usage:
    python publish_extensions.py --apk-dir <dir> --extensions-repo <dir>
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

APK_NAME_RE = re.compile(r"^aniyomi-(.+?)-v(\d+\.\d+)-release\.apk$")
PKG_PREFIX = "eu.kanade.tachiyomi.animeextension."


def find_apks(apk_dir: Path):
    apks = {}
    for apk in sorted(apk_dir.rglob("*.apk")):
        m = APK_NAME_RE.match(apk.name)
        if not m:
            print(f"  skip (name not an extension release): {apk.name}")
            continue
        suffix, version = m.group(1), m.group(2)
        code = int(version.split(".")[1])
        apks[suffix] = {"pkg": PKG_PREFIX + suffix, "version": version, "code": code, "file": apk}
    return apks


def load_json(path: Path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, data, minified: bool):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        if minified:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        else:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")


def update_entry(entry, suffix, apk_name, version, code):
    """Mutate an index entry for the given extension build. Returns True if changed."""
    changed = False
    if entry.get("apk") != apk_name:
        entry["apk"] = apk_name
        changed = True
    if entry.get("code") != code:
        entry["code"] = code
        changed = True
    if entry.get("version") != version:
        entry["version"] = version
        changed = True
    return changed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-dir", required=True, help="directory containing the built APKs (recursively)")
    parser.add_argument("--extensions-repo", required=True, help="checked-out clone of arasif10/anime-extensions")
    parser.add_argument("--icons-dir", default=None, help="optional dir with <suffix>.png icons, synced to icon/<pkg>.png")
    parser.add_argument("--dry-run", action="store_true", help="update files locally but do not commit or push")
    args = parser.parse_args()

    apk_dir = Path(args.apk_dir)
    repo = Path(args.extensions_repo)
    if not (repo / "index.min.json").exists():
        sys.exit(f"error: {repo} does not look like the extensions repo (no index.min.json)")

    apks = find_apks(apk_dir)
    if not apks:
        sys.exit("error: no extension release APKs found in " + str(apk_dir))

    print("Built extensions:")
    for suffix, info in apks.items():
        print(f"  {info['pkg']} v{info['version']} (code {info['code']})")

    index_pretty = load_json(repo / "index.json")
    index_min = load_json(repo / "index.min.json")
    by_pkg_pretty = {e.get("pkg"): e for e in index_pretty}
    by_pkg_min = {e.get("pkg"): e for e in index_min}

    changed = False
    for suffix, info in apks.items():
        apk_name = f"aniyomi-{suffix}-v{info['version']}-release.apk"
        entry_pretty = by_pkg_pretty.get(info["pkg"])
        entry_min = by_pkg_min.get(info["pkg"])
        if entry_pretty is None or entry_min is None:
            print(f"  !! {info['pkg']} is not in the index - skipping (add it manually with its source id)")
            continue

        # Update both index files with the same values.
        c1 = update_entry(entry_pretty, suffix, apk_name, info["version"], info["code"])
        c2 = update_entry(entry_min, suffix, apk_name, info["version"], info["code"])
        if c1 or c2:
            changed = True

        # Replace the APK file and drop any older builds of the same package.
        dest = repo / "apk" / apk_name
        dest.parent.mkdir(parents=True, exist_ok=True)
        if not dest.exists() or dest.read_bytes() != info["file"].read_bytes():
            for stale in dest.parent.glob(f"aniyomi-{suffix}-v*.apk"):
                if stale.name != apk_name:
                    stale.unlink()
            shutil.copyfile(info["file"], dest)
            changed = True
        print(f"  -> {apk_name}")

    # Sync per-extension icons to icon/<pkg>.png (AniYomi fetches them from there).
    if args.icons_dir:
        icons_dir = Path(args.icons_dir)
        for suffix, info in apks.items():
            icon_src = icons_dir / f"{suffix}.png"
            if not icon_src.exists():
                print(f"  !! no icon for {suffix} (expected {icon_src.name}) - skipping")
                continue
            icon_dest = repo / "icon" / (info["pkg"] + ".png")
            icon_dest.parent.mkdir(parents=True, exist_ok=True)
            if not icon_dest.exists() or icon_dest.read_bytes() != icon_src.read_bytes():
                shutil.copyfile(icon_src, icon_dest)
                changed = True
            print(f"  -> icon/{icon_dest.name}")

    if not changed:
        print("No changes - index and APKs already up to date.")
        return

    write_json(repo / "index.json", index_pretty, minified=False)
    write_json(repo / "index.min.json", index_min, minified=True)

    if args.dry_run:
        print("Dry run - files updated locally, not committed/pushed.")
        return

    versions = ", ".join(f"{info['pkg'].split('.')[-1]} v{info['version']}" for info in apks.values())
    subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
    subprocess.run(
        [
            "git", "-c", "user.name=github-actions[bot]",
            "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
            "commit", "-m", f"publish extension repo ({versions})",
        ],
        cwd=repo, check=True,
    )
    subprocess.run(["git", "push"], cwd=repo, check=True)
    print("Committed and pushed.")


if __name__ == "__main__":
    main()
