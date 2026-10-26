#!/usr/bin/env python3
"""Writes the static inputs of the Friuli case as one GeoPackage, data/friuli.gpkg, for 1D runs
(snowsoil_friuli_terrain.sim, -Dcolumn=n). The time series stay in data/*.csv (OMS tables).

Layers and tables (SI units, eta negative in the soil):

- points (POINT, EPSG:3035): id, name, elevation [m], lat, lon [deg], slope, aspect [deg, from north
  clockwise] of the 250 m map cell, svf [-] computed from the horizon and the slope (Dozier and Frew 1990),
  svf_map [-] the sky view factor of the source map (kept for traceability, not used: see below),
  site_type ('stake'), wind_height and temperature_height [m] of the sensors;
- horizon (attributes): point_id, azimuth [deg, from north clockwise], height [deg]; 16 directions, every
  22.5 deg, interpolated from the 72 of the source;
- soil_layers, soil_parameters, soil_parameter_types, initial_condition (attributes): the WHETGEO-1D soil
  inputs (the columns of the CSV files of grid/) with a profile_id, or an ic_id for the initial condition;
- columns (attributes): id, point_id, profile_id, ic_id: one row per 1D run;
- provenance (attributes): key, value.

The sky view factor of the source map (0.78-0.87) is not consistent with the point horizons: from the
same horizons it is 0.85-0.98. The column takes the one computed from the horizon.

Usage: python3 make_case.py [source_folder]
       default source: ~/git/geotop_moby/pysnow/tests/gt_sim1D
"""
import csv
import datetime
import hashlib
import os
import sqlite3
import sys

import geopandas as gpd
import numpy as np
from shapely.geometry import Point

from make_topography import HERE, STATIONS, geometry, periodic, sky_view_factor

GPKG = os.path.join(HERE, "data", "friuli.gpkg")
NAMES = {1: "Chiansaveit", 2: "Sauris di sopra", 3: "Sauris di sotto", 4: "Casera Pieltinis"}
DIRECTIONS = np.arange(0.0, 360.0, 22.5)


def read_csv(path):
    with open(path) as f:
        rows = [r for r in csv.reader(f) if r and not r[0].startswith("#")]
    return rows[0], rows[1:]


def attributes(con, name, header, rows, types, description):
    """A GeoPackage attributes table (registered in gpkg_contents)."""
    cols = ", ".join(f'"{h}" {t}' for h, t in zip(header, types))
    con.execute(f'DROP TABLE IF EXISTS "{name}"')
    con.execute(f'CREATE TABLE "{name}" (fid INTEGER PRIMARY KEY AUTOINCREMENT, {cols})')
    con.executemany(f'INSERT INTO "{name}" ({", ".join(chr(34) + h + chr(34) for h in header)}) '
                    f'VALUES ({", ".join("?" * len(header))})', rows)
    con.execute("DELETE FROM gpkg_contents WHERE table_name = ?", (name,))
    con.execute("INSERT INTO gpkg_contents (table_name, data_type, identifier, description, last_change) "
                "VALUES (?, 'attributes', ?, ?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))", (name, name, description))


def number(v):
    return None if v.strip().lower() == "nan" else float(v)


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/git/geotop_moby/pysnow/tests/gt_sim1D")
    geo = geometry(source)
    with open(os.path.join(source, "ListPoints.txt")) as f:
        xy = {int(float(r["st_id"])): (float(r["x"]), float(r["y"])) for r in csv.DictReader(f)}
    points, horizon = [], []
    for s, (lat, lon, z) in STATIONS.items():
        slope, aspect, svf_map = geo[s]
        with open(os.path.join(source, "horizon_point", f"horizon{s:04d}.txt")) as f:
            rows = list(csv.DictReader(f))
        h16 = periodic(np.array([float(r["azimuth"]) for r in rows]),
                       np.array([float(r["horizon_height"]) for r in rows]), DIRECTIONS)
        horizon += [(s, float(a), round(float(h), 4)) for a, h in zip(DIRECTIONS, h16)]
        points.append({"id": s, "name": NAMES[s], "elevation": z, "lat": lat, "lon": lon, "slope": slope,
                       "aspect": aspect, "svf": round(sky_view_factor(DIRECTIONS, h16, slope, aspect), 4),
                       "svf_map": round(svf_map, 4), "site_type": "stake", "wind_height": 5.0,
                       "temperature_height": 2.0, "geometry": Point(*xy[s])})
    if os.path.exists(GPKG):
        os.remove(GPKG)
    gpd.GeoDataFrame(points, crs="EPSG:3035").to_file(GPKG, layer="points", driver="GPKG")

    grid = os.path.join(HERE, "grid")
    with sqlite3.connect(GPKG) as con:
        attributes(con, "horizon", ["point_id", "azimuth", "height"], horizon, ["INTEGER", "REAL", "REAL"],
                   "horizon of each point, 16 directions [deg]")
        h, rows = read_csv(os.path.join(grid, "grid_geometry.csv"))
        attributes(con, "soil_layers", ["profile_id"] + h,
                   [[1, r[0]] + [number(v) for v in r[1:]] for r in rows],
                   ["INTEGER", "TEXT", "REAL", "INTEGER", "INTEGER", "INTEGER"],
                   "WHETGEO-1D grid_geometry per soil profile")
        h, rows = read_csv(os.path.join(grid, "swrc_parameters.csv"))
        attributes(con, "soil_parameters", ["profile_id", "parameter_id"] + h,
                   [[1, k + 1] + [float(v) for v in r] for k, r in enumerate(rows)],
                   ["INTEGER", "INTEGER"] + ["REAL"] * len(h), "WHETGEO-1D swrc_parameters per soil profile")
        h, rows = read_csv(os.path.join(grid, "swrc_parameter_types.csv"))
        attributes(con, "soil_parameter_types", h, rows, ["TEXT", "TEXT"], "WHETGEO-1D swrc_parameter_types")
        h, rows = read_csv(os.path.join(grid, "initial_condition.csv"))
        attributes(con, "initial_condition", ["ic_id"] + h, [[1] + [float(v) for v in r] for r in rows],
                   ["INTEGER", "REAL", "REAL", "REAL"], "WHETGEO-1D initial_condition")
        attributes(con, "columns", ["id", "point_id", "profile_id", "ic_id", "note"],
                   [(s, s, 1, 1, NAMES[s]) for s in STATIONS], ["INTEGER", "INTEGER", "INTEGER", "INTEGER", "TEXT"],
                   "one row per 1D run")
        sources = [os.path.join(source, "ListPoints.txt")] + [
            os.path.join(source, "horizon_point", f"horizon{s:04d}.txt") for s in STATIONS] + [
            os.path.join(source, "input_maps", f"{n}_250m3035.asc") for n in ("slp", "asp", "svf")]
        prov = [("schema", "geo2geo 1D case, version 1"), ("case", "Friuli, 4 stations of the FVG network"),
                ("source", "MobyGIS-GEOtop point run pysnow/tests/gt_sim1D"),
                ("written", datetime.date.today().isoformat()), ("script", "cases/snowsoil/friuli/make_case.py"),
                ("svf", "computed from the 16-direction horizon and the slope (Dozier and Frew 1990); svf_map kept")]
        prov += [("sha256:" + os.path.basename(p), hashlib.sha256(open(p, "rb").read()).hexdigest()) for p in sources]
        attributes(con, "provenance", ["key", "value"], prov, ["TEXT", "TEXT"], "where the data come from")
    print(gpd.read_file(GPKG, layer="points").drop(columns="geometry").to_string(index=False))


if __name__ == "__main__":
    main()
