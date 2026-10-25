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

The snow appears on the same days as measured, within 0–8 days. At Chiansaveit every model, GEOtop included,
loses the pack two weeks early and stays 0.2–0.5 m low from March. That points at the precipitation at the
highest and windiest station (undercatch, or snow drifting onto the stake) more than at the snow physics.

## To run your own station

1. **Write the forcing** as OMS CSV, like the files in `data/`, one column per station ID, UTC.
   - Required: air temperature, relative humidity and wind.
   - Optional: pressure (otherwise from the elevation).
   - Radiation: global shortwave, or direct and diffuse, or a cloud transmissivity as here. With measured
     shortwave, connect it to `inShortwaveGlobal`; τ_c is then computed from it.
   - Precipitation: total, or rain and snow already split (`inRainfall`, `inSnowfall`).
   - Optional: incoming longwave, if measured.
2. **Describe the soil** in `grid/`: cells, initial temperature and suction, soil parameters.
3. **Copy `snowsoil_friuli.sim`** and change the station coordinates, the elevation, the sensor heights
   (`windHeight`, `temperatureHeight`), the dates and the rain/snow thresholds.
4. **Start in autumn, without snow.** The column does not take an initial snowpack yet.

The settings that are not written in the `.sim` take the defaults of the configuration. The user guide,
`cases/snowsoil/README.md`, lists them all.
