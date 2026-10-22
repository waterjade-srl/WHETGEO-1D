# Matsch_P2_Ref_007: fidelity run against GEOtop 3.0

This case is the snow+soil column in the *fidelity* configuration on GEOtop 3.0's official 1D test `Matsch_P2_Ref_007`, run **without canopy** (`CanopyFraction = 0`). That variant is the strict reference of phase 2.1, because the canopy is out of the port's scope (SCOPE-1).

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
| `geotop/` | the GEOtop 3.0 run without canopy: `geotop.inpts`, `meteo0001.txt` (October 2009 only), `horizon0001.txt`, `soil0001.txt`, and its outputs `output-tabs/` (snow layer files cut to L1–L5). It was produced by `casi/Matsch_P2_Ref_007/run.sh senza_chioma` of the GEO2GEO workspace, with the binary at sha256 `6a65968f…09c`, GEOtop commit 1f1fc5af. `point0001.txt` sha256: `af000d2e…b147` |
| `forcing.properties` | the case's settings for the forcing tools |
| `data/` | OMS CSV forcing at 900 s, written by `run.sh` (`GeotopForcingFiles`) |
| `grid/` | the soil as WHETGEO-1D CSV inputs |
| `snowsoil_matsch_p2_ref_007.sim` | the OMS3 simulation |
| `output/` | the outputs (not versioned): `snowsoil.nc`, and the GEOtop-like CSV files with dates in UTC+1 |

## Forcing

The forcing is GEOtop's own forcing, computed from GEOtop's input files by the temporary tools of `org.geoframe.whetgeo1d.core.snowsoil.fidelity`. `GeotopForcingTest` checks it hour by hour against GEOtop's point output:

| Quantity | How GEOtop computes it | Max difference |
|---|---|---|
| Ta, RH, wind | step means of the meteo file. GEOtop reads its `JDfrom0` column, which here is **one day behind the dates**: the forcing at a date is the record dated one day later | 1e-8 °C, 5e-5, 0.013 m s⁻¹ |
| pressure | 1013.25 e^(−z/8500) hPa | 2e-7 hPa |
| rain, snow | linear ramp −3.2…3 °C, `SnowCorrFactor` 1.398 applied at the station, split again at the point, times **cos² of the slope** (GEOtop projects twice in a point simulation) | 5e-7 mm |
| SW beam and diffuse | on the slope: Iqbal clear sky, Erbs diffuse fraction, τ_c from the measured global SW. The point horizon is flat; the station horizon is used only for the cloudiness | 6e-4 W m⁻² |
| LW in | Idso + Hodges (`LWinParameterization = 4`). The meteo file has an empty `CloudTrans` column, so GEOtop does **not** fill the averaged cloudiness from SW: it uses Kimball/Walcek from T and RH at every step | 7e-3 W m⁻² |

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

The run takes 3068 steps and every heat solve converges. The full comparison against the proposed VAL-1 tolerances is in the notebook. In short:

- **Within the proposed tolerances:**
  - SWE: RMSE 0.14 mm;
  - HS: RMSE 0.8 mm;
  - melt (−2 %);
  - snow runoff (−6 %);
  - LWnet with snow;
  - soil temperature bias down to 0.35 m;
  - θw below 15 mm;
  - maximum frost depth: same cell, 15 mm.
- **Beyond the tolerances:**
  - SWE peak +21 %, and HS max differs by 6.6 mm;
  - Tsurface, SWnet, H and LE with snow;
  - soil temperature RMSE 0.24–0.42 K in the top 10 cm;
  - deep soil bias of +0.13 K;
  - θ at the top cell.

  The notebook attributes each one: thin snow not ported, rain on the fresh pack and its density, a GEOtop residual pack after 21 October, the conditioned bare-soil top, and the soil heat closures. Nothing was tuned.
