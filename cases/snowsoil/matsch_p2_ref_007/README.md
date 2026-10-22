# Matsch_P2_Ref_007: fidelity run against GEOtop 3.0

This case is the snow+soil column in the *fidelity* configuration on GEOtop 3.0's official 1D test `Matsch_P2_Ref_007`, run **without canopy and corrected**. That variant is the strict reference of phase 2.1.

The reference differs from the official case in four ways:

- `CanopyFraction = 0`, because the canopy is out of the port's scope (SCOPE-1);
- the meteo file's `JDfrom0` column and `HeaderJulianDayfrom0Meteo` are dropped, so the records are dated by `Date`. In the official file that column is one day behind the dates, and GEOtop trusts it over the dates (`fixing_dates`, `meteodata.cc:583`). This needs the inline `!` comments stripped from `geotop.inpts`, because GEOtop's parser keeps them: `MeteoStationStandardTime = 1 ! added by ... 2.1` reads as 1e21, which only `fixing_dates` uses (`readline_par`, `find_number`, `src/libraries/ascii/tabs.cc:159-189, 277-345`);
- `ActualOrProjectedArea = 1` is set: without it GEOtop projects the precipitation a second time in a point simulation, `P cos²β` (`energy.balance.cc:299-304`);
- the empty `CloudTrans` column (-9999) is dropped: with it GEOtop never computes the daily cloudiness from the measured shortwave (`input.cc:405-408`), and falls back to Kimball from T and RH.

The corrected reference therefore no longer equals the official test output.

The case:

- **Site:** station P2 in Val Mazia, 46.668 N, 10.579 E, 1549 m, slope 21.4°, aspect 221.6°.
- **Period:** 02/10/2009 00:00 to 02/11/2009 23:00, local standard time (UTC+1).
- **Steps:** 900 s, with hourly outputs.

## Run

```bash
cases/snowsoil/matsch_p2_ref_007/run.sh          # forcing (data/) and run (output/), about 10 s
cases/snowsoil/matsch_p2_ref_007/run.sh forcing  # only the forcing
```

Then open the comparison notebook, `Jupyter_Notebook/6_Matsch_P2_Ref_007_Fidelity.ipynb`.

The run needs JDK 21, Maven (offline works) and `oms-3.5.12.jar` in the local Maven repository (`OMS_JAR` overrides it).

## Files

| Path | Content |
|---|---|
| `geotop/` | the corrected GEOtop 3.0 run without canopy, produced by `casi/Matsch_P2_Ref_007/run.sh corretta` of the GEO2GEO workspace (binary sha256 `6a65968f…09c`, GEOtop commit 1f1fc5af). It holds: `geotop.inpts`; `meteo0001.txt` (the corrected input, Date only, October 2009 only); `meteo0001_cloud_filled_by_geotop.txt` (the same file with the cloudiness GEOtop computed and wrote back into it); `clouds.txt`; `horizon0001.txt`; `soil0001.txt`; and the outputs in `output-tabs/` (snow layer files cut to L1–L5). `point0001.txt` sha256: `5fae8eba…` |
| `forcing.properties` | the case's settings for the forcing tools |
| `data/` | OMS CSV forcing at 900 s, written by `run.sh` (`GeotopForcingFiles`) |
| `grid/` | the soil as WHETGEO-1D CSV inputs |
| `snowsoil_matsch_p2_ref_007.sim` | the OMS3 simulation |
| `output/` | the outputs (not versioned): `snowsoil.nc`, and the GEOtop-like CSV files with dates in UTC+1 |

## Forcing

The forcing is GEOtop's own forcing, computed from GEOtop's input files by the temporary tools of `org.geoframe.whetgeo1d.core.snowsoil.fidelity`. `GeotopForcingTest` checks it hour by hour against GEOtop's point output:

