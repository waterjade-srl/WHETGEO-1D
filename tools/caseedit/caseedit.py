#!/usr/bin/env python3
"""Edits a case GeoPackage (CaseGeoPackage) written by tools/geotop2case: named grids of a soil profile and the
snow at the start of an initial condition.

Usage:
    caseedit.py grid add CASE.gpkg --profile N --method classical|exponential|mixed
                         [--dz-min M] [--growth B] [--dz-max M] [--note TEXT]
    caseedit.py grid list CASE.gpkg
    caseedit.py snow CASE.gpkg --ic N --swe KG_M2 --density KG_M3 --temperature DEGC [--age DAYS]
    caseedit.py snow CASE.gpkg --ic N --layers LAYERS.csv [--age DAYS]
    caseedit.py snow CASE.gpkg --ic N --none

Grids. The cells are built by GEOframe's own grid builder, grid1D of Jupyter_Notebook/WHETGEO1D_GridCreator.py,
from the soil_layers of the profile (Type, eta, K, equationStateID, parameterID):

- classical: K cells of equal thickness in each layer (K of soil_layers; the grid WHETGEO-1D's reader builds);
- exponential: cells growing from dz_min at the surface by the rate growth, over the whole profile;
- mixed: from dz_min at each face of a layer, growing by growth up to dz_max.

Each cell takes the equation of state and the parameters of the layer that holds its centre, as the
GridCreator's set_parameters_* do. A grid is written to the tables grids (grid_id, profile_id, method, dz_min,
growth, dz_max, cells, note) and grid_cells (grid_id, cell from 1 at the top, eta_top, eta_bottom,
equationStateID, parameterID); a run picks it with caseGrid.

Snow. One layer as GEOtop's initial keywords (snow_initial: swe, density, temperature, age), or a profile of
layers from a CSV with the columns thickness [m], density [kg m-3], temperature [degC], liquid [kg m-2], top
first (snow_initial_layers; the age stays in snow_initial). The model turns them into its own cells
(InitialSnowpack).

GridCreator imports bokeh for its plots only: without bokeh installed, empty modules stand in for it.
"""
import argparse
import csv
import os
import sqlite3
import sys
import types

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(REPO, "tools", "geotop2case"))
from geotop2case import MELTING, attributes  # noqa: E402

GRIDS = (["grid_id", "profile_id", "method", "dz_min", "growth", "dz_max", "cells", "note"],
         ["INTEGER", "INTEGER", "TEXT", "REAL", "REAL", "REAL", "INTEGER", "TEXT"],
         "named grids of a soil profile, built by WHETGEO1D_GridCreator (tools/caseedit)")
GRID_CELLS = (["grid_id", "cell", "eta_top", "eta_bottom", "equationStateID", "parameterID"],
              ["INTEGER", "INTEGER", "REAL", "REAL", "INTEGER", "INTEGER"],
              "cells of the named grids, from 1 at the top, eta of their faces [m]")
SNOW = (["ic_id", "swe", "density", "temperature", "age"], ["INTEGER", "REAL", "REAL", "REAL", "REAL"],
        "initial snow, one layer as GEOtop's InitSWE [kg m-2], density [kg m-3], T [K], age [d]")
SNOW_LAYERS = (["ic_id", "layer", "thickness", "density", "temperature", "liquid"],
               ["INTEGER", "INTEGER", "REAL", "REAL", "REAL", "REAL"],
               "initial snow by layer from the top: thickness [m], density [kg m-3], T [K], liquid [kg m-2]")


def grid_creator():
    """GEOframe's WHETGEO1D_GridCreator, with empty modules for bokeh where it is missing."""
    try:
        import bokeh  # noqa: F401
    except ImportError:
        for m in ("bokeh", "bokeh.models", "bokeh.plotting", "bokeh.io", "bokeh.layouts", "bokeh.models.widgets"):
            mod = types.ModuleType(m)
            mod.__getattr__ = lambda name: None
            sys.modules[m] = mod
    sys.path.insert(0, os.path.join(REPO, "Jupyter_Notebook"))
    import WHETGEO1D_GridCreator
    return WHETGEO1D_GridCreator


def has_table(con, name):
    return con.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (name,)).fetchone() is not None


def rows(con, name, header):
    if not has_table(con, name):
        return []
    return con.execute(f'SELECT {", ".join(chr(34) + h + chr(34) for h in header)} FROM "{name}" ORDER BY fid'
                       ).fetchall()


def rewrite(con, name, spec, keep, new):
    """Table name with its rows that keep() accepts, then the new ones."""
    header, kinds, description = spec
    attributes(con, name, header, [r for r in rows(con, name, header) if keep(r)] + list(new), kinds, description)


