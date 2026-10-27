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

The branch `geo2geo` of this fork carries the prototype of the GEO2GEO project: GEOtop 3.0's snow model ported into WHETGEO-1D, for 1D columns. The OMS3 component `SnowSoilSolver1DMain` solves the heat of snow and soil as one system, with Richards' equation and frozen soil under the snow. It has two configurations: *fidelity* (GEOtop 3.0's options) and *recommended* (the set proposed in the design).

- [`cases/snowsoil/README.md`](cases/snowsoil/README.md): user guide (build, run, options, outputs, coupling with other GEOframe components) and the cases.
- [`tools/geotop2case`](tools/geotop2case/README.md): the case GeoPackage and the OMS series from a GEOtop 3.0 point run.
- `tools/snow_regression.sh` and `tools/golden_baseline.sh`: the snow regression and the golden baseline.

The data of the Friuli and Canadian cases are not in the repository; their READMEs say how to get them.

The prototype is a work in progress: options, inputs and outputs may still change.

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
