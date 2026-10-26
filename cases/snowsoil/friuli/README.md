# Friuli: a snow season from station data

This case simulates **one winter of snow at a weather station**, with the snow+soil column of WHETGEO-1D
(`SnowSoilSolver1DMain`) and nothing else: OMS time-series readers of the hortonmachine, and the column. No
GEOtop run is needed to produce it. It is the example to start from for a station of your own.

## What is simulated

- **Where:** one point on flat ground, at one of four stations of the Friuli Venezia Giulia network. One run
  simulates one station; `-Dstation=1..4` picks it.

  | ID | Station | Latitude | Longitude | Elevation |
  |---|---|---|---|---|
  | 1 | Chiansaveit | 46.4518 N | 12.62653 E | 1710 m |
  | 2 | Sauris di sopra | 46.46722 N | 12.68198 E | 1414 m |
  | 3 | Sauris di sotto | 46.4662 N | 12.71044 E | 1200 m |
  | 4 | Casera Pieltinis | 46.48414 N | 12.72301 E | 1742 m |

- **When:** 1 November 2025 – 1 May 2026, hourly steps (4344 steps). The run starts without snow.
- **What:** the snowpack and the soil under it, as **one column**, heated and cooled through the same heat
  equation. Snow cells are added by snowfall, compacted, melted, wetted by rain and melt water, merged and
  split. The soil has three cells: 0.28, 0.50 and 2.00 m. They are frozen and thawed with WHETGEO-1D's freezing
  curve, and their water content is fixed (no Richards, `soilWaterMode = Fixed`).
- **What is checked:** the simulated snow depth against the snow depth measured at the station.
- **What is not simulated:**
  - **Slope and horizon:** the station fields are flat, and the column has no slope (LINK-4).
  - **Canopy:** out of scope (SCOPE-1).
  - **Evapotranspiration:** no evaporation from bare soil. In winter the soil is mostly under snow.
  - **Soil water flow:** the soil water does not move; the melt water that leaves the pack is reported, not
    routed.

## The data

The stations measure air temperature, relative humidity, wind speed (sensor at 5 m), air pressure and
precipitation, every hour, and the snow depth. **They do not measure radiation.** In its place the case uses
a cloud transmissivity τ_c. It is a value between 0 (overcast) and 1 (clear sky) for every hour, and it comes
with the station data of the MobyGIS run the case was taken from (`TAU_CLD` in its SMET files, estimated in
that preprocessing, not measured).

`make_inputs.py` turns those files into the OMS CSV series of `data/`, one column per station, times in UTC,
each value referring to the hour that ends at its date:

| File | Variable | Unit |
|---|---|---|
| `airTemperature.csv` | air temperature | °C |
| `relativeHumidity.csv` | relative humidity | % |
| `windSpeed.csv` | wind speed | m s⁻¹ |
| `pressure.csv` | air pressure | Pa |
| `precipitation.csv` | total precipitation in the hour | mm |
| `cloudTransmissivity.csv` | cloud transmissivity τ_c | – |
| `observed_snow_depth.csv` | measured snow depth (only for the comparison) | m |

`grid/` is the soil, as WHETGEO-1D reads it:

- `grid_geometry.csv`: the three cells, with depths η in metres, negative downwards;
- `initial_condition.csv`: 1 °C, water table at 1 m;
- `swrc_parameters.csv`: Van Genuchten parameters, porosity, density, conductivity and heat capacity of the
  solids.

## What the column does with them, every hour

