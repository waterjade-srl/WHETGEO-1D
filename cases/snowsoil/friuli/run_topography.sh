#!/bin/bash
# Runs the Friuli case on the terrain of the stations: forcing of each variant (make_topography.py),
# then the column, from the root of the repository.
#   cases/snowsoil/friuli/run_topography.sh [variant ...]     default: flat horizon slope topography
#   STATIONS="1 4" cases/snowsoil/friuli/run_topography.sh topography
#   SOURCE=<gt_sim1D folder> ...                               default: ~/git/geotop_moby/pysnow/tests/gt_sim1D
# Writes output/topography/<variant>/station_<n>/ (forcing) and .../run/ (NetCDF, GEOtop-like CSV, log).
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
python3 "$CASE/make_topography.py" "$CP" ${SOURCE:+"$SOURCE"}
VARIANTS=${*:-flat horizon shade slope topography}
for v in $VARIANTS; do
  for s in ${STATIONS:-1 2 3 4}; do
    out="$CASE/output/topography/$v/station_$s/run"
    mkdir -p "$out"
    start=$(date +%s.%N)
    java -Dstation="$s" -Dvariant="$v" -Doms.prj="$CASE" -cp "$OMS:$CP" \
         oms3.CLI -r "$CASE/snowsoil_friuli_topography.sim" > "$out/run.log" 2>&1
    # oms3.CLI exits with 0 even on an error
    if grep -q ">>>> Error" "$out/run.log"; then echo "$v station $s failed, see $out/run.log" >&2; exit 1; fi
    end=$(date +%s.%N)
    printf '%-10s station %s: %.1f s, %s\n' "$v" "$s" "$(echo "$end - $start" | bc)" \
           "$(grep "snowsoil: .* steps," "$out/run.log" || true)"
  done
done
