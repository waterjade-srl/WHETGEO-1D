#!/bin/bash
# Writes data/arf_1d.gpkg, the case GeoPackage (ARCH-10: point, horizon, soil profile, initial condition, a row
# per column), from GEOtop 3.0's official test ARF_1D with tools/geotop2case, which reads it as GEOtop does.
#   GEOTOP_REPO=<GEOtop 3.0 repository> cases/snowsoil/arf_1d/make_case.sh     default: ~/wj-git/geotop
set -euo pipefail
CASE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$CASE/../../.." && pwd)
GEOTOP_REPO=${GEOTOP_REPO:-$HOME/wj-git/geotop}
python3 "$REPO/tools/geotop2case/geotop2case.py" "$CASE/data/arf_1d.gpkg" "$GEOTOP_REPO/tests/1D/ARF_1D" \
    --epsg 32605 --names "ARF_1D" --case "ARF_1D, GEOtop 3.0 official 1D test: North Slope of Alaska, Anaktuvuk River Fire area"
# a named grid as soil_layers (classical), checked against WHETGEO-1D's reader by CaseGeoPackageTest
python3 "$REPO/tools/caseedit/caseedit.py" grid add "$CASE/data/arf_1d.gpkg" --profile 1 --method classical --note "as soil_layers"
