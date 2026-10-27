# WHETGEO-1D, branch geo2geo

WHETGEO-1D (water and heat in the soil, GEOframe, Java 21, OMS3) with the prototype of the GEO2GEO project: GEOtop 3.0's snow model ported into a 1D snow and frozen-soil column. Two configurations: *fidelity* (GEOtop 3.0's options, to compare with GEOtop) and *recommended* (the set proposed in the design). Start from `README.md` (getting started) and `RELEASE_NOTES_GEO2GEO.md` (what is checked, known limits).

## Where things are

| What | Where |
|---|---|
| The OMS3 components of the prototype | `src/main/java/org/geoframe/whetgeo1d/solvers/SnowSoilSolver1DMain.java` (the column; every option is an `@In` field with its `@Description`), `TerrainShortwaveMain.java` (shortwave on the terrain) |
| The column: heat of snow and soil, Richards under the snow, step control, restart, balances | `core/snowsoil/` (`SnowSoilSolver`, `SnowSoilHeatDriver`, `SoilWaterDriver`, `StepController`, `ColumnBalance`, `InitialSnowpack`) |
| Snow physics: cell state, equation of state, compaction, percolation, restratification, conductivity | `snow/` (`SnowCell`, `SnowEquationOfState`, `MassCriterionRestratification`, `GradedThicknessRestratification`, …) |
| Surface: turbulence, albedo, energy balance, sublimation | `snow/surface/` |
| Forcing on the terrain, horizon, clouds, longwave | `core/snowsoil/forcing/` |
| Test harness that rebuilds GEOtop's own forcing (not part of the model) | `core/snowsoil/fidelity/` |
| Inputs and outputs: case GeoPackage, soil grid, NetCDF and CSV writers | `io/snowsoil/` (`CaseGeoPackage`, `SoilGridInputs`, `SnowSoilCsvWriter`) |
| Soil closures (Van Genuchten, Dall'Amico freezing) and the nested Newton solver | the Maven dependencies `closure-equation` and `numerical` (GEOframe), not in this repository |
| The original WHETGEO-1D solvers (soil only) | `solvers/Richards*`, `solvers/Heat*` |
| Cases, with their README (what is simulated, how to run, results) | `cases/snowsoil/<case>/`; the user guide is `cases/snowsoil/README.md` |
| Reference results against GEOtop 3.0 and observations | the *Results* sections of the case READMEs; notebooks `Jupyter_Notebook/6_Matsch_P2_Ref_007_Fidelity.ipynb`, `7_Canada_NTGS_Fidelity.ipynb`, `cases/snowsoil/friuli/friuli_comparison.ipynb` |
| Case tools | `tools/geotop2case` (a case from GEOtop 3.0 point runs), `tools/caseedit` (named soil grids, initial snow) |
| Tests | `src/test/java/...`, mirroring the main packages; analytic solutions in `core/snowsoil/NeumannStefanTest`, `LunardiniTest` |

Identifiers such as COL-3, SNOW-2, VAL-1 refer to the decisions of the project's design specification; the code and the READMEs cite them where a choice was made.

## Commands

```bash
mvn -q test                                                  # all tests (online the first time)
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt   # classpath for the java commands below
cases/snowsoil/matsch_p2_ref_007/run.sh                      # fidelity case against GEOtop 3.0 (~10 s)
cases/snowsoil/friuli/run_terrain.sh                         # Friuli on the terrain of the stations: the operational run (output/terrain/)
cases/snowsoil/friuli/run_all.sh [1 2 3 4]                   # Friuli as a flat column, for comparison (output/station_<n>/)
K=Sturm_1997 cases/snowsoil/friuli/run_terrain.sh            # a sensitivity run (K, STABILITY, SW, ALPHA; also with run_all.sh), its own output folder
python3 cases/snowsoil/friuli/compare.py                     # Friuli: every run in output/ against the observed snow depth
cases/snowsoil/canada_ntgs/run.sh && python3 cases/snowsoil/canada_ntgs/compare.py
java -cp target/classes:$(cat target/cp.txt) org.geoframe.whetgeo1d.io.snowsoil.CaseGeoPackage case.gpkg   # check a case
tools/snow_regression.sh                                     # snow regression (one ARF_1D season, bit for bit)
tools/golden_baseline.sh                                     # golden baseline of the soil solvers
python3 -m pytest -q tools/geotop2case
```

- `oms3.CLI` may exit with 0 when a run fails: the run scripts look for `>>>> Error` in the log, and so should you.
- The data of Friuli and of the Canadian boreholes are not in the repository: each README, *Getting the data*, says how to get them and how to check them (`sha256sum -c DATA.sha256`). The GEOtop 3.0 runs they come from are shared as `geotop_runs.tar.gz` (`canada/`, `friuli/`): inputs and GEOtop's outputs, ready for `tools/geotop2case`.
- Every output starts with a header: code version, configuration, every option as resolved (defaults included), SHA-256 of the inputs. Compare the headers of two runs to see what changed.
- The VAL-1 tolerances against GEOtop are in `cases/snowsoil/matsch_p2_ref_007/README.md` (*Result*) and notebook 6; the notebooks need Jupyter, or can be run as scripts from `Jupyter_Notebook/`.
- `CaseGeoPackage` checks the static part of a case only. That the series a run needs exist (air temperature, relative humidity and wind are required) is checked by `geotop2case`, which warns, and by the component when it starts.
- A run on a new case needs a `.sim`: start from `cases/snowsoil/friuli/snowsoil_friuli_terrain.sim` (a case GeoPackage, OMS series, shortwave on the terrain) and change the case file, the series folder, the dates and the readers of the series the case has.

## Rules

- **One model behaviour.** No options or modes in the components only for tests or for fidelity with GEOtop. To reproduce GEOtop, change the data of the case (`geotop2case --svf-of-run`) or the test harness (`core/snowsoil/fidelity/`). A run in other conditions is another case.
- **The geometry is always the true one of the point** (slope, aspect, horizon, sky view factor from the case).
- **Model choices are parameters**, set in the `.sim` or with `-D` overrides; they are recorded in the output header.
- **After any change to the code:** `mvn -q test`, `tools/snow_regression.sh` and `tools/golden_baseline.sh`. A change that moves the results says so, with the numbers.
- **Answer with evidence:** cite the file and line, or the README section, a statement comes from; rerun what you report instead of quoting numbers from memory.
- Branches start from `geo2geo`, and pull requests go to `geo2geo` of this fork. Commit messages describe the change; no names of people in commits, code or documents.
- The repository is public: no data that cannot be distributed, no notes of meetings.
