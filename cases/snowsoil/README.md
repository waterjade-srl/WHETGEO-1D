# Snow and soil column: user guide

`org.geoframe.whetgeo1d.solvers.SnowSoilSolver1DMain` is the OMS3 component of the GEO2GEO prototype. It models a snowpack on WHETGEO-1D's soil:

- the heat of snow and soil is solved as one system;
- Richards' equation runs under the snow, with frozen soil;
- the snow physics is GEOtop 3.0's in the *fidelity* configuration;
- the *recommended* configuration is the set proposed in the design.

Each `@Execute` advances the column by one step of the forcing.

## Build

The build needs JDK 21 and Maven, and works offline:

```bash
mvn -o -q compile                                   # or: mvn -o test
mvn -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt
```

In a git checkout the build writes `git describe --always --dirty --tags` to `target/classes/whetgeo1d-git-version.txt`. This is done by the pom profile `git-version` and becomes the code version in the header of every output.

## Run the example

`synthetic_season/` contains the following:

| File | Content |
|---|---|
| `snowsoil_synthetic_season.sim` | the OMS3 simulation |
| `grid/` | the soil, as WHETGEO-1D CSV inputs: 2 m of sandy loam, 20 cells |
| `data/` | hourly OMS CSV forcing: Ta, RH, wind, global SW, total precipitation |
| `make_inputs.py` | regenerates `data/` |

The run starts on 15 November 2025 and lasts 120 days, at a flat point at 1500 m.

Run it from the repository root. `oms-3.5.12.jar` must come **first** on the classpath, because hm-gears carries an incompatible copy of `oms3.dsl`:

```bash
java -Doms.prj=$PWD/cases/snowsoil/synthetic_season \
     -cp ~/.m2/repository/edu/colostate/omslab/oms/3.5.12/oms-3.5.12.jar:target/classes:$(cat cp.txt) \
     oms3.CLI -r cases/snowsoil/synthetic_season/snowsoil_synthetic_season.sim
```

`oms3.CLI` exits with 0 even on an error, so check the log for `>>>> Error`. In `parameter{}`, put negative numbers between parentheses: `"snowsoil.snowThreshold" (-1.0)`.

## Options of SnowSoilSolver1DMain

Names are the component's fields: set them in `parameter{}` as `"snowsoil.<name>"`, and connect the `in...` series in `connect{}`. Enum options are case-insensitive, and spaces and underscores are ignored.

### Configuration

| Option | Default | Meaning |
|---|---|---|
| `configuration` | `recommended` | `fidelity` (GEOtop 3.0 options) or `recommended` |
| `snowThermalConductivity` | by configuration | `Cosenza` (fidelity), `Jordan 1991` (recommended), `Sturm 1997`, ... |
| `stabilityCorrection` | by configuration | `Both`, `Unstable only` (GEOtop `MoninObukhov = 2`), `Stable only`, `Neutral` |
| `maxSnowCells` | 10 / 11 | capacity of snow cells above the soil |
| `alphaSnow` | 1e5 | smoothing of the snow equation of state |
| `skinNode` | by configuration | skin node of the surface balance over snow and bare soil (SURF-1/2), eliminated in the solver: `Geotop` (fidelity: as GEOtop, the step solved again without the skin when it rises above 0 °C over snow), `Capped` (recommended: the skin held at 0 °C over snow, the surplus melts the top cell), `None` (the surface is the top cell; sensitivity) |
| `shortwavePenetration` | by configuration | shortwave absorbed below the snow surface (SNOW-7), with more than one snow cell: `Geotop` (fidelity: k = ρ/3 + 50 m⁻¹ of each cell on the cumulative depth, the rest to the top soil cell, none at the surface), `Compounded` (transmission compounded cell by cell, every cell absorbs a non-negative amount; agreed for the recommended configuration but off for now: under the 2 cm surface cell it does not converge in the time step at hourly steps), `None` (all at the surface; the recommended default for now) |
| `windHeight`, `temperatureHeight` | 2, 2 m | measurement heights |
| `groundAlbedo` | 0.2 | albedo of the soil under thin snow |
| `heatTolerance`, `heatMaxIterations`, `heatPicardIterations` | 1e-3 J m⁻², 50, 1 | Newton settings of the heat step |
| `soilWaterMode` | `Richards` | `Richards`, or `Fixed`: suction fixed at its initial value, heat only (GEOtop `WaterBalance = 0`); the water leaving the pack is then only reported |

### Site

`latitude` and `longitude` [deg], `standardTime` (offset of local standard time from UTC [h], used only for the sun), `elevation` [m].

### Soil grid

- `soilGridFile` takes one of:
  - a WHETGEO-1D GeoPackage;
  - a NetCDF grid (`ReadNetCDFHeatDiffusionGrid1D`);
  - a folder of WHETGEO-1D CSV inputs: `grid_geometry.csv`, `initial_condition.csv`, `swrc_parameters.csv`, `swrc_parameter_types.csv`.
