# Canadian case NTGS1 / NTGS2: fidelity, heat only, against GEOtop 3.0

This case runs the snow+soil column on the two NTGS boreholes near Inuvik (NWT, Canada), with ERA5 and JRA-3Q forcing, four cases in all:

- the *fidelity* configuration;
- soil water fixed (`soilWaterMode = Fixed`, as GEOtop's `WaterBalance = 0`).

**Reference.** GEOtop 3.0 without canopy, corrected, of the GEO2GEO workspace (`casi/Canada_NTGS/sims/<site>_<forcing>_senza_chioma_corretta`). That run has:

- `ActualOrProjectedArea = 1`, so precipitation × cos β once;
- `LWin` from the reanalysis;
- 20 soil layers to 9.03 m, with zero heat flux at the bottom;
- start on 01/09/2020 from the GEOtop 2.0 profile;
- slope 30° (NTGS1) or 35° (NTGS2) facing north, flat horizon;
- sensors at 10 m;
- the case's snow keywords.

**Period.** The comparison runs over 01/09/2021–01/09/2023; the first year is warm-up.

## Run

```bash
cases/snowsoil/canada_ntgs/run.sh                  # forcing (data/<case>/) and run (output/<case>/), four cases, ~5 min
cases/snowsoil/canada_ntgs/run.sh NTGS1_ERA5       # one case
python3 cases/snowsoil/canada_ntgs/compare.py      # comparison table
```

Then open `Jupyter_Notebook/7_Canada_NTGS_Fidelity.ipynb`.

`python3 make_inputs.py [sims_folder]` regenerates `geotop/` and `grid/` from the GEOtop runs.

For sensitivity runs, `-D` overrides of the .sim go through `SIM_OPTS`, and the output goes to `output/<case>_<LABEL>/`:

```bash
LABEL=flag SIM_OPTS="-DonNonConvergence=Flag" cases/snowsoil/canada_ntgs/run.sh NTGS1_JR3QG
LABEL=skin SIM_OPTS="-DexperimentalSkin=true" cases/snowsoil/canada_ntgs/run.sh   # experimental skin node (see Results)
```

The other overrides are `-DheatTolerance=` and `-DendDate=`.

## Files

| Path | Content |
|---|---|
| `geotop/<case>/` | GEOtop's inputs: `meteo0001.txt.gz` (the original `meteo0001.txt.old`, gzipped), `soil0001.txt`, `horizon0001.txt`, `geotop.inpts`; `forcing.properties` for the forcing tools; `reference.csv.gz` (hourly reference, see below) |
| `grid/<case>/` | the soil as WHETGEO-1D CSV inputs |
| `snowsoil_canada_ntgs.sim` | the OMS3 simulation, one case per run (`-Dcase=`) |
| `run.sh`, `make_inputs.py`, `compare.py` | run, inputs, comparison |
| `data/`, `output/` | forcing and outputs (not versioned) |

`reference.csv.gz` holds GEOtop's hourly results:

- from `point0001.txt`: Tsurface, Evap_surface, HS, SWE, thawed depths;
- from `soilTz0001.txt`: T at 0.1, 0.5, 1, 2 and 4 m.

## Settings

### Forcing

The forcing is GEOtop's own, computed by the temporary fidelity tools (`GeotopForcingFiles`) from GEOtop's input files:

| Quantity | How | Check against GEOtop's point output (NTGS1 ERA5) |
|---|---|---|
| Ta, RH, wind | step means of the meteo file, `RHmin` 10 %, `Vmin` 0.5 m s⁻¹ | 0, 5e-7, 0 |
| pressure | 1013.25 e^(−z/8500) hPa | 0 |
| rain, snow | `IPrec` × cos β (once), linear ramp 1 / −3 °C, no correction | 5e-7 mm |
| SW beam, diffuse | on the slope from the global SW (`SlopeShortwave`) | 7e-7 W m⁻² |
| LW in | the reanalysis column `LWin`, as it is (`longwaveInHeader`) | 0 |

### Soil

- 20 layers in η: one parameter set per layer; Van Genuchten with Dall'Amico freezing; GEOtop's specific storage 1e-7 mm⁻¹.
- Solids: λ = 2.5 W m⁻¹ K⁻¹, ρc = 2.2 MJ m⁻³ K⁻¹. Water conductivity 0.567.
- θ comes from `InitPres` and is fixed.
- T(0) = `InitTemp` per layer.
- Bottom: zero heat flux.

### Snow and surface

- Fidelity configuration: Cosenza snow conductivity; `MoninObukhov = 1` (both corrections); sensors at 10 m.
- The case's keywords, through `geotopSnowParameters`: FreshSnowReflVis 0.9, NIR 0.65, AlbExtParSnow 10, IrriducibleWatSatSnow 0.02, SnowEmissiv 0.99, SnowRoughness 0.1, DrySnowDefRate 1, WetSnowDefRate 1.5, SnowDensityCutoff 100, SnowViscosity 1e6, MaxWaterEqSnowLayerContent 10, SWEbottom = SWEtop = 20, MaxSnowLayersMiddle 4, PointSlope.
- Ground albedo 0.2, GEOtop's default.

### Fallbacks and what is not mapped

- **Bare soil:** the column has no bare-soil energy balance. Without snow at the start of a step, GEOtop's `Tsurface` is the Dirichlet top, at the surface face of the top cell. When GEOtop still has snow and the column has none, that value is GEOtop's snow-surface temperature.
- **Evaporation:** irrelevant with fixed water, so not passed.
- **Not ported:**
  - thin-snow roughness and emissivity: below `ThresSnowSoilRough` = `SoilRoughness` = 10 mm by default, so a few days in autumn;
  - shortwave penetration (SNOW-7) and delayed refreezing (STA-3);
  - GEOtop's snow–soil interface conductance (SNOW-3; the column uses the harmonic mean);
  - GEOtop's skin node over snow (`HighestNodeCorrespondsToLayer = 0`, GEOtop's default; SURF-1/2 agreed, not implemented): the main cause of the colder soil (see Results);
  - GEOtop's recomputation of the turbulence without stable correction inside the Newton loop (after 10 iterations with an iterate below −50 °C): it never fires in these cases (see Results).

