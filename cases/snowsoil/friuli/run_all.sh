#!/bin/bash
# Runs the Friuli case on the given stations (default: all four), from the root of the repository.
#   cases/snowsoil/friuli/run_all.sh [1 2 3 4]
#   STABILITY="Unstable only" cases/snowsoil/friuli/run_all.sh    (sensitivity: GEOtop MoninObukhov = 2)
#   SW=Compounded cases/snowsoil/friuli/run_all.sh                 (sensitivity: compounded shortwave penetration, SNOW-7)
#   K=Sturm_1997 cases/snowsoil/friuli/run_all.sh                  (snow conductivity law, SNOW-2)
#   ALPHA=1e3 cases/snowsoil/friuli/run_all.sh                     (steepness of the snow phase-change curve, STA-4)
# Writes output/station_<n>/ (NetCDF, GEOtop-like CSV) and output/station_<n>.log, and prints the
# wall time of each run and the step rejections the controller logged (SOL-5).
set -euo pipefail
export LC_NUMERIC=C
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
CASE=$ROOT/cases/snowsoil/friuli
OMS=${OMS_JAR:-$HOME/.m2/repository/edu/colostate/omslab/oms/3.5.12/oms-3.5.12.jar}
cd "$ROOT"
# always compile: a stale target/classes would run old code
mvn -q -o compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
mkdir -p "$CASE/output"
STATIONS=${*:-1 2 3 4}
for s in $STATIONS; do
  start=$(date +%s.%N)
  log="$CASE/output/station_$s${STABILITY:+_${STABILITY// /_}}${SW:+_sw_${SW,,}}${K:+_k_${K,,}}${ALPHA:+_alpha_$ALPHA}.log"
  java -Dstation="$s" -Dstability="${STABILITY:-}" -DshortwavePenetration="${SW:-}" -DsnowConductivity="${K:-}" -DalphaSnow="${ALPHA:-}" -Doms.prj="$CASE" -cp "$OMS:target/classes:$(cat target/cp.txt)" \
       oms3.CLI -r "$CASE/snowsoil_friuli.sim" > "$log" 2>&1
  end=$(date +%s.%N)
  printf 'station %s: %.1f s, %s\n' "$s" "$(echo "$end - $start" | bc)" \
         "$(grep "snowsoil: .* steps," "$log" || true)"
done