- Alternatively, connect the grid arrays as for the other WHETGEO-1D solvers (`z`, `spaceDeltaZ`, `controlVolume`, `psiIC`, `temperature`, ids, parameter arrays).
- Positions are in η: zero at the soil surface, negative downwards.
- Zero-thickness (pond) cells are not accepted.
- Soil closures:
  - heat: `typeClosureEquation`, `typeEquationState` and `typeThermalConductivity`, by default the freezing soil (`Van Genuchten Dall Amico`, `FreezingSoilInternalEnergy`, `Cosenza`), and `interfaceThermalConductivityModel`;
  - water: `typeWaterClosureEquation`, `typeWaterEquationState`, `typeUHCModel`, `typeUHCTemperatureModel` and `interfaceHydraulicConductivityModel` (empty by default: `GEOtop` in fidelity, the upwind cell capped by the saturated conductivities with ice; `max` in recommended);
  - Richards settings: `waterNewtonTolerance`, `waterMaxIterations`, `waterMaxSubstep`;
  - `iceImpedance` (7, as GEOtop);
  - `thermalConductivityWater` (0.6; GEOtop uses 0.567).

### Forcing

Each forcing is an OMS time series (`HashMap<Integer,double[]>`, keyed by `stationID`). The value applies to the step that **ends** at `inCurrentDate` (UTC). `-9999` means missing. `tTimeStep` is the step length [s].

| Input | Unit | If missing |
|---|---|---|
| `inAirTemperature` | °C | required |
| `inRelativeHumidity` | % | required |
| `inWindSpeed` | m s⁻¹ | required |
| `inPressure` | Pa | GEOtop's standard atmosphere at `elevation`, P = 101325 e^(−z/8500) |
| `inShortwaveDirect` + `inShortwaveDiffuse` | W m⁻² | `inShortwaveGlobal`, split into beam and diffuse as GEOtop (Erbs, from the cloud transmissivity) |
| `inLongwaveIn` | W m⁻² | GEOtop's estimate (see below) |
| `inCloudTransmissivity` | – | used by the longwave estimate if given; with no shortwave at all, the shortwave is GEOtop's clear sky times τ_c (as GEOtop with a `CloudTrans` column and no radiation) |
| `inRainfall` + `inSnowfall` | mm per step | `inPrecipitation` split by `precipitationPartition` |
| `inPrecipitationTemperature` | °C | wet-bulb temperature (recommended only; fidelity ignores it) |

How the longwave is estimated when it is missing:

- ε_a = ε_clear τ_c + 1 − τ_c.
- ε_clear comes from `clearSkyEmissivity`; the default `Dilley OBrien 1998` is GEOtop's default. GEOtop's `LWinParameterization` 1–9 are all available; `Konzelmann 1994` (7) uses GEOtop's default coefficients k1 = 0.484, k2 = 8.
- τ_c is the measured shortwave over Iqbal's clear sky, averaged over the daylight steps of the last 24 h and held through the night.
- This average is causal, so it differs from GEOtop's series. GEOtop's exact series is `DailyCloudiness.geotopSeries`; precompute it and pass it as `inCloudTransmissivity` if you need it.
- Before the first daylight step, τ_c = `defaultCloudTransmissivity`.

Precipitation split (`precipitationPartition`):

- `Geotop Linear`, with `rainThreshold` 3 °C and `snowThreshold` −1 °C;
- `Kavetski`, as RainSnowSep, with `kavetskiMeltingTemperature`, `kavetskiSmoothing`, `rainCorrection` and `snowCorrection`.

Fidelity path: feed GEOtop's hourly point output to the component:

- rain and snow, already split;
- Ta, RH, wind, pressure;
- SW beam and diffuse, LW in;
- `Tsurface` as a Dirichlet bare-soil top.

### Boundary conditions (COL-6)

Every value is either a constant (`...Value`) or a time series (`in...`). Where both are given, the series wins, and a missing value in the series falls back to the constant.

