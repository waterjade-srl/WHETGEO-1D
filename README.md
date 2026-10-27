# WHETGEO-1D
WHETGEO-1D (Water HEat Transport in GEOframe) is a Java-based modeling environment simulating water flow, heat and solute transport in variably saturated porous media.

The source code of external libraries contained in the `lib` folder can be found here:
- [Buffer](https://github.com/geoframecomponents/Buffer) contains the source code of the buffer componets. 
- [ClosureEquation](https://github.com/geoframecomponents/ClosureEquation) contains the source code of the parametrization describing the hydraulic and thermal properties of the soil.
- [NetCDF](https://github.com/geoframecomponents/NetCDF) contains the source code of the components to read and write netCDF files,
- [Numerical](https://github.com/geoframecomponents/Numerical) contains the source code the classes dealing with the algorithms to solve linear systems and to linearize nonlinear systems.

The executables can be found [here](https://github.com/GEOframeOMSProjects/OMS_Project_WHETGEO1D).

Hoping that WHETGEO will whet your interest.

# geo2geo prototype

The branch `geo2geo` of this fork carries the prototype of the GEO2GEO project: GEOtop 3.0's snow model ported into WHETGEO-1D, for 1D columns. The OMS3 component `SnowSoilSolver1DMain` solves the heat of snow and soil as one system, with Richards' equation and frozen soil under the snow. It has two configurations: *fidelity* (GEOtop 3.0's options) and *recommended* (the set proposed in the design). What this version holds, what has been checked and what is known not to work yet: [RELEASE_NOTES_GEO2GEO.md](RELEASE_NOTES_GEO2GEO.md).

## Getting started

You need JDK 21 and Maven; Python 3 with numpy, pandas and matplotlib for the comparison scripts, Jupyter for the notebooks, and geopandas and pyproj for the case tools.

```bash
git clone -b geo2geo https://github.com/waterjade-srl/WHETGEO-1D.git
cd WHETGEO-1D
mvn -q test                                   # online the first time: it fetches the dependencies; ~5 min of tests
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt   # the classpath of the java commands (the run scripts write it too)
cases/snowsoil/matsch_p2_ref_007/run.sh       # a month at Matsch P2 in the fidelity configuration, ~10 s
```

The comparison with GEOtop 3.0 is in `Jupyter_Notebook/6_Matsch_P2_Ref_007_Fidelity.ipynb`.

After the first online build every script works offline. Then:

| To | Read |
|---|---|
| run the cases, set the options, read the outputs | [`cases/snowsoil/README.md`](cases/snowsoil/README.md), the user guide |
| run Friuli and the Canadian boreholes | their READMEs, *Getting the data*: the data are shared separately |
| make a case from your own GEOtop 3.0 point run | [`tools/geotop2case`](tools/geotop2case/README.md); the GEOtop runs of Friuli and Canada are shared as `geotop_runs.tar.gz`, with the case data |
| add grids of the soil or an initial snowpack to a case | [`tools/caseedit`](tools/caseedit/README.md) |
| check that a case holds together | `java -cp target/classes:$(cat target/cp.txt) org.geoframe.whetgeo1d.io.snowsoil.CaseGeoPackage case.gpkg` |
| check that a change keeps the results | `tools/snow_regression.sh` and `tools/golden_baseline.sh` |

The prototype is a work in progress: options, inputs and outputs may still change. Comments and problems: the Issues of this fork.

# Contributing
Your contributions are always welcome! Please take a look at the [contribution guidelines first](http://geoframe.blogspot.com/2020/05/geoframe-community-publication-policy.html).

# Acknowledgements

-  Niccolò Tubini, Riccardo Rigon developed the theoretical aspects of the model (Authors). 
-  Niccolò Tubini, Riccardo Rigon designed the first version of the code (Authors)
-  Niccolò Tubini implemented and deployed it (Authors)
-  Riccardo Rigon provided financial support
-  Niccolò Tubini was supported by a Ph.D. grant by [DICAM-UniTrento](https://www.unitn.it/dricam/) by the Italian MIUR Project (PRIN 2017)
“WATer mixing in the critical ZONe: observations and predictions under environmental changes-WATZON” (project code: 2017SL7ABC).
-  We thank Professor Vincenzo Casulli and Professor Michael Dumbser for their fruitful discussions on the numerical aspects of the work. 
