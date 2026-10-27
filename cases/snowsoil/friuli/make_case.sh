#!/bin/bash
# Writes data/friuli.gpkg, the case GeoPackage (ARCH-10: points, 16-direction horizon, soil profile, initial
# condition, a row per station), with tools/geotop2case from the GEOtop 3.0 runs of the four stations on their
# terrain: slope and aspect of the 250 m map cell, horizons from the 3" DEM (README.md), sky view factor computed
# from the horizon and the slope. The runs are not in the repository (shared separately, README.md).
#   FRIULI_GT3=<folder of the GEOtop 3.0 runs, with topografia_v/> cases/snowsoil/friuli/make_case.sh
set -euo pipefail
CASE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$CASE/../../.." && pwd)
: "${FRIULI_GT3:?set FRIULI_GT3 to the folder of the GEOtop 3.0 runs of the Friuli case}"
RUNS=("$FRIULI_GT3"/topografia_v/{1_Chiansaveit,2_Sauris_di_sopra,3_Sauris_di_sotto,4_Casera_Pieltinis})
python3 "$REPO/tools/geotop2case/geotop2case.py" "$CASE/data/friuli.gpkg" "${RUNS[@]}" \
    --epsg 3035 --site-type stake --names "Chiansaveit,Sauris di sopra,Sauris di sotto,Casera Pieltinis" \
    --case "Friuli: four snow stakes of the FVG network, 01/11/2025 - 01/05/2026, GEOtop 3.0 on the terrain of the stations"
