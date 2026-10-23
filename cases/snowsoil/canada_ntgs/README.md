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
  - GEOtop's porosity cap at ρw (SNOW-6).

  On NTGS1 ERA5 only the face conductance moves the soil, by −0.15 K at every depth. The other two change it by less than 0.01 K. `-DthinSnowRegime=None`, `-DfaceConductance=Harmonic_Mean` and `-DporosityCap=Ice_Density` (through `SIM_OPTS`) restore the previous behaviour.
- **Not ported:**
  - shortwave penetration (SNOW-7) and delayed refreezing (STA-3);
  - GEOtop's recomputation of the turbulence without stable correction inside the Newton loop (after 10 iterations with an iterate below −50 °C): it never fires in these cases (see Results).

### Step control

The component's default, `onNonConvergence = Stop`. No step is rejected in the four cases.

One hour needed attention: in NTGS1 JRA-3Q, 5 October 2021 12:00 UTC, a saturated peat cell at 0.26–0.42 m forms its first ice exactly at its melting temperature (T − T* ≈ −3·10⁻⁸ K). The forcing is ordinary (night, −2.8 °C, wind below 3 m s⁻¹, no precipitation). At that point the liquid content falls as |T − T*|^1.8 (Van Genuchten n = 1.8, α = 30 m⁻¹), its second derivative is unbounded, and Newton converges only linearly: the per-cell residual goes from 743 to 9.4 to 0.005 times its bound (1e-3 J m⁻²) in successive polish rounds of the heat driver, at any step length, so halving the step did not help. With a single polish round the step was rejected 6 times; the heat driver now repeats the polish rounds until every cell is within its bound (at most 10). Over the three years only this step needs a second round, and the run time is unchanged.

## Results

Full tables and figures are in the notebook. In short (porting − GEOtop, 01/09/2021–01/09/2023, with the skin node; soil from `soilTzav.csv`):

| Case | SWE RMSE | SWE peaks (porting / GEOtop) | Snow-off | Ts with snow (bias) | T bias at 0.1 / 1 / 4 m | H under snow (porting / GEOtop) | Max thaw 2022, 2023 (porting / GEOtop) |
|---|---|---|---|---|---|---|---|
| NTGS1 ERA5 | 4.0 mm | 90/89, 107/104 mm | +2, +2 d | −0.18 K | −0.55 / −0.38 / −0.34 K | −6.2 / −6.9 W m⁻² | 768/770, 895/897 mm |
| NTGS1 JRA-3Q | 5.2 mm | 97/97, 133/129 mm | +2, +4 d | −0.13 K | −0.56 / −0.43 / −0.42 K | −6.5 / −7.0 W m⁻² | 699/700, 822/825 mm |
| NTGS2 ERA5 | 4.0 mm | 86/85, 101/98 mm | +2, +4 d | −0.17 K | −0.59 / −0.46 / −0.44 K | −6.3 / −6.9 W m⁻² | 739/742, 884/889 mm |
| NTGS2 JRA-3Q | 4.9 mm | 93/91, 127/124 mm | +2, +2 d | −0.17 K | −0.58 / −0.47 / −0.47 K | −6.4 / −7.0 W m⁻² | 682/685, 815/819 mm |

Without the skin (the first runs, and `-DskinNode=None`) the soil was 1.4–1.6 / 1.2–1.4 / 1.0–1.35 K colder at 0.1 / 1 / 4 m, H under snow −4.2 to −5.3 W m⁻², Ts with snow −0.4 to −0.6 K. No step is rejected with or without the skin; a case takes 75–95 s.

- **Snow** follows GEOtop:
  - snow-on on the same day;
  - snow-off 3–5 days later;
  - peaks within 2–6 %;
  - HS 14–17 mm higher on average (settling, SNOW-6).
- **The soil was 1–1.5 K colder at every depth without the skin node.** An instrumented GEOtop 3.0 (INDAGINE_SUOLO.md in the case folder of the GEO2GEO workspace) showed why:
  - **GEOtop's skin node.** GEOtop 3.0 without its skin (`HighestNodeCorrespondsToLayer = 1`) is 1.07–1.32 K colder than with it: the porting's deficit. The porting without skin matched GEOtop-without-skin within 0.05 K from 0.5 to 4 m, in H with snow (−4.2 against −4.2 W m⁻², NTGS1 ERA5) and in the SWE peak.
  - **Mechanism.** The surface flux reaches the centre of the top cell through half of its thickness. Without the skin that resistance is missing and the pack insulates the soil less in winter. The halved sensible heat is a symptom: GEOtop with its skin but resistances evaluated at the skin gets the porting's H, and its soil cools by only 0.2 K.
  - **The negative Obukhov lengths** (16–45 % of the snow hours) come from GEOtop restarting the skin at the top-layer temperature at every step (`energy.balance.cc:732`): the resistances are frozen there, 2.3–2.6 K above the air in those hours, while the skin ends the step 1–2 K below it. The port does the same.
  - **Not the emergency path, a GEOtop artefact that does not act here.** GEOtop recomputes the resistances with `MoninObukhov = 2` (no stable correction) at the current iterate after 10 Newton iterations once an iterate of the surface temperature has fallen below −50 °C (`energy.balance.cc:57-58, 1835, 2618-2621`). In the four cases it never fires: an iterate below −50 °C 0 / 33 / 0 / 29 times, never with more than 10 iterations, so 0 of about 17 000 snow steps per case; switching it off gives byte-identical outputs. It is not ported.
  - **Not the output convention.** GEOtop's soil profile is an hourly mean; `compare.py` compares the porting's `soilTzav.csv` with it. With one step per hour it equals the end-of-hour value, to the third digit.
  - **The skin closes about two thirds of the gap.** The residual is 0.35–0.6 K colder; candidates, not yet investigated: the face conductance (SNOW-3), which moved the soil by −0.15 K without the skin and should be rechecked with it; GEOtop's skin conductance recomputed at each Newton iterate (here at the start of the step); the 0 °C handling; settling (HS 6–10 mm higher).
- **Thaw depth** at the summer maximum is within 1–5 mm of GEOtop's (20–30 mm shallower without the skin). The summer top is GEOtop's `Tsurface`.
- **Speed:** 60–70 s per case for 3 years hourly with 20 soil cells (26 280 steps).
