#!/bin/bash
# Canadian case (NTGS1, NTGS2 x ERA5, JRA-3Q), fidelity, heat only. Run from anywhere; needs JDK 21,
# Maven (offline is fine) and oms-3.5.12.jar in the local Maven repository (OMS_JAR overrides it).
#   ./run.sh                      forcing (data/<case>/) and run (output/<case>/) of the four cases
#   ./run.sh NTGS1_ERA5 ...       only these cases
#   FORCING_ONLY=1 ./run.sh       only the forcing
# Prints the wall time of each run and the step rejections the controller logged (SOL-5).
set -euo pipefail
export LC_NUMERIC=C
CASE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$CASE/../../.." && pwd)
OMS_JAR=${OMS_JAR:-$HOME/.m2/repository/edu/colostate/omslab/oms/3.5.12/oms-3.5.12.jar}
CASES=${*:-NTGS1_ERA5 NTGS1_JR3QG NTGS2_ERA5 NTGS2_JR3QG}

cd "$REPO"
mvn -q -o compile dependency:build-classpath -Dmdep.outputFile="$CASE/.cp.txt"
CP="target/classes:$(cat "$CASE/.cp.txt")"
mkdir -p "$CASE/output"

for c in $CASES; do
  g="$CASE/geotop/$c"
  # GEOtop's inputs are committed gzipped; the tools read them plain (not versioned)
  gunzip -kf "$g/meteo0001.txt.gz" "$g/reference.csv.gz"
  java -cp "$CP" org.geoframe.whetgeo1d.core.snowsoil.fidelity.GeotopForcingFiles "$g/forcing.properties"
  [ -n "${FORCING_ONLY:-}" ] && continue
  log="$CASE/output/$c.log"
  start=$(date +%s.%N)
  # oms-3.5.12.jar first: hm-gears carries an incompatible copy of oms3.dsl
  java -Dcase="$c" -Doms.prj="$CASE" -cp "$OMS_JAR:$CP" oms3.CLI -r "$CASE/snowsoil_canada_ntgs.sim" > "$log" 2>&1
  end=$(date +%s.%N)
  # oms3.CLI exits with 0 even on an error
  if grep -q ">>>> Error" "$log"; then echo "$c: the run failed, see $log" >&2; exit 1; fi
  printf '%s: %.1f s, %s\n' "$c" "$(echo "$end - $start" | bc)" "$(grep "snowsoil: .* steps," "$log" || true)"
done
