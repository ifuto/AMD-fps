#!/usr/bin/env bash
# Compiles every GLSL shader under src/main/resources/assets/amdfaster/shaders
# into SPIR-V under src/main/resources/assets/amdfaster/spirv.
#
# The compiled .spv files ARE committed to the repository so that CI and normal
# users never need a shader toolchain installed. GLSL sources stay in the repo
# as the human-readable truth (and as input for Radeon GPU Analyzer).
#
# Requires glslangValidator on PATH (any recent version; SPIR-V 1.5 / Vulkan 1.2
# target is enough for everything AMD-Faster ships).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/src/main/resources/assets/amdfaster/shaders"
OUT="$ROOT/src/main/resources/assets/amdfaster/spirv"
mkdir -p "$OUT"

for f in "$SRC"/*.vert "$SRC"/*.frag "$SRC"/*.comp; do
  [ -e "$f" ] || continue
  base="$(basename "$f")"
  echo "  $base -> $base.spv"
  glslangValidator -V --target-env vulkan1.2 -o "$OUT/$base.spv" "$f"
done

echo "SPIR-V written to $OUT"
ls -la "$OUT"
