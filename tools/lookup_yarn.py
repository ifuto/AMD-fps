#!/usr/bin/env python3
"""Look up a Yarn 1.21.11 class/method/field by name.

Usage:
  python3 tools/lookup_yarn.py <ClassName> [member-substring]
  python3 tools/lookup_yarn.py --class <fully.qualified.Name>

Reads the Enigma-style .mapping files in /tmp/ref/yarn/mappings (clone of
FabricMC/yarn branch 1.21.11). If the clone is missing, it is fetched.
"""
import json, os, re, subprocess, sys

ROOT = "/tmp/ref/yarn"
MAP = os.path.join(ROOT, "mappings")
IDX = "/tmp/yarn-index/members.json"

def ensure():
    if not os.path.isdir(MAP):
        subprocess.run(["git", "clone", "--depth", "1", "-b", "1.21.11",
                        "https://github.com/FabricMC/yarn", ROOT], check=True)
    if not os.path.exists(IDX):
        subprocess.run([sys.executable, "/tmp/tools/build_yarn_index.py"], check=True)

def main():
    ensure()
    mem = json.load(open(IDX))
    args = sys.argv[1:]
    cls = None
    sub = ""
    i = 0
    while i < len(args):
        if args[i] == "--class":
            cls = args[i + 1]; i += 2
        else:
            if cls is None:
                cls = args[i]
            else:
                sub = args[i]
            i += 1
    if cls is None:
        print(__doc__); sys.exit(1)
    # accept simple or fully-qualified
    key = cls if cls.startswith("net/minecraft/") or cls.startswith("com/mojang/") else None
    if key is None:
        # find by simple name suffix
        cands = [k for k in mem if k.split("/")[-1] == cls or k == cls]
        if not cands:
            print(f"class not found: {cls}")
            # try partial
            cands = [k for k in mem if cls.lower() in k.lower()][:20]
            for c in cands: print("  maybe:", c)
            sys.exit(1)
        key = cands[0]
        if len(cands) > 1:
            print(f"multiple matches, using {key}:")
            for c in cands[1:6]: print("   ", c)
    c = mem[key]
    print(f"== {key}")
    for k, v in c["methods"].items():
        if sub.lower() in k.lower():
            print("  m", k, v)
    for k, v in c["fields"].items():
        if sub.lower() in k.lower():
            print("  f", k, v)

if __name__ == "__main__":
    main()
