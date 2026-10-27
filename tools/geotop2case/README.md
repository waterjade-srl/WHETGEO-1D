# geotop2case

Writes the case GeoPackage of 1D runs (read by `CaseGeoPackage`, `SnowSoilSolver1DMain -caseFile` and `TerrainShortwaveMain -caseFile`) from GEOtop 3.0 point runs (`PointSim = 1`). Optionally it also writes their meteorological time series as GEOframe OMS tables.

```bash
python3 tools/geotop2case/geotop2case.py case.gpkg RUN [RUN ...] --epsg CODE \
    [--names A,B,...] [--site-type station] [--directions 16] [--case TEXT] [--series data/] [--forcing F1,F2,...] [--svf-of-run]
```

Every point of every run becomes a point and a column of the case, numbered from 1 in the order given. Identical soil profiles and initial conditions are written once. Requires `geopandas`, `pyproj` and `numpy`.

**Several forcing sets at one place** (`--forcing`, one name per run). The same borehole driven by two reanalyses, say, is one point with a column per set:

- a point equal to one already written (coordinates, elevation, slope, aspect, horizon, sensor heights) is not written again;
- the column records its set in `columns.forcing`;
- with `--series`, each set has its own folder, `data/<set>/`, keyed by point as GEOframe's series (`value_<point id>`).

**Fidelity cases** (`--svf-of-run`): `svf` is the sky view factor of the GEOtop run instead of the one computed from the horizon, so that the case describes the site as GEOtop's run saw it (GEOtop takes 1 where a point run has none, whatever its slope).

## What it reads

It reads what GEOtop 3.0 reads, with GEOtop's precedence and defaults:

- **Points:**
  1. the `Point*` keywords;
  2. then the `PointFile`;
  3. then the elevation, slope, aspect and sky view factor maps;
  4. then GEOtop's defaults.
- **Horizon:** `HorizonPointFile` of each point. Without a file the horizon is flat.
- **Soil:** `SoilParFile` over the soil keywords, cell by cell.
- **Initial condition:** the water table at `InitWaterTableDepth`, hydrostatic with GEOtop's cos(slope), or the initial pressure of every layer; then `InitSoilTemp` or the temperature column.
- **Sensor heights:** wind and temperature, from the meteo station keywords.

## What it writes

- **`points`:**
  - `svf` is computed from the horizon, resampled to `--directions` directions, and the slope (Dozier and Frew 1990): this is the one the column uses.
  - `svf_geotop` is the one of the GEOtop run, kept for traceability.
- **Soil:** one control volume per GEOtop layer, in SI units.
- **`columns`** (with `forcing`, NULL without `--forcing`) and **`provenance`**, with the SHA-256 of every file read.
- **`snow_initial`:** GEOtop's `InitSWE`, `InitSnowDensity`, `InitSnowTemp` and `InitSnowAge` (defaults 200 kg m⁻³, −3 °C, 0 d) for the runs with `InitSWE` > 0, by initial condition.
- Named grids and pit profiles of the snow are added afterwards with `tools/caseedit`.
- **Warnings** (on stderr): a meteo column declared in `geotop.inpts` but absent from the file (that series is not written), and, with `--series`, a series the column cannot run without (air temperature, relative humidity, wind). `CaseGeoPackage`'s check covers the static part of the case only.
- **No `.sim` is written**: start from `cases/snowsoil/friuli/snowsoil_friuli_terrain.sim` and change the case file, the series folder, the dates and the readers of the series the case has.
- **Time series (`--series`):** in UTC.
  - Precipitation is the intensity times the interval to the previous record.
  - The files are read from `meteo0001.txt.old` when a GEOtop run left one, because GEOtop rewrites the file it read without some of its columns.

The script docstring gives the full mapping and the units.

## Not supported

The script stops on:

- more than one soil type;
- a bedrock above the bottom of the soil;
- slope, aspect or sky view factor derived from a DEM without their own map;
- a smoothed DEM;
- more than one meteo station;
- meteo dates given as Julian days only.

## Checks

- **Tests:** `python3 -m pytest tools/geotop2case`, on a synthetic run.
- **The 13 GEOtop 3.0 1D tests:**
  - all of them convert;
  - points, soil matrix and initial pressure equal those resolved by an independent Python port of GEOtop's input code, to the last digit (GEOtop parses numbers with its own routine).
- **Friuli** (`friuli/data/friuli.gpkg`, written by `friuli/make_case.sh` from the GEOtop runs of the four stations):
  - the horizon, soil layers, soil parameters and time series are identical to those of the case written by hand before it and of `make_inputs.py`;
  - the coordinates are those of the GEOtop runs (SMET), within 1 m of the station list;
  - the initial condition has GEOtop's cos(slope): on the terrain the runs move by at most 3.7 mm of snow depth and 0.43 K of soil temperature, with the same snow depth RMSE.
- **Matsch, ARF_1D and the Canadian boreholes:**
  - the soil and initial condition equal the hand-written `grid/` inputs of their cases, to the digits printed there;
  - for the Canadian boreholes, Ks is GEOtop's default 1e-4 mm/s, where `grid/` has an arbitrary value (the water balance is off there).
