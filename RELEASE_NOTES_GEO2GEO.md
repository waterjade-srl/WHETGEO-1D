# geo2geo prototype v0.2

Preview of the GEO2GEO prototype for the Rigon group: a snow and frozen-soil column in WHETGEO-1D, run from an OMS3 `.sim`. It is a preview for testing, not a release: the snow library is to move to a GEOframe repository before that (ARCH-9), and options, inputs and outputs may still change. The design is in the specification site of the project (draft v1.0); the identifiers below (COL-3, VAL-1, …) are those of the site.

## What it holds

- **`SnowSoilSolver1DMain`**, one heat system for snow and soil (COL-1) with a window of active snow cells (COL-3), Richards' equation under the snow, frozen soil with two potentials, ice impedance and cryosuction (SOIL-1 to SOIL-4).
  - *fidelity*: GEOtop 3.0's options; *recommended*: the set proposed in the design.
  - Surface energy balance over snow and bare soil with GEOtop's skin node; albedo, compaction, percolation, sublimation, restratification (mass criterion in fidelity, graded thickness in recommended).
  - Step control with halving and an explicit failure (SOL-5), restart bit for bit (ARCH-5), mass and energy balance of the column at every step (VAL-7), run header with the code version, the configuration and the SHA-256 of the inputs.
  - Outputs: NetCDF and GEOtop-like CSV.
- **`TerrainShortwaveMain`**: shortwave on the terrain of a point (slope, aspect, horizon, sky view factor), upstream of the column.
- **Case GeoPackage** (ARCH-10, proposed), one file per case for the static inputs: points, horizon, soil profiles, named grids of the soil, initial conditions of soil and snow, one row per column. Series stay in GEOframe's OMS tables, keyed by point, one folder per forcing set.
  - `tools/geotop2case` writes a case from GEOtop 3.0 point runs, read as GEOtop reads them.
  - `tools/caseedit` adds named grids (built by GEOframe's `WHETGEO1D_GridCreator`) and initial snow (one layer as GEOtop's `InitSWE`, or layers from a pit).
  - `CaseGeoPackage` checks that a case holds together.
  - A run picks column, grid and initial condition (`caseColumn`, `caseGrid`, `caseInitialCondition`). The geometry is always the true one of the point.
- **Cases** (`cases/snowsoil/`): a synthetic season; Matsch_P2_Ref_007 and ARF_1D from GEOtop's official tests; Friuli (four FVG stations, 2025–26) and the NTGS1/NTGS2 boreholes near Inuvik, whose data are shared separately.
- **Notebooks**: 5 (reading the outputs), 6 (Matsch), 7 (Canada); `friuli_comparison.ipynb`.
- **Tests**: 338 JUnit tests, among them Neumann's and Lunardini's analytic solutions (VAL-6) and constructed cases (VAL-4); golden baseline of the soil solvers; snow regression on one ARF_1D season. CI on Linux, macOS and Windows (VAL-5, proposed).

## What has been checked

| Case | Configuration | Against | Result |
|---|---|---|---|
| Matsch_P2_Ref_007 | fidelity | GEOtop 3.0, VAL-1 tolerances (approved 22/10/2026) | within: soil, SWE and snow depth (RMSE), fluxes with snow; beyond: SWE peak, melt and runoff, from GEOtop's ground albedo of 0 under snow (a GEOtop error, not reproduced) |
| NTGS1, NTGS2 × ERA5, JRA-3Q | fidelity, heat only | GEOtop 3.0 | SWE daily bias 0.3–0.7 mm, snow-on and snow-off within a day, soil bias +0.02 to +0.13 K at 0.1–4 m, maximum thaw 16–26 mm deeper |
| Friuli, 4 stations | recommended, terrain of the point | observed snow depth | RMSE 0.06–0.20 m; melt-out from 2 days late to 20 days early |
| ARF_1D, one season | fidelity | its baseline (snow regression) | bit for bit |

Details, tables and figures: the READMEs of the cases and the notebooks.

## Known limits

- The `fidelity` package (GEOtop forcing files, snow keywords) is a temporary test harness, not part of the model.
- The initial snow has not yet been compared with GEOtop in the fidelity configuration (ARF_1D has 23.78 mm at the start).
- GridCreator's `mixed` grids leave cells much thinner than `dz_min` at the faces of the layers.
- Delayed refreezing of GEOtop (STA-3) is not ported; the compounded shortwave penetration of the recommended configuration is off (it does not converge at hourly steps, SNOW-7).
- Open in the design: SOL-3 (three changes to Numerical, worked around), VAL-5 (who validates the parked WHETGEO-1D tests), ORG-4 (technical meeting with the library maintainer); ARCH-10 and VAL-3 are proposed.
- No vegetation (SCOPE-1), no glacier (SCOPE-2), 1D only.

## What we ask you

1. Run a case of yours: a GEOtop 3.0 point run through `geotop2case` and the column. Does it work, and is anything missing?
2. The operational acceptance criteria (VAL-3) on Friuli and the Canadian boreholes.
3. The case GeoPackage and the record of the runs (ARCH-10), to be agreed with Andrea Antonello.

Comments and problems: the Issues of this fork.