| Where | Option | Choices | Value |
|---|---|---|---|
| heat, top of the snow | `snowTopType` | `Surface Energy Balance` (default), `Dirichlet`, `Neumann` | `snowTopValue` / `inSnowTopBC`: °C (capped at 0 °C over snow) or W m⁻² into the snow |
| heat, top of bare soil | `bareSoilTopType` | `Bulk Transfer` (default, h (Ta − T_top) with h = `bareSoilTransfer`), `Dirichlet`, `Neumann`, `Energy Balance` (GEOtop 3.0's bare-soil balance through the skin node of `skinNode`: `groundAlbedo`/`groundAlbedoNearInfrared` dry, `groundAlbedoWet`/`groundAlbedoNearInfraredWet` saturated, `soilEmissivity` 0.96, `soilRoughness` 0.01 m; latent heat of the evaporation given in `inEvaporation`) | `bareSoilTopValue` / `inTopBC`: °C or W m⁻² into the soil |
| heat, bottom | `bottomBCType` | `Bottom No Gradient` (default), `Bottom Dirichlet`, `Bottom Neumann` | `bottomBCValue` / `inBottomBC`: °C or W m⁻² into the column |
| water, bottom | `soilWaterBottomType` | `Bottom Free Drainage` (default), `Bottom Impervious`, `Bottom Dirichlet`, `Bottom Neumann` | `soilWaterBottomValue` / `inSoilWaterBottomBC`: ψ [m] for Dirichlet, inflow [m s⁻¹] for Neumann |

The top of Richards is the water leaving the pack, or the rain on bare soil. It switches to saturation (Dirichlet) when the soil cannot take the water.

### Step control, restart, resume (SOL-5, ARCH-5)

| Option | Default | Meaning |
|---|---|---|
| `maxHalvings` | 4 | a step that is rejected is rolled back and covered by halved sub-steps, up to this many times |
| `onNonConvergence` | `Stop` | `Stop`, or `Flag` (accept the finest sub-step, marked degraded in the diagnostics) |
| `restartPath`, `restartInterval` [s] | none | write `snowsoil_restart_<t>.bin` and `snowsoil_restart_<t>.forcing` every interval (t = seconds since 1970, UTC) |
| `resumeFrom` | none | the `.bin` to carry on from; the `.forcing` file must sit beside it, and the configuration must be the same |

With `Stop`, a step that fails at the finest sub-step stops the run:

- the state of the last accepted step is written as the same `.bin` + `.forcing` pair to `restartPath`, or to `outputPath` if `restartPath` is not set;
- the rejections of the run are written to `snowsoil_rejections_<t>.txt`;
- the writers are closed, so the outputs up to that step are complete;
- the error message says where the state is.

A run resumed from that state continues exactly as the continuous run would have.

To restart a run, start the readers one step after `t`, and set `resumeFrom` to the `.bin` file.

## Outputs

With `outputPath` set, the component writes to that folder:

- `snowsoil.nc`, in NetCDF. It holds:
  - the snow cells in η, with a variable number of cells: thickness, density, temperature, ice, liquid water, internal energy;
  - the soil profiles: T, ψ, θ, θ_ice, water fluxes;
  - the point quantities written to `point.csv`.
- GEOtop-like CSV files:
  - `point.csv`, with the same quantities as GEOtop's `point` output;
  - soil profiles: `soilTz.csv` (temperature at the end of the record), `soilTzav.csv` (mean over the record, as GEOtop's `SoilAveragedTempProfileFile`), `psiz.csv`, `thetaliq.csv`, `thetaice.csv`;
  - snow layers: `snowT.csv`, `snowDepth.csv`, `snowLiq.csv`, `snowIce.csv`.

`outputInterval` [s] aggregates the records as GEOtop's `DtPlotPoint` does; 0 writes every step.

Every output starts with the run header:

- the code version (`codeVersion` overrides it);
- the configuration;
- the SHA-256 of the soil grid files and of the files listed in `inputFiles` (comma-separated);
- the soil grid.

To read the outputs, open `Jupyter_Notebook/5_WHETGEO1D_SnowSoil_Outputs.ipynb`. Its helpers are in `Jupyter_Notebook/WHETGEO1D_SnowSoilOutputs.py`: `read_netcdf`, `read_header`, `read_csv`, `plot_snow_cells`, `plot_soil_profile`.

For chaining with other components, the main results are also `@Out` fields: `outSnowWaterEquivalent`, `outSnowDepth`, `outSnowCells`, `outSurfaceTemperature`, `outSoilTemperature`, `outSoilSuction`, `outSoilIce`, `outSoilInput`, `outRainfall`, `outSnowfall`, `outPressure`, `outLongwaveIn`, `outCloudTransmissivity`, `outStepResult`, `outRunHeader` and `outRunDiagnostics`.

The component prints every rejected step (`snowsoil: heat not accepted at t = ...`) and, at the end, a summary line: steps, accepted solver steps, rejected, degraded.

## Cases

- `synthetic_season/`: the synthetic season of the example above.
- `friuli/`: four stations of the Friuli Venezia Giulia network, 1 November 2025 – 1 May 2026, recommended configuration, heat only, against the observed snow depth (VAL-3). `make_inputs.py` writes the forcing from the SMET files of the MobyGIS-GEOtop run, `run_all.sh` runs the four stations (`-Dstation=n` in the `.sim`), `friuli_comparison.ipynb` compares with the observations, GEOtop 3.0 and MobyGIS (`compare.py`).
