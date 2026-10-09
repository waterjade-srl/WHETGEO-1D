#!/usr/bin/env bash
# Golden baseline: bitwise regression check of the soil-only GeoPackage tests.
#
# Runs the Test*Gpkg cases, hashes the SQL dump of each output database and
# compares it with the hash stored in src/test/resources/golden/<case>.sha256.
#
# Usage:
#   tools/golden_baseline.sh check   [--from DIR]   compare with the stored hashes
#   tools/golden_baseline.sh capture [--from DIR]   overwrite the stored hashes
#
# Without --from the tests run with a private java.io.tmpdir, so concurrent runs
# (other checkouts, worktrees, CI jobs) cannot mix their outputs. With --from DIR
# no test is run and the outputs already in DIR are hashed.
#
# Requires: mvn, a JDK >= 21 (honours JAVA_HOME), sqlite3. Hashes depend on the
# sqlite3 .dump format, so capture and check on the same machine setup.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
GOLDEN_DIR="$REPO/src/test/resources/golden"

MODE="${1:-check}"
FROM=""
if [ "${2:-}" = "--from" ]; then
  FROM="${3:?--from needs a directory}"
fi
case "$MODE" in
  check|capture) ;;
  *) echo "usage: $0 check|capture [--from DIR]" >&2; exit 2 ;;
esac

declare -A PREFIXES=(
  [vg_coupled]="VanGenuchtenCoupledOutput"
  [vg_dirichlet]="VanGenuchtenDirichletOutput"
  [vg_root_uptake]="VanGenuchtenRootWaterUptakeOutput"
  [heat_insulated_bottom]="HeatDiffusionInsulatedBottomEquilibrationOutput"
  [heat_two_dirichlet]="HeatDiffusionSteadyStateTwoDirichletOutput"
)

if [ -z "$FROM" ]; then
  FROM="$(mktemp -d "${TMPDIR:-/tmp}/whetgeo-golden.XXXXXX")"
  trap 'rm -rf "$FROM"' EXIT
  (cd "$REPO" && mvn -q test -Dtest='org.geoframe.solvers.Test*Gpkg' \
    -Dsurefire.failIfNoSpecifiedTests=false -DargLine="-Djava.io.tmpdir=$FROM")
fi

output_of() {
  local prefix="$1" found=() f
  for f in "$FROM/$prefix"*.gpkg; do
    [ -e "$f" ] && found+=("$f")
  done
  if [ "${#found[@]}" -ne 1 ]; then
    echo "expected exactly one $prefix*.gpkg in $FROM, found ${#found[@]}" >&2
    return 1
  fi
  echo "${found[0]}"
}

hash_db() {
  sqlite3 "$1" .dump | sha256sum | cut -d' ' -f1
}

mkdir -p "$GOLDEN_DIR"
status=0
for key in $(printf '%s\n' "${!PREFIXES[@]}" | sort); do
  if ! db="$(output_of "${PREFIXES[$key]}")"; then status=1; continue; fi
  h="$(hash_db "$db")"
  file="$GOLDEN_DIR/$key.sha256"
  if [ "$MODE" = "capture" ]; then
    echo "$h" > "$file"
    echo "captured $key  $h"
  elif [ ! -f "$file" ]; then
    echo "MISSING  $key" >&2
    status=1
  elif [ "$h" = "$(cat "$file")" ]; then
    echo "OK       $key"
  else
    echo "DIFF     $key  got=$h want=$(cat "$file")" >&2
    status=1
  fi
done
exit $status
