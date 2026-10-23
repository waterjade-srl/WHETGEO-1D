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
LABEL=noskin SIM_OPTS="-DskinNode=None" cases/snowsoil/canada_ntgs/run.sh   # no skin node (sensitivity, see Results)
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
- **Ported since the first runs (round 4), active in fidelity:**
  - GEOtop's skin node (`HighestNodeCorrespondsToLayer = 0`, SURF-1/2), over snow and bare soil, eliminated in the solver (`SkinNode`): the cause of the 1–1.5 K colder soil of the first runs (see Results);
  - GEOtop's thin-snow regime: soil roughness and emissivity below `ThresSnowSoilRough` = `SoilRoughness` = 10 mm by default, so a few days in autumn;
  - GEOtop's snow–soil and snow–snow face conductance (SNOW-3);
  - GEOtop's porosity cap at ρw (SNOW-6);
  - GEOtop's shortwave penetration (SNOW-7, `ShortwavePenetration.GEOTOP`): with more than one snow layer the net shortwave is absorbed inside the layers with k = ρ/3 + 50 m⁻¹, the rest by the top soil cell, none at the skin (`rad_snow_absorption`, `radiation.cc:582-613`). It closes the residual 0.35–0.6 K the skin left (see Results).

  Without the shortwave penetration, on NTGS1 ERA5 only the face conductance moved the soil, by −0.15 K at every depth (with it, +0.05 K for the harmonic mean); thin-snow regime and porosity cap change it by less than 0.01 K. `-DthinSnowRegime=None`, `-DfaceConductance=Harmonic_Mean`, `-DporosityCap=Ice_Density`, `-DshortwavePenetration=None` and `-DskinNode=None` (through `SIM_OPTS`) restore the previous behaviour; `-DheatPicardIterations=3` refreshes the conductivities within the step (no effect: less than 0.002 K).
- **Not ported:**
  - delayed refreezing (STA-3);
  - GEOtop's recomputation of the turbulence without stable correction inside the Newton loop (after 10 iterations with an iterate below −50 °C): it never fires in these cases (see Results).

### Step control

The component's default, `onNonConvergence = Stop`. No step is rejected in the four cases.

One hour needed attention: in NTGS1 JRA-3Q, 5 October 2021 12:00 UTC, a saturated peat cell at 0.26–0.42 m forms its first ice exactly at its melting temperature (T − T* ≈ −3·10⁻⁸ K). The forcing is ordinary (night, −2.8 °C, wind below 3 m s⁻¹, no precipitation). At that point the liquid content falls as |T − T*|^1.8 (Van Genuchten n = 1.8, α = 30 m⁻¹), its second derivative is unbounded, and Newton converges only linearly: the per-cell residual goes from 743 to 9.4 to 0.005 times its bound (1e-3 J m⁻²) in successive polish rounds of the heat driver, at any step length, so halving the step did not help. With a single polish round the step was rejected 6 times; the heat driver now repeats the polish rounds until every cell is within its bound (at most 10). Over the three years only this step needs a second round, and the run time is unchanged.

## Results

Full tables and figures are in the notebook. In short (porting − GEOtop, 01/09/2021–01/09/2023, with the skin node and the shortwave penetration; soil from `soilTzav.csv`; `python3 compare.py`):

| Case | SWE daily bias / RMSE | SWE peaks (porting / GEOtop) | Snow-on, snow-off | Ts with snow (bias) | T bias at 0.1 / 1 / 4 m | Max thaw 2022, 2023 (porting / GEOtop) |
|---|---|---|---|---|---|---|
| NTGS1 ERA5 | 0.29 / 0.38 mm | 89.5/89.0, 104/104 mm | 0, 0 d | −0.01 K | +0.04 / +0.13 / +0.06 K | 789/770, 921/897 mm |
| NTGS1 JRA-3Q | 0.34 / 0.48 mm | 97.2/96.7, 129/129 mm | −1 / 0, 0 / +1 d | +0.02 K | +0.02 / +0.09 / +0.02 K | 717/700, 848/825 mm |
| NTGS2 ERA5 | 0.33 / 0.45 mm | 85.7/85.1, 98.9/98.3 mm | 0, 0 d | −0.01 K | +0.02 / +0.07 / −0.01 K | 758/742, 915/889 mm |
| NTGS2 JRA-3Q | 0.71 / 1.01 mm | 92.9/91.1, 125/124 mm | 0, 0 d | −0.01 K | +0.06 / +0.10 / +0.02 K | 702/685, 845/819 mm |

The daily soil RMSE is 0.23–0.35 K at 0.1–1 m and 0.10–0.13 K at 4 m. What is left: the soil at 0.5–1 m is up to 0.5–0.8 K warmer in January–February, the top 0.1 m up to 0.35 K colder in November–December, and the summer thaw 16–26 mm deeper. A case takes 65–95 s; NTGS1 JRA-3Q and NTGS2 JRA-3Q have 1 and 3 rejected steps, each accepted after halving.

How the gap closed, step by step (soil bias at 0.1 / 1 / 4 m, NTGS1 ERA5):

| Porting | Soil bias | SWE daily RMSE | Snow-off |
|---|---|---|---|
| First runs (no skin, no penetration) | −1.42 / −1.18 / −1.02 K | 5.7 mm | +3, +5 d |
| + GEOtop's face conductance, thin snow, porosity cap | −1.58 / −1.33 / −1.15 K | 5.8 mm | +3, +4 d |
| + skin node, thin cells unshrunk | −0.51 / −0.35 / −0.31 K | 4.0 mm | +2, +2 d |
| + shortwave penetration | +0.04 / +0.13 / +0.06 K | 0.38 mm | 0, 0 d |

- **The skin node (SURF-1/2).** An instrumented GEOtop 3.0 (INDAGINE_SUOLO.md in the case folder of the GEO2GEO workspace) showed that GEOtop 3.0 without its skin (`HighestNodeCorrespondsToLayer = 1`) is 1.07–1.32 K colder than with it. The surface flux reaches the centre of the top cell through half of its thickness; without the skin that resistance is missing and the pack insulates the soil less. GEOtop restarts the skin at the top-layer temperature at every step (`energy.balance.cc:732`) and freezes the resistances there, which gives the negative Obukhov lengths of 16–45 % of the snow hours; the port does the same.
- **The shortwave penetration (SNOW-7).** With the skin, the residual (0.35–0.6 K colder) grew from February to April, with the shortwave: from December to January H matched GEOtop's, from February to April the port's was 1.6–3.6 W m⁻² smaller. With more than one layer GEOtop gives no shortwave to the skin: it is absorbed in the top layer, which then starts each step warmer, and the colder skin draws more sensible heat from the air.
- **Not the emergency path, a GEOtop artefact that does not act here.** GEOtop recomputes the resistances with `MoninObukhov = 2` (no stable correction) after 10 Newton iterations once an iterate of the surface temperature has fallen below −50 °C (`energy.balance.cc:57-58, 1835, 2618-2621`). In the four cases it never fires (0 of about 17 000 snow steps per case; switched off, the outputs are byte-identical). It is not ported.
- **Not the output convention.** GEOtop's soil profile is an hourly mean; `compare.py` compares the porting's `soilTzav.csv` with it. With one step per hour it equals the end-of-hour value to the third digit.
- **Without snow** the porting's `Tsurface[C]` is the imposed value (GEOtop's `Tsurface`); before round 5 it was written as the temperature of the top soil cell, which looked 0.9 K colder in summer.
- **Speed:** 65–95 s per case for 3 years hourly with 20 soil cells (26 280 steps).