| Quantity | How GEOtop computes it | Max difference |
|---|---|---|
| Ta, RH, wind | step means of the meteo file, dated by `Date`; a step that starts at a record also checks it for no value, as GEOtop does | 2e-15 |
| pressure | 1013.25 e^(−z/8500) hPa | 2e-7 hPa |
| rain, snow | linear ramp −3.2…3 °C, `SnowCorrFactor` 1.398 applied at the station, split again at the point, times cos β (the flux per unit area of a column normal to the slope) | 5e-7 mm |
| SW beam and diffuse | on the slope: Iqbal clear sky, Erbs diffuse fraction, τ_c from the measured global SW. The point horizon is flat; the station horizon is used only for the cloudiness | 6e-7 W m⁻² |
| LW in | Idso + Hodges (`LWinParameterization = 4`) with GEOtop's daily cloudiness from the measured SW (`DailyCloudiness.geotopSeries`; the series matches the one GEOtop writes back to 5e-7), and Kimball/Walcek where the series is undefined, as GEOtop | 5e-7 W m⁻² |

Two quantities are fallbacks, taken from GEOtop's own output (DN-14):

- **`Tsurface`**: a Dirichlet top without snow, because the column has no bare-soil energy balance.
- **`Evap_surface`**: a sink of the top cell without snow, because evapotranspiration comes from GEOET.

Each hourly value of GEOtop applies to the four steps of its hour.

## Configuration

The run uses the fidelity configuration, with these settings:

- Cosenza snow conductivity;
- Monin–Obukhov with the stability correction in both conditions (`MoninObukhov = 1`);
- sensors at 2.5 m (wind) and 2 m (temperature);
- α = 1e5;
- the case's snow keywords, through `geotopSnowParameters`;
- ground albedo 0.15 / 0.25;
- water conductivity 0.567.

The soil and its conditions:

- **Soil:** 16 layers to 1.0 m, with Van Genuchten α = 1.31 m⁻¹, n = 1.5, θr = 0.05, θs = 0.53, Ks = 2.86e-6 m s⁻¹, specific storage 5e-4 m⁻¹. Freezing follows Dall'Amico. The solids have λ = 1.9 W m⁻¹ K⁻¹ and ρc = 2.16 MJ m⁻³ K⁻¹.
- **Initial state:** ψ from the first row of GEOtop's `psiz`, and T = 4 °C.
- **Bottom:** zero heat flux (`Zboundary` = 1e20, `Fboundary` = 0) and impervious for water. `FreeDrainageAtBottom = 0` leaves `Kbottom` at zero (`water.balance.cc:976`).

### Not mapped

- **Thin snow:** soil roughness below `ThresSnowSoilRough` (100 mm) and soil emissivity below 10 mm of snow (DN-14 open point).
- **Slope in Richards:** the slope in the gravity term (cos 21.4°); the column is vertical.
- **Surface temperature:** without snow, GEOtop's top-layer temperature is imposed at the surface face of the port's top cell.
- **Shortwave penetration** into the snow (SNOW-7).
- **Delayed refreezing** (STA-3).
- **Snow capacity:** `maxSnowCells` = 10 cells of heat capacity, while the case's mass criterion would allow 255. The pack never has more than one cell here.

## Result

The run takes 3068 steps, every heat solve converges, and it runs in about 8 s. The full comparison against the VAL-1 tolerances, approved on 22 October 2026, is in the notebook. In short:

- **Within the tolerances:**
  - SWE: RMSE 0.02 mm, peak −8 %;
  - HS: RMSE 1.7 mm;
  - LWnet with snow;
  - soil temperature below 10 cm: bias ≤ 0.04 K, RMSE ≤ 0.09 K;
  - θw below 15 mm;
  - maximum frost depth: same cell, 15 mm.
- **Beyond the tolerances:**
  - HS max, by 20 mm: GEOtop keeps the thickness of a melting single layer;
  - melt −11 % and runoff −21 %;
  - Ts, SWnet, H and LE with snow: thin snow is not ported;
  - soil RMSE 0.25–0.40 K at 15–85 mm: the conditioned bare-soil top;
  - θ at the top cell.

  The notebook explains each one. Nothing was tuned.
