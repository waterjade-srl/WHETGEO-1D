#!/bin/bash
# ARF_1D snow regression: forcing (data/) and the run (output/). Run from anywhere; needs JDK 21, Maven (offline is
# fine) and oms-3.5.12.jar in the local Maven repository. The check against the stored outputs is
# tools/snow_regression.sh.
#   ./run.sh           forcing files and the run
#   ./run.sh forcing   only the forcing files
set -euo pipefail
export LC_NUMERIC=C
CASE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$CASE/../../.." && pwd)
OMS_JAR=${OMS_JAR:-$HOME/.m2/repository/edu/colostate/omslab/oms/3.5.12/oms-3.5.12.jar}

cd "$REPO"
mvn -q -o compile dependency:build-classpath -Dmdep.outputFile="$CASE/.cp.txt"
CP="target/classes:$(cat "$CASE/.cp.txt")"
# GEOtop's meteo is committed gzipped; the tools read it plain (not versioned)
gunzip -kf "$CASE/geotop/meteo0001.txt.gz"
java -cp "$CP" org.geoframe.whetgeo1d.core.snowsoil.fidelity.GeotopForcingFiles "$CASE/forcing.properties"
[ "${1:-}" = "forcing" ] && exit 0

start=$(date +%s)
# oms-3.5.12.jar first: hm-gears carries an incompatible copy of oms3.dsl
java -Doms.prj="$CASE" -cp "$OMS_JAR:$CP" oms3.CLI -r "$CASE/snowsoil_arf_1d.sim" > "$CASE/.run.log" 2>&1
# oms3.CLI exits with 0 even on an error
if grep -q ">>>> Error" "$CASE/.run.log"; then echo "the run failed, see $CASE/.run.log" >&2; exit 1; fi
echo "run: $(( $(date +%s) - start )) s, $(grep "snowsoil: .* steps," "$CASE/.run.log" || true)"
