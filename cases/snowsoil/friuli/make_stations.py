#!/usr/bin/env python3
"""Writes the geometry of the Friuli stations as GEOframe inputs, for snowsoil_friuli_terrain.sim:

- data/stations.shp: one point per station (EPSG:3035, the coordinates of the source's ListPoints.txt), with
  id, elevation [m], lat and lon [deg], and slope, aspect [deg from north, clockwise] and svf [-] of the
  250 m map cell of the station;
- data/horizon.csv: an OMS table, a row per azimuth [deg] and a column value_<id> per station with the height
  of the horizon [deg] (NivoGEO-1D's HorizonReader format), from the point horizons of the source.

The source is the MobyGIS-GEOtop point run `pysnow/tests/gt_sim1D` (repository geotop_moby), as for
make_inputs.py; slope, aspect and svf are sampled as in make_topography.py.

Usage: python3 make_stations.py [source_folder]
       default source: ~/git/geotop_moby/pysnow/tests/gt_sim1D
"""
import csv
import os
import sys

import geopandas as gpd
from shapely.geometry import Point

from make_topography import HERE, STATIONS, geometry


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/git/geotop_moby/pysnow/tests/gt_sim1D")
    geo = geometry(source)
    with open(os.path.join(source, "ListPoints.txt")) as f:
        points = {int(float(r["st_id"])): (float(r["x"]), float(r["y"])) for r in csv.DictReader(f)}
    rows = []
    for s, (lat, lon, z) in STATIONS.items():
        slope, aspect, sky = geo[s]
        rows.append({"id": s, "elevation": z, "lat": lat, "lon": lon, "slope": slope, "aspect": aspect,
                     "svf": round(sky, 4), "geometry": Point(*points[s])})
    gpd.GeoDataFrame(rows, crs="EPSG:3035").to_file(os.path.join(HERE, "data", "stations.shp"))

    horizons = {}
    for s in STATIONS:
        with open(os.path.join(source, "horizon_point", f"horizon{s:04d}.txt")) as f:
            horizons[s] = {float(r["azimuth"]): float(r["horizon_height"]) for r in csv.DictReader(f)}
    azimuths = sorted(horizons[1])
    if any(sorted(h) != azimuths for h in horizons.values()):
        sys.exit("the stations have different azimuths")
    with open(os.path.join(HERE, "data", "horizon.csv"), "w") as f:
        f.write("@T,table\nCreated,make_stations.py\nAuthor,cases/snowsoil/friuli\n")
        f.write("@H,azimuth," + ",".join(f"value_{s}" for s in STATIONS) + "\n")
        f.write("Type,double" + ",double" * len(STATIONS) + "\nFormat," + "," * len(STATIONS) + "\n")
        for a in azimuths:
            f.write(f",{a:g}," + ",".join(f"{horizons[s][a]:.4f}" for s in STATIONS) + "\n")
    print(gpd.GeoDataFrame(rows).drop(columns="geometry").to_string(index=False))


if __name__ == "__main__":
    main()
