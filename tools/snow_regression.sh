#!/usr/bin/env bash
# Snow regression (VAL-4): one snow season of ARF_1D (cases/snowsoil/arf_1d), run by the snow+soil column and
# compared with the porting's own earlier outputs.
#
# The data lines of point.csv, soilTz.csv and soilTzav.csv (the '#' header lines carry the code version) are hashed
# and compared with src/test/resources/golden/snow_arf_1d.sha256. On a difference the hourly SWE, snow depth,
# surface temperature, snow runoff and soil temperature at 0.1, 0.5 and 2 m are compared with the stored copy
# src/test/resources/golden/snow_arf_1d.csv.gz, and the largest differences are printed.
#
# Usage:
#   tools/snow_regression.sh check     run the case and compare with the stored outputs
#   tools/snow_regression.sh capture   run the case and overwrite the stored outputs (an intended change of results:
#                                      report the differences printed by a check first)
#
# Requires what cases/snowsoil/arf_1d/run.sh requires, and python3. Bitwise on the same machine setup, as
# tools/golden_baseline.sh.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
CASE="$REPO/cases/snowsoil/arf_1d"
GOLDEN="$REPO/src/test/resources/golden"
MODE="${1:-check}"
case "$MODE" in
  check|capture) ;;
  *) echo "usage: $0 check|capture" >&2; exit 2 ;;
esac

"$CASE/run.sh" > /dev/null
out="$CASE/output"
hash=$(for f in point.csv soilTz.csv soilTzav.csv; do grep -v '^#' "$out/$f"; done | sha256sum | cut -d' ' -f1)

# capture: write the stored copy; check: print the largest differences from it
compare() {
  python3 "$REPO/tools/snow_regression_compare.py" "$out" "$GOLDEN/snow_arf_1d.csv.gz" "$1"
}

if [ "$MODE" = capture ]; then
  compare capture
  echo "$hash" > "$GOLDEN/snow_arf_1d.sha256"
  echo "captured $hash"
  exit 0
fi
if [ "$hash" = "$(cat "$GOLDEN/snow_arf_1d.sha256")" ]; then
  echo "OK       snow_arf_1d"
else
  echo "DIFF     snow_arf_1d (stored $(cat "$GOLDEN/snow_arf_1d.sha256"), now $hash)"
  compare check
  exit 1
fi
