# caseedit

Edits a case GeoPackage (`CaseGeoPackage`, written by `tools/geotop2case`): named grids of a soil profile and the snow at the start of an initial condition. A run picks them with `caseGrid` and `caseInitialCondition` of `SnowSoilSolver1DMain`.

```bash
python3 tools/caseedit/caseedit.py grid add case.gpkg --profile 1 --method mixed --dz-min 0.005 --growth 0.2 --dz-max 0.1 --note fine
python3 tools/caseedit/caseedit.py grid list case.gpkg
python3 tools/caseedit/caseedit.py snow case.gpkg --ic 1 --swe 23.78 --density 208.72 --temperature -21.47 [--age 0]
python3 tools/caseedit/caseedit.py snow case.gpkg --ic 1 --layers pit.csv [--age 2]
python3 tools/caseedit/caseedit.py snow case.gpkg --ic 1 --none
```

Then check the case: `java -cp target/classes:$(cat target/cp.txt) org.geoframe.whetgeo1d.io.snowsoil.CaseGeoPackage case.gpkg` prints what does not hold together, and exits with 1 if anything does not. The component runs the same checks it needs at the start of a run.

## Grids

The cells come from GEOframe's own grid builder, `grid1D` of `Jupyter_Notebook/WHETGEO1D_GridCreator.py`, applied to the `soil_layers` of the profile:

| Method | Cells |
|---|---|
| `classical` | K cells of equal thickness in each layer (the `K` of `soil_layers`); the grid WHETGEO-1D's reader builds without a named grid |
| `exponential` | from `dz-min` at the surface, growing by the rate `growth`, over the whole profile; a cell may straddle two layers |
| `mixed` | from `dz-min` at each face of a layer, growing by `growth` up to `dz-max` |

- Each cell takes the equation of state and the parameters of the layer that holds its centre, as GridCreator's `set_parameters_*`.
- The grid is written to `grids` (method and its parameters) and `grid_cells` (faces of every cell, from the top).
- GridCreator imports bokeh for its plots only; without bokeh installed, empty modules stand in for it.
- `mixed` leaves, at the faces of the layers, the remainders of its growth: cells much thinner than `dz-min` can appear (0.35 mm with `dz-min` 5 mm on Matsch_P2_Ref_007). They come from GridCreator.

## Initial snow

- **One layer**, as GEOtop's `InitSWE`, `InitSnowDensity`, `InitSnowTemp`, `InitSnowAge`: table `snow_initial` (`swe` [kg m⁻²], `density` [kg m⁻³], `temperature` [K], `age` [d]). `geotop2case` writes it from the GEOtop run.
- **Layers**, from a CSV with the columns `thickness` [m], `density` [kg m⁻³], `temperature` [°C], `liquid` [kg m⁻²], top first: table `snow_initial_layers`. The age stays in `snow_initial`.

The model turns the declared layers into its own cells (`InitialSnowpack`): mass and internal energy of each layer, merged where they exceed the capacity of the pack, at equilibrium (liquid declared in a cold layer refreezes, and a layer whose liquid holds more latent heat than its cold content ends at 0 °C), then restratified by the configuration until nothing changes. Mass and energy are conserved.