def build_cells(con, profile, method, dz_min, growth, dz_max):
    """Cells (eta_top, eta_bottom, equationStateID, parameterID), top first, of a profile by GridCreator."""
    layers = con.execute("SELECT Type, eta, K, equationStateID, parameterID FROM soil_layers WHERE profile_id = ? "
                         "ORDER BY fid", (profile,)).fetchall()
    if not layers:
        raise SystemExit(f"no soil profile {profile}")
    data = pd.DataFrame(layers, columns=["Type", "eta", "K", "equationStateID", "parameterID"]).astype(
        {"eta": float, "K": float, "equationStateID": float, "parameterID": float})
    gc = grid_creator()
    _, _, eta_dual, *_ = gc.grid1D(data, dz_min, growth, dz_max, method)
    faces = np.sort(np.asarray(eta_dual, dtype=float))[::-1]  # top first
    tops = [(r[1], r[3], r[4]) for r in layers]
    cells = []
    for top, bottom in zip(faces[:-1], faces[1:]):
        centre = 0.5 * (top + bottom)
        owner = [(e, p) for (t, e, p), (b, _, _) in zip(tops[:-1], tops[1:]) if b < centre <= t]
        if not owner:
            raise SystemExit(f"a cell centred at {centre} m is outside the layers of profile {profile}")
        cells.append((float(top), float(bottom), int(owner[0][0]), int(owner[0][1])))
    return cells


def grid_add(a):
    with sqlite3.connect(a.case) as con:
        cells = build_cells(con, a.profile, a.method, a.dz_min, a.growth, a.dz_max)
        ids = [r[0] for r in rows(con, "grids", GRIDS[0])]
        gid = max(ids, default=0) + 1
        rewrite(con, "grids", GRIDS, lambda r: True,
                [(gid, a.profile, a.method, a.dz_min, a.growth, a.dz_max, len(cells), a.note)])
        rewrite(con, "grid_cells", GRID_CELLS, lambda r: True,
                [(gid, i + 1) + c for i, c in enumerate(cells)])
    print(f"grid {gid}: profile {a.profile}, {a.method}, {len(cells)} cells, "
          f"{min(t - b for t, b, *_ in cells):.4g} to {max(t - b for t, b, *_ in cells):.4g} m")


def grid_list(a):
    with sqlite3.connect(a.case) as con:
        for r in rows(con, "grids", GRIDS[0]):
            print(dict(zip(GRIDS[0], r)))


def snow(a):
    with sqlite3.connect(a.case) as con:
        if con.execute("SELECT 1 FROM initial_condition WHERE ic_id = ?", (a.ic,)).fetchone() is None:
            raise SystemExit(f"no initial condition {a.ic}")
        other = lambda r: r[0] != a.ic  # noqa: E731
        if a.none:
            rewrite(con, "snow_initial", SNOW, other, [])
            rewrite(con, "snow_initial_layers", SNOW_LAYERS, other, [])
            return
        layers = []
        if a.layers:
            with open(a.layers) as f:
                for i, r in enumerate(csv.DictReader(f)):
                    layers.append((a.ic, i + 1, float(r["thickness"]), float(r["density"]),
                                   float(r["temperature"]) + MELTING, float(r.get("liquid") or 0.0)))
            bulk = (a.ic, None, None, None, a.age)
        else:
            bulk = (a.ic, a.swe, a.density, a.temperature + MELTING, a.age)
        rewrite(con, "snow_initial", SNOW, other, [bulk])
        rewrite(con, "snow_initial_layers", SNOW_LAYERS, other, layers)
    print(f"initial condition {a.ic}: " + (f"{len(layers)} snow layers" if layers else f"{a.swe} kg m-2 of snow"))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="what", required=True)
    g = sub.add_parser("grid").add_subparsers(dest="action", required=True)
    add = g.add_parser("add")
    add.add_argument("case")
    add.add_argument("--profile", type=int, required=True)
    add.add_argument("--method", choices=("classical", "exponential", "mixed"), required=True)
    add.add_argument("--dz-min", type=float, default=0.0)
    add.add_argument("--growth", type=float, default=0.0)
    add.add_argument("--dz-max", type=float, default=0.0)
    add.add_argument("--note", default="")
    add.set_defaults(run=grid_add)
    ls = g.add_parser("list")
    ls.add_argument("case")
    ls.set_defaults(run=grid_list)
    s = sub.add_parser("snow")
    s.add_argument("case")
    s.add_argument("--ic", type=int, required=True)
    s.add_argument("--swe", type=float)
    s.add_argument("--density", type=float, default=200.0)
    s.add_argument("--temperature", type=float, default=-3.0, help="[degC]")
    s.add_argument("--age", type=float, default=0.0, help="[d]")
    s.add_argument("--layers", help="CSV: thickness [m], density [kg m-3], temperature [degC], liquid [kg m-2]")
    s.add_argument("--none", action="store_true", help="remove the initial snow")
    s.set_defaults(run=snow)
    a = ap.parse_args(argv)
    if a.what == "snow" and not (a.none or a.layers or a.swe is not None):
        ap.error("snow: give --swe, --layers or --none")
    a.run(a)


if __name__ == "__main__":
    main()
