#!/usr/bin/env python3
"""Publish freshly built extension APKs into the public extensions repo.

Used by the GitHub Actions publish job. For every release APK produced by the
build job it:

  * copies the APK into <extensions-repo>/apk/ (replacing the previous one),
  * bumps the matching entry in index.json / index.min.json (apk, code, version),
  * preserves the entry's source id, name, lang and other fields,
  * commits and pushes the change to the "repo" branch.

Source ids are preserved from the existing index; a package that is not already
listed is added automatically by deriving its entry from the extension source
(build.gradle + <extClass>.kt: id, name, baseUrl, lang, isNsfw).

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


def derive_entry(src_dir: Path, suffix: str, pkg: str, apk_name: str, version: str, code: int):
    """Build a fresh index entry for a new extension by reading its source.

    Reads extName/extClass/isNsfw from build.gradle and id/name/baseUrl/lang
    from the main <extClass>.kt. Returns None when anything is missing.
    """
    parts = pkg.split(".")
    if len(parts) < 2:
        return None
    lang_dir = parts[-2]  # e.g. 'all' from eu.kanade...all.hahomoe
    ext_dir = src_dir / lang_dir / parts[-1]
    gradle_path = ext_dir / "build.gradle"
    if not gradle_path.exists():
        return None
    gradle_text = gradle_path.read_text(encoding="utf-8")

    def gradle_grab(pattern):
        m = re.search(pattern, gradle_text)
        return m.group(1) if m else None

    ext_name = gradle_grab(r"extName\s*=\s*'([^']+)'")
    ext_class = gradle_grab(r"extClass\s*=\s*'(?:\.)?(\w+)'")
    is_nsfw = re.search(r"isNsfw\s*=\s*true", gradle_text) is not None
    if not ext_class:
        return None

    kt_path = ext_dir / "src" / "/".join(parts) / f"{ext_class}.kt"
    if not kt_path.exists():
        return None
    kt_text = kt_path.read_text(encoding="utf-8")

    def kt_grab(pattern):
        m = re.search(pattern, kt_text)
        return m.group(1) if m else None

    sid = kt_grab(r"override\s+val\s+id\s*:\s*Long\s*=\s*(-?\d+)L")
    name = kt_grab(r"override\s+val\s+name\s*=\s*\"([^\"]+)\"")
    base = kt_grab(r"override\s+val\s+baseUrl\s*=\s*\"([^\"]+)\"")
    lang = kt_grab(r"override\s+val\s+lang\s*=\s*\"([^\"]+)\"")
    if sid is None or name is None or base is None or lang is None:
        return None

    return {
        "name": ext_name or name,
        "pkg": pkg,
        "apk": apk_name,
        "lang": lang,
        "code": code,
        "version": version,
        "nsfw": 1 if is_nsfw else 0,
        "hasReadme": 0,
        "hasChangelog": 0,
        "sources": [
            {
                "id": int(sid),
                "lang": lang,
                "name": name,
                "baseUrl": base,
            }
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-dir", required=True, help="directory containing the built APKs (recursively)")
    parser.add_argument("--extensions-repo", required=True, help="checked-out clone of arasif10/anime-extensions")
    parser.add_argument("--icons-dir", default=None, help="optional dir with <suffix>.png icons, synced to icon/<pkg>.png")
    parser.add_argument("--src-dir", default="src", help="extension source root (to derive entries for new extensions)")
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
            # New extension: derive the entry from its source and add it.
            derived = derive_entry(Path(args.src_dir), suffix, info["pkg"], apk_name, info["version"], info["code"])
            if derived is None:
                print(f"  !! {info['pkg']} is not in the index and could not be derived - skipping")
                continue
            entry_pretty = derived
            entry_min = json.loads(json.dumps(derived))
            index_pretty.append(entry_pretty)
            index_min.append(entry_min)
            by_pkg_pretty[info["pkg"]] = entry_pretty
            by_pkg_min[info["pkg"]] = entry_min
            changed = True
            print(f"  + added new index entry for {info['pkg']} (source id {derived['sources'][0]['id']})")

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