1. **Rain or snow.** The precipitation is snow below 0.5 °C, rain above 2 °C, and a linear mix in between
   (GEOtop's ramp, `precipitationPartition = Geotop Linear`).
2. **Radiation.** The sun's position comes from latitude, longitude and the hour. The shortwave reaching the
   ground is GEOtop's clear-sky shortwave times τ_c, split into direct beam and diffuse. The longwave from the
   sky comes from the air temperature, the humidity and τ_c (Konzelmann 1994, as in the GEOtop runs of these
   stations).
3. **Surface energy balance.** Over snow, the absorbed shortwave (snow albedo from the age of the surface
   snow), the longwave, the sensible and the latent heat (Monin–Obukhov, measurements at 5 and 2 m) are
   balanced at a skin node on top of the snow. The skin cannot rise above 0 °C: what is left over melts the
   snow. Without snow, the soil surface exchanges heat with the air through a transfer coefficient,
   h (T_air − T_soil) with h = 10 W m⁻² K⁻¹.
4. **Heat through snow and soil**, solved implicitly in one system. Phase change is included: snow melts and
   refreezes, and the soil freezes and thaws.
5. **The pack.** Compaction, sublimation or deposition, percolation of liquid water with refreezing in cold
   cells, restratification. The top cell is kept at most 2 cm thick, so the surface is resolved. The fresh
   snow of the hour is added on top.
6. **Checks.** If a step does not converge, it is repeated in halves. The mass and energy of the column are
   balanced every step, and the closure is printed at the end.

All of this is the **recommended configuration** (`configuration = recommended`): the choices of the design
for new simulations, with the parameters of the library. Nothing is calibrated on these stations.

## Getting the data

The data of this case are not in the repository: the station series come from the Friuli Venezia Giulia
network through a MobyGIS run, and are shared separately as `friuli_data.tar.gz` (ask the maintainers). From
the root of the repository:

```bash
tar xzf friuli_data.tar.gz                               # writes data/ and grid/ of this case
(cd cases/snowsoil/friuli && sha256sum -c DATA.sha256)   # the files these results were made with
```

The comparison with GEOtop 3.0 and MobyGIS (`compare.py`, the notebook) also needs their runs, outside this
repository: `FRIULI_GT3=<folder of the GEOtop 3.0 runs>` and `FRIULI_MOBYGIS=<its output_tabs>`; without them
those columns are skipped.

## How to run it

From the root of the repository, with JDK 21 and Maven:

```bash
cases/snowsoil/friuli/run_all.sh          # the four stations, about 8 s each
cases/snowsoil/friuli/run_all.sh 2        # only Sauris di sopra
python3 cases/snowsoil/friuli/compare.py  # scores against the observed snow depth
```

`run_all.sh` compiles once and runs `snowsoil_friuli.sim` through `oms3.CLI`. `oms-3.5.12.jar` has to come
first on the classpath, because hm-gears carries an incompatible copy of the OMS DSL; the script does this.
Every setting in `snowsoil_friuli.sim` has a comment.

## What comes out

`output/station_<n>/` contains:

- **`snowsoil.nc`**: everything, in SI units.
- **`point.csv`**: one row per hour.
  - Snow: depth `snow_depth[mm]`, water equivalent `snow_water_equivalent[mm]`, density, mean temperature,
    melt, runoff from the base.
  - Surface: temperature `Tsurface[C]` and the fluxes `SWnet`, `LWnet`, `H` and `LE` in W m⁻².
  - The terms of the mass and energy balance.
- **`soilTz.csv`, `soilTzav.csv`**: soil temperature at the cell centres, at the end of the hour and as its
  mean over the hour.
- **`snowT.csv`, `snowDepth.csv`, `snowIce.csv`, `snowLiq.csv`**: the profiles of the snow cells.

Each CSV file starts with `#` lines: the code version, every setting of the run, and the SHA-256 of every input
file. A run can be traced back to its inputs. `friuli_comparison.ipynb` reads these outputs and draws the
snow depth against the measurement, for each station.

## Results

Daily mean snow depth against the measurement, over the days with a measurement:

| Station | RMSE, porting | RMSE, GEOtop 3.0 flat | Maximum depth, porting / measured | Snow gone, porting / measured |
|---|---|---|---|---|
| Chiansaveit | 0.29 m | 0.40 m | 1.34 / 1.52 m | 19/04 / 02/05 |
| Sauris di sopra | 0.06 m | 0.13 m | 1.02 / 0.96 m | 06/04 / 02/04 |
| Sauris di sotto | 0.04 m | 0.09 m | 0.73 / 0.74 m | 17/03 / 27/03 |
| Casera Pieltinis | 0.11 m | 0.17 m | 1.26 / 1.22 m | 13/04 / 16/04 |

The snow appears on the same days as measured, within 0–8 days. At Chiansaveit the porting and GEOtop lose the
pack two weeks early and stay 0.2–0.5 m low from March. NivoGEO-1D, with the same forcing, keeps it to the
end of the season (RMSE 0.09 m with the terrain, 0.16 m flat), so undercatch at the stake alone does not
explain it: part of the gap is in the energy balance.

## On the terrain of the stations (the default)

`snowsoil_friuli_terrain.sim` with `run_terrain.sh` is the operational run. It uses GEOframe inputs only and
reads no GEOtop file. Each station is on its terrain: slope, aspect, horizon, and a sky view factor V computed
from them.

**Static inputs: one GeoPackage, `data/friuli.gpkg`** (`make_case.py`). The time series stay in `data/*.csv`.

| Layer or table | Content |
|---|---|
| `points` (POINT, EPSG:3035) | id, name, elevation, lat, lon, slope and aspect of the 250 m map cell, `svf` from the horizon and the slope, `svf_map` (the map's, not used), `site_type`, `wind_height`, `temperature_height` |
| `horizon` | point_id, azimuth, height: 16 directions, every 22.5° |
| `soil_layers`, `soil_parameters`, `soil_parameter_types`, `initial_condition` | WHETGEO-1D's soil inputs (the columns of the CSV files of `grid/`), by `profile_id` and `ic_id` |
| `columns` | id, point_id, profile_id, ic_id: one row per 1D run |
| `provenance` | sources, their SHA-256, the script |

- `-Dcolumn=n` picks the row of `columns`. The column (`caseFile`, `caseColumn`) takes from it its station ID,
  site, sensor heights, slope, V and soil.
- `TerrainShortwaveMain` (`caseFile`) takes geometry and horizon of every station from the same file and
  computes the shortwave on the terrain upstream of the column.
- GDAL and QGIS read the file as a standard GeoPackage. GEOframe's `OmsVectorReader` reads the layer of points
  (`friuli.gpkg#points`) but not the attribute tables, which the components read with SQL.

**The horizons, `data/horizon/`,** come from a 3" DEM (about 90 m) covering Friuli: 72 directions, a sample
every 90 m up to 20 km, with the curvature of the Earth and refraction, the observer 2 m above the DEM at the
point. The horizon files of the MobyGIS source are not used: north and south are swapped in them, and they come
from the case's 250 m DEM, which ends 0.4 km north of Casera Pieltinis.

**The map's sky view factor is not used.** The 250 m map gives V = 0.78–0.87 at the stations. The horizons
give 0.94–0.98 (Dozier and Frew 1990, on the slope).

`-Dgeometry=flat` and `-Dgeometry=field` (a flat field under its horizon) are for internal checks only.

Daily snow depth against the stake, operational run (`cell`):

| Station | RMSE | Melt-out, run / measured | Flat column (`flat`) |
|---|---|---|---|
| Chiansaveit (20° NNE) | 0.20 m | 26/04 / 02/05 | 0.29 m |
| Sauris di sopra (0°) | 0.06 m | 04/04 / 02/04 | 0.07 m |
| Sauris di sotto (11° SSW) | 0.10 m | 07/03 / 27/03 | 0.04 m |
| Casera Pieltinis (12° SSE) | 0.20 m | 06/04 / 16/04 | 0.11 m |

**Checks.**
- `flat` from the GeoPackage gives, bit for bit, the data it gave from a shapefile and the CSV soil inputs.
- Given the same pressure, the shortwave is the one that GEOtop 3.0's computation (`run_topography.sh`, below)
  gives for the same geometry.
- 16 directions instead of 72 move V by at most 0.003 and the snow depth RMSE by at most 0.004 m.

## What each terrain term does (internal test)

`run_topography.sh` runs the column with GEOtop 3.0's own terrain radiation (`make_topography.py`, through
`GeotopForcingFiles` of the fidelity package), one term at a time, next to GEOtop 3.0 with the same geometry
(outside this repository, `FRIULI_GT3`). Settings:

- V from the 72-direction point horizon: flat in `horizon`, on the slope in `topography`;
- rain and snow per unit area of the slope;
- `python3 compare.py --terrain` for the table.

| Station | flat | shade (horizon, V = 1) | horizon (with its V) | slope | topography | GEOtop 3.0 flat / topography |
|---|---|---|---|---|---|---|
| Chiansaveit | 0.29 m | 0.28 m | 0.30 m | 0.19 m | 0.20 m | 0.40 / 0.27 m |
| Sauris di sopra | 0.07 m | 0.07 m | 0.06 m | 0.07 m | 0.06 m | 0.13 / 0.13 m |
| Sauris di sotto | 0.04 m | 0.04 m | 0.05 m | 0.09 m | 0.10 m | 0.09 / 0.13 m |
| Casera Pieltinis | 0.11 m | 0.11 m | 0.11 m | 0.19 m | 0.20 m | 0.17 / 0.31 m |

Every term moves the port and GEOtop 3.0 the same way:

- **Slope of the map cell:** melt-out 7–8 days later on the NNE slope, 4–10 days earlier on the southern ones.
- **Horizon:** matters only at Chiansaveit, under a southern horizon of up to 25°.
- **Sky view factor, once consistent with the horizon:** small, ±1–2 days of melt-out.
- **On the mean of the four stations** the full terrain moves GEOtop 3.0 from 0.195 to 0.210 m and the port
  from 0.126 to 0.140 m. That is because the port's flat column was already unbiased at the valley stations.

## To run your own station

1. **Write the forcing** as OMS CSV, like the files in `data/`, one column per station ID, UTC.
   - Required: air temperature, relative humidity and wind.
   - Optional: pressure (otherwise from the elevation).
   - Radiation: global shortwave, or direct and diffuse, or a cloud transmissivity as here. With measured
     shortwave, connect it to `inShortwaveGlobal`; τ_c is then computed from it.
   - Precipitation: total, or rain and snow already split (`inRainfall`, `inSnowfall`).
   - Optional: incoming longwave, if measured.
2. **Describe the soil**: cells, initial temperature and suction, soil parameters (`grid/` as CSV, or in the
   case GeoPackage).
3. **Describe the station** in a case GeoPackage like `data/friuli.gpkg` (see `make_case.py`): point, horizon,
   soil, a row in `columns`. Then copy `snowsoil_friuli_terrain.sim` and change the dates and the rain/snow
   thresholds. Without a GeoPackage, `snowsoil_friuli.sim` takes coordinates, elevation and sensor heights
   (`windHeight`, `temperatureHeight`) as parameters, on flat ground.
4. **Start in autumn, without snow.** The column does not take an initial snowpack yet.

The settings that are not written in the `.sim` take the defaults of the configuration. The user guide,
`cases/snowsoil/README.md`, lists them all.
