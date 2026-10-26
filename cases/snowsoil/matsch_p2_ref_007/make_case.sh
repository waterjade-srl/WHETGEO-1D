#!/bin/bash
# Writes data/matsch_p2_ref_007.gpkg, the case GeoPackage (ARCH-10: point, horizon, soil profile, initial condition, a row
# per column), from GEOtop 3.0's official test Matsch_P2_Ref_007 with tools/geotop2case, which reads it as GEOtop does.
#   GEOTOP_REPO=<GEOtop 3.0 repository> cases/snowsoil/matsch_p2_ref_007/make_case.sh     default: ~/wj-git/geotop
set -euo pipefail
CASE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$CASE/../../.." && pwd)
GEOTOP_REPO=${GEOTOP_REPO:-$HOME/wj-git/geotop}
python3 "$REPO/tools/geotop2case/geotop2case.py" "$CASE/data/matsch_p2_ref_007.gpkg" "$GEOTOP_REPO/tests/1D/Matsch_P2_Ref_007" \
    --epsg 32632 --names "Matsch P2" --case "Matsch_P2_Ref_007, GEOtop 3.0 official 1D test: station P2 at Montecini, Val Mazia (BZ)"
