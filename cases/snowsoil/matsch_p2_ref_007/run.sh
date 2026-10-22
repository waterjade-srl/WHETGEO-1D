#!/bin/bash
# Fidelity run of the snow+soil column on Matsch_P2_Ref_007 (no canopy).
# Run from anywhere; needs JDK 21, Maven (offline is fine) and oms-3.5.12.jar
# in the local Maven repository.
#   ./run.sh           forcing files (data/) and the run (output/)
#   ./run.sh forcing   only the forcing files
set -euo pipefail
CASE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$CASE/../../.." && pwd)
OMS_JAR=${OMS_JAR:-$HOME/.m2/repository/edu/colostate/omslab/oms/3.5.12/oms-3.5.12.jar}

cd "$REPO"
mvn -q -o compile dependency:build-classpath -Dmdep.outputFile="$CASE/.cp.txt"
CP="target/classes:$(cat "$CASE/.cp.txt")"

java -cp "$CP" org.geoframe.whetgeo1d.core.snowsoil.fidelity.GeotopForcingFiles "$CASE/forcing.properties"
echo "forcing: $(ls "$CASE/data" | wc -l) files in $CASE/data"
[ "${1:-}" = "forcing" ] && exit 0

# oms-3.5.12.jar first: hm-gears carries an incompatible copy of oms3.dsl
start=$(date +%s)
java -Doms.prj="$CASE" -cp "$OMS_JAR:$CP" oms3.CLI -r "$CASE/snowsoil_matsch_p2_ref_007.sim" 2>&1 | tee "$CASE/.run.log"
# oms3.CLI exits with 0 even on an error
if grep -q ">>>> Error" "$CASE/.run.log"; then echo "the run failed, see $CASE/.run.log" >&2; exit 1; fi
echo "run: $(( $(date +%s) - start )) s, outputs in $CASE/output"
