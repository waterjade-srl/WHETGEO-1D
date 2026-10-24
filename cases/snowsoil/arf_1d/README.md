# ARF_1D: snow regression of the porting

One snow season of GEOtop 3.0's official 1D test `ARF_1D`, run by the snow+soil column and compared with the porting's own earlier outputs by `tools/snow_regression.sh` (VAL-4). It is a **numerical regression**: the forcing is a repeated climatological NARR year, not observations, and the run is not compared with GEOtop. It is neither a validation (VAL-3) nor a fidelity reference (VAL-1).

## The case

- **Site:** Anaktuvuk River Fire, Alaska North Slope, 68.93 N, −150.27 E, 760 m, flat, local standard time UTC−9.
- **Period:** 1 September 2001 – 15 June 2002, hourly. The snow lies from 25 September to 30 May. The summer is left out: the column gets no evaporation here, so its bare soil would overheat.
- **Soil:** the 50 layers of `SoilARF0001.txt` to 9.7 m, Van Genuchten with Dall'Amico's freezing, Richards, free drainage at the bottom, zero heat flux. Initial T = −4.8 °C and ψ = 0 everywhere, as `InitSoilTemp` and `InitSoilPressure`.
- **Forcing:** `meteoNARRhourlyDynamicRepeatedWithSpinUp`, turned into OMS CSV by the temporary fidelity tools (`GeotopForcingFiles`):
  - the precipitation split with `ThresTempRain` = 3.66, `ThresTempSnow` = −0.06 and `SnowCorrFactor` = 0.54, the last of the two values the official file gives;
  - the longwave from Dilley and O'Brien, GEOtop's default;
  - the pressure from the elevation.
- **Configuration:** fidelity, with the official file's settings:
  - the column: 10 snow cells, `MoninObukhov = 2` (unstable side only), sensors at 10 and 2 m;
  - the snow keywords: `FreshSnowReflVis/NIR`, `IrriducibleWatSatSnow`, `SoilRoughness`, `ThresSnowSoilRough`, `SoilEmissiv`;
  - the bare-soil energy balance with the soil albedos of the official file, and no evaporation.
- **Not mapped:** the canopy, and GEOtop's lateral and surface water routing.

## Files

- `make_inputs.py`: writes `geotop/` (the meteo of 15/08/2001–05/07/2002, gzipped, and a flat horizon) and `grid/` from `tests/1D/ARF_1D` of the GEOtop 3.0 repository.
- `forcing.properties`: the settings of `GeotopForcingFiles`.
- `run.sh`: writes `data/` and runs `snowsoil_arf_1d.sim` into `output/`.
- `tools/snow_regression.sh check | capture` (repository root): runs the case and compares it with `src/test/resources/golden/snow_arf_1d.sha256`. On a difference it prints the largest hourly differences of SWE, snow depth, surface temperature, snow runoff and soil temperature against `snow_arf_1d.csv.gz`. An intended change of results is re-captured with those differences reported, as for the soil-only golden baseline.

## The season (capture of 24 October 2026)

- **Snow:** SWE peak 124 mm on 19 May 2002, snow depth up to 454 mm, 10 snow cells reached. Melt 174 mm, runoff from the pack 127 mm.
- **Soil:** at 5 cm the monthly mean falls from −3.8 °C in October to −15.5 °C in March.
- **Run:** 50 s, no step rejected.

## What it found

The first run stopped on 30 May 2002, while the pack was melting out: the surface sweeps through the skin did not converge in 20 sweeps, at any step length. The surface balance over snow jumps at 0 °C, because GEOtop's latent heat switches from sublimation below to evaporation above: about −17 against −15 W m⁻² here. The sweeps alternated between −0.003 and +0.003 °C, without a fixed point on either side.

The solver now treats sweeps that alternate across 0 °C without converging as a skin at 0 °C, which goes through the 0 °C rule of the skin:

- **fidelity:** the step is solved again without the skin, as GEOtop;
- **recommended:** the skin is held at 0 °C.

The season then runs without a rejected step, the November 2001 one included.
