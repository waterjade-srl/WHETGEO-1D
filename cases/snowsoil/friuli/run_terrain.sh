#!/bin/bash
# Runs the Friuli case as an operational run, the terrain from GEOframe inputs (snowsoil_friuli_terrain.sim),
# from the root of the repository.
#   cases/snowsoil/friuli/run_terrain.sh [geometry ...]     default: cell (the operational run); flat field: checks
#   COLUMNS="1 4" cases/snowsoil/friuli/run_terrain.sh cell       (rows of the columns table of data/friuli.gpkg)
# Writes output/terrain/<geometry>/station_<n>/ (NetCDF, GEOtop-like CSV) and .../run.log.
set -euo pipefail
export LC_NUMERIC=C
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
CASE=$ROOT/cases/snowsoil/friuli
OMS=${OMS_JAR:-$HOME/.m2/repository/edu/colostate/omslab/oms/3.5.12/oms-3.5.12.jar}
cd "$ROOT"
# oms3.CLI leaves an empty workspace, <sim name>/out, in the current folder
trap 'find . -maxdepth 2 -path "./snowsoil_*" -type d -empty -delete' EXIT
# always compile: a stale target/classes would run old code
mvn -q -o compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"
for g in ${*:-cell}; do
  for s in ${COLUMNS:-1 2 3 4}; do
    out="$CASE/output/terrain/$g/station_$s"
    mkdir -p "$out"
    start=$(date +%s.%N)
    java -Dcolumn="$s" -Dgeometry="$g" -Doms.prj="$CASE" -cp "$OMS:$CP" \
         oms3.CLI -r "$CASE/snowsoil_friuli_terrain.sim" > "$out/run.log" 2>&1
    # oms3.CLI exits with 0 even on an error
    if grep -q ">>>> Error" "$out/run.log"; then echo "$g station $s failed, see $out/run.log" >&2; exit 1; fi
    end=$(date +%s.%N)
    printf '%-6s column %s: %.1f s, %s\n' "$g" "$s" "$(echo "$end - $start" | bc)" \
           "$(grep "snowsoil: .* steps," "$out/run.log" || true)"
  done
done
