#!/bin/bash
# ARF_1D with GEOET's soil evaporation (snowsoil_arf_1d_geoet.sim), to 1 September 2002, and the same column
# without evaporation over the same period, for the comparison (output_noevap/).
#
# GEOET is not a dependency of this project. The script compiles it from a local checkout (GEOET_SRC, default
# ~/git/geotop_moby/geoframecomponents/GEOET) against this project's classpath, in a temporary folder: GEOET is
# written for GeoTools 28, the hortonmachine of this project brings GeoTools 34, so three imports of
# InputReaderMain are renamed on the way (org.opengis -> org.geotools.api, CrsUtilities into its crs package).
# Nothing else of GEOET changes. hm-hmachine 0.11.2 (for HortonMessageHandler) is taken from the local Maven
# repository.
set -euo pipefail
export LC_NUMERIC=C
CASE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$CASE/../../.." && pwd)
OMS_JAR=${OMS_JAR:-$HOME/.m2/repository/edu/colostate/omslab/oms/3.5.12/oms-3.5.12.jar}
GEOET_SRC=${GEOET_SRC:-$HOME/git/geotop_moby/geoframecomponents/GEOET}
HMACHINE=${HMACHINE:-$HOME/.m2/repository/org/hortonmachine/hm-hmachine/0.11.2/hm-hmachine-0.11.2.jar}

"$CASE/run.sh" forcing > /dev/null
CP="$REPO/target/classes:$(cat "$CASE/.cp.txt")"

BUILD=$(mktemp -d "${TMPDIR:-/tmp}/geoet.XXXXXX")
trap 'rm -rf "$BUILD"' EXIT
cp -r "$GEOET_SRC/src/main/java" "$BUILD/src"
sed -i -e 's/import org\.opengis\.feature\.simple\.SimpleFeature;/import org.geotools.api.feature.simple.SimpleFeature;/' \
       -e 's/import org\.opengis\.referencing\.crs\.CoordinateReferenceSystem;/import org.geotools.api.referencing.crs.CoordinateReferenceSystem;/' \
       -e 's/import org\.hortonmachine\.gears\.utils\.CrsUtilities;/import org.hortonmachine.gears.utils.crs.CrsUtilities;/' \
       "$BUILD/src/it/geoframe/blogspot/geoet/inout/InputReaderMain.java"
mkdir -p "$BUILD/classes"
javac -nowarn -encoding UTF-8 -d "$BUILD/classes" -cp "$CP:$HMACHINE" $(find "$BUILD/src" -name "*.java")
echo "GEOET: $(git -C "$GEOET_SRC" log -1 --format='%h %ad' --date=short 2>/dev/null || echo "$GEOET_SRC")"

run() {
  local sim=$1 log=$2; shift 2
  local start=$(date +%s)
  # oms-3.5.12.jar first: hm-gears carries an incompatible copy of oms3.dsl
  java "$@" -Doms.prj="$CASE" -cp "$OMS_JAR:$BUILD/classes:$CP:$HMACHINE" oms3.CLI -r "$CASE/$sim" > "$CASE/$log" 2>&1
  if grep -q ">>>> Error" "$CASE/$log"; then echo "$sim failed, see $CASE/$log" >&2; exit 1; fi
  echo "$sim: $(( $(date +%s) - start )) s, $(grep "snowsoil: .* steps," "$CASE/$log" || true)"
}
run snowsoil_arf_1d_geoet.sim .geoet.log
run snowsoil_arf_1d.sim .noevap.log -DendDate="2002-09-01 09:00" -Doutput=output_noevap