### Step control

The component's default, `onNonConvergence = Stop`. No step is rejected in the four cases.

One hour needed attention: in NTGS1 JRA-3Q, 5 October 2021 12:00 UTC, a saturated peat cell at 0.26–0.42 m forms its first ice exactly at its melting temperature (T − T* ≈ −3·10⁻⁸ K). The forcing is ordinary (night, −2.8 °C, wind below 3 m s⁻¹, no precipitation). At that point the liquid content falls as |T − T*|^1.8 (Van Genuchten n = 1.8, α = 30 m⁻¹), its second derivative is unbounded, and Newton converges only linearly: the per-cell residual goes from 743 to 9.4 to 0.005 times its bound (1e-3 J m⁻²) in successive polish rounds of the heat driver, at any step length, so halving the step did not help. With a single polish round the step was rejected 6 times; the heat driver now repeats the polish rounds until every cell is within its bound (at most 10). Over the three years only this step needs a second round, and the run time is unchanged.

## Results

Full tables and figures are in the notebook. In short (porting − GEOtop, 01/09/2021–01/09/2023):

| Case | SWE RMSE | SWE peaks (porting / GEOtop) | Snow-off | Ts with snow (bias) | T bias at 0.1 / 1 / 4 m | Max thaw 2022, 2023 (porting / GEOtop) |
|---|---|---|---|---|---|---|
| NTGS1 ERA5 | 5.7 mm | 92/89, 109/104 mm | +3, +3 d | −0.62 K | −1.42 / −1.18 / −1.02 K | 748/770, 869/897 mm |
| NTGS1 JRA-3Q | 6.9 mm | 99/97, 134/129 mm | +3, +5 d | −0.41 K | −1.45 / −1.26 / −1.14 K | 683/700, 797/825 mm |
| NTGS2 ERA5 | 5.7 mm | 87/85, 104/98 mm | +3, +4 d | −0.58 K | −1.47 / −1.26 / −1.13 K | 720/742, 858/889 mm |
| NTGS2 JRA-3Q | 6.3 mm | 95/91, 128/124 mm | +3, +3 d | −0.37 K | −1.48 / −1.30 / −1.21 K | 666/685, 789/819 mm |

- **Snow** follows GEOtop:
  - snow-on on the same day;
  - snow-off 3–5 days later;
  - peaks within 2–6 %;
  - HS 14–17 mm higher on average (settling, SNOW-6).
- **The soil is 1–1.5 K colder at every depth.** The deficit builds up under snow and is carried down and into the summer. An instrumented GEOtop 3.0 (INDAGINE_SUOLO.md in the case folder of the GEO2GEO workspace) shows why:
  - **GEOtop's skin node.** GEOtop 3.0 without its skin (`HighestNodeCorrespondsToLayer = 1`) is 1.07–1.32 K colder than with it. That is the porting's deficit: the porting matches GEOtop-without-skin within 0.05 K from 0.5 to 4 m and 0.15–0.2 K at 0.1 m, in H with snow (−4.2 against −4.2 W m⁻², NTGS1 ERA5) and in the SWE peak.
  - **Mechanism.** The surface flux reaches the centre of the top cell through half of its thickness. Without the skin, that resistance is missing and the pack insulates the soil less in winter. The halved sensible heat is a symptom: GEOtop with its skin but resistances evaluated at the skin gets the porting's H, and its soil cools by only 0.2 K.
  - **The negative Obukhov lengths** (16–45 % of the snow hours) come from GEOtop restarting the skin at the top-layer temperature at every step (`energy.balance.cc:732`). The resistances are frozen there, 2.3–2.6 K above the air in those hours, while the skin ends the step 1–2 K below it.
  - **Not the emergency path.** The recomputation of the resistances without stable correction never fires: 0 of about 17 000 snow steps per case; switching it off gives byte-identical outputs.
  - **Not the output convention.** GEOtop's soil profile is an hourly mean; `compare.py` now compares the porting's `soilTzav.csv` with it. With one step per hour it equals the end-of-hour value, so the biases are unchanged to the third digit.
  - **The experimental skin** (`-DexperimentalSkin=true`, off by default; `SnowSoilSolver.setSkin`) closes about two thirds of the gap. With it, the bias at 0.1 / 1 / 4 m is −0.50 / −0.34 / −0.29 K (NTGS1 ERA5), −0.49 / −0.36 / −0.35 (NTGS1 JRA-3Q), −0.55 / −0.42 / −0.41 (NTGS2 ERA5) and −0.53 / −0.41 / −0.41 K (NTGS2 JRA-3Q). No step is rejected.
  - **What remains** is to be investigated, starting from the snow–soil interface conductance (SNOW-3).
- **Thaw depth** is 20–30 mm shallower at the summer maximum. The summer top is GEOtop's `Tsurface`, so this is the winter deficit.
- **Speed:** 60–70 s per case for 3 years hourly with 20 soil cells (26 280 steps).
