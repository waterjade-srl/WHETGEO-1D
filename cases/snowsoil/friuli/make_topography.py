#!/usr/bin/env python3
"""Writes the radiation and precipitation of the Friuli stations on their terrain, for the topography runs
(phase 0 of the plan for operational runs: how much slope, aspect, horizon and sky view factor weigh).

The column takes the shortwave on the slope and the incoming longwave as inputs. Here they come from
GEOtop 3.0's own computation, ported in `GeotopForcingFiles` (fidelity package): the clear-sky shortwave
times the cloud transmissivity of the station (`CloudTrans`), split into beam and diffuse with Erbs, the
beam on the slope and hidden by the horizon, the diffuse times the sky view factor V, the longwave from
the sky V eps_a sigma T_a^4, rain and snow per unit area of the slope (P cos(slope)). The column adds the
rest of GEOtop's terrain terms itself (`skyViewFactor`): it emits V eps sigma T_s^4 to the sky, and gets
back (1 - V) of the shortwave it reflected in the previous step.

Variants, as the GEOtop 3.0 runs of the same stations:

    flat        slope 0, no horizon, V = 1 (the same forcing as the flat case, through the same path)
    horizon     horizon and V of the point, slope 0
    shade       horizon of the point only: slope 0, V = 1
    slope       slope and aspect of the point, no horizon, V = 1
    topography  all of them

Slope, aspect and V are those of the 250 m map cell of the station, the horizon that of the point, both
from the MobyGIS-GEOtop point run `pysnow/tests/gt_sim1D` (repository geotop_moby), as in make_inputs.py.

Writes, next to this script:

- data/geometry.csv: id, slope [deg], aspect [deg from north, clockwise], sky view factor [-];
- output/topography/<variant>/station_<id>/: GEOtop's meteo file and point horizon, the properties of
  GeotopForcingFiles, and its OMS series (rainfall, snowfall, shortwaveDirect, shortwaveDiffuse,
  cosIncidence, longwaveIn, ...), which snowsoil_friuli_topography.sim reads.

Usage: python3 make_topography.py <classpath> [source_folder]
       (run_topography.sh passes the classpath of the compiled repository)
"""
import csv
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
STATIONS = {  # id: latitude, longitude, elevation [m], map coordinates (EPSG:3035) of the station
    1: (46.4518, 12.62653, 1710.0),
    2: (46.46722, 12.68198, 1414.0),
    3: (46.4662, 12.71044, 1200.0),
    4: (46.48414, 12.72301, 1742.0),
}
VARIANTS = {  # name: (horizon, sky view factor, slope and aspect)
    "flat": (False, False, False),
    "horizon": (True, True, False),
    "shade": (True, False, False),
    "slope": (False, False, True),
    "topography": (True, True, True),
}
SERIES = ["airTemperature", "relativeHumidity", "windSpeed", "precipitation", "cloudTransmissivity"]
GEOTOP_HEADERS = {"airTemperature": "AirT", "relativeHumidity": "RelHum", "windSpeed": "WindSp",
                  "precipitation": "Iprec", "cloudTransmissivity": "CloudTrans"}
FIRST, LAST = "2025-11-01 01:00", "2026-05-01 00:00"


def read_oms(path):
    """{id: [(date, value), ...]} of an OMS table with one column per station."""
    with open(path) as f:
        rows = list(csv.reader(f))
    ids = next(r for r in rows if r and r[0] == "ID")[2:]
    data = {int(i): [] for i in ids}
    for r in rows:
        if r and r[0] == "" and len(r) > 2:
            for i, v in zip(ids, r[2:]):
                data[int(i)].append((r[1], float(v)))
    return data


def read_grid(path):
    head = {}
    with open(path) as f:
        for _ in range(6):
            k, v = f.readline().split()
            head[k.lower()] = float(v)
        rows = [list(map(float, line.split())) for line in f if line.strip()]
    return head, rows


def sample(grid, x, y):
    head, rows = grid
    c = int((x - head["xllcorner"]) // head["cellsize"])
    r = int(head["nrows"] - 1 - (y - head["yllcorner"]) // head["cellsize"])
    return rows[r][c]


def geometry(source):
    """Slope, aspect and V of the map cell of each station (the points of ListPoints.txt)."""
    maps = {n: read_grid(os.path.join(source, "input_maps", f"{n}_250m3035.asc")) for n in ("slp", "asp", "svf")}
    with open(os.path.join(source, "ListPoints.txt")) as f:
        points = {int(float(r["st_id"])): (float(r["x"]), float(r["y"])) for r in csv.DictReader(f)}
    return {s: tuple(sample(maps[n], *points[s]) for n in ("slp", "asp", "svf")) for s in STATIONS}


def main():
    classpath = sys.argv[1]
    source = sys.argv[2] if len(sys.argv) > 2 else os.path.expanduser("~/git/geotop_moby/pysnow/tests/gt_sim1D")
    geo = geometry(source)
    with open(os.path.join(HERE, "data", "geometry.csv"), "w") as f:
        f.write("id,slope,aspect,skyViewFactor\n")
        for s, (slope, aspect, sky) in geo.items():
            f.write(f"{s},{slope:g},{aspect:g},{sky:.4f}\n")
    series = {n: read_oms(os.path.join(HERE, "data", n + ".csv")) for n in SERIES}
    for variant, (with_horizon, with_sky, with_slope) in VARIANTS.items():
        for s, (lat, lon, z) in STATIONS.items():
            slope, aspect, sky = geo[s]
            folder = os.path.join(HERE, "output", "topography", variant, f"station_{s}")
            os.makedirs(folder, exist_ok=True)
            # GEOtop's meteo file: the OMS records, UTC, each the hour that ends at its date (GEOtop's
            # time_interp_constant); the first record repeated one hour earlier, where the first step begins
            # (GeotopMeteo needs a record there, its value does not enter the step)
            with open(os.path.join(folder, "meteo0001.txt"), "w") as f:
                f.write("Date," + ",".join(GEOTOP_HEADERS[n] for n in SERIES) + "\n")
                columns = [series[n][s] for n in SERIES]
                f.write("01/11/2025 00:00," + ",".join(f"{c[0][1]:.4f}" for c in columns) + "\n")
                for k in range(len(columns[0])):
                    date = columns[0][k][0]
                    y, m, d = date[:10].split("-")
                    f.write(f"{d}/{m}/{y} {date[11:]}," + ",".join(f"{c[k][1]:.4f}" for c in columns) + "\n")
            horizon = "horizon0001.txt"
            with open(os.path.join(folder, horizon), "w") as f:
                if with_horizon:
                    with open(os.path.join(source, "horizon_point", f"horizon{s:04d}.txt")) as h:
                        f.write(h.read())
                else:
                    f.write("azimuth,horizon_height\n0,0\n90,0\n180,0\n270,0\n")
            props = {
                "meteoFile": "meteo0001.txt", "dateHeader": "Date", "julianDayHeader": "",
                # the station horizon only serves a measured shortwave: none here
                "stationHorizonFile": horizon, "pointHorizonFile": horizon,
                "latitude": lat, "longitude": lon, "standardTime": 0, "elevation": z,
                "slope": slope if with_slope else 0.0, "aspect": aspect if with_slope else 0.0,
                "skyViewFactor": sky if with_sky else 1.0,
                "rainThreshold": 2.0, "snowThreshold": 0.5, "rainCorrection": 1.0, "snowCorrection": 1.0,
                "clearSkyEmissivity": "KONZELMANN_1994", "cloudTransmissivityHeader": "CloudTrans",
                "firstStepEnd": FIRST, "lastStepEnd": LAST, "timeStep": 3600, "stationID": s,
                "outputFolder": ".",
            }
            with open(os.path.join(folder, "forcing.properties"), "w") as f:
                f.write(f"# Friuli station {s}, variant {variant}: written by make_topography.py\n")
                for k, v in props.items():
                    f.write(f"{k} = {v}\n")
            run = subprocess.run(["java", "-cp", classpath,
                                  "org.geoframe.whetgeo1d.core.snowsoil.fidelity.GeotopForcingFiles",
                                  os.path.join(folder, "forcing.properties")], capture_output=True, text=True)
            if run.returncode != 0:
                sys.exit(f"GeotopForcingFiles failed on {folder}:\n{run.stderr[-2000:]}")
            print(f"{variant:10s} station {s}: slope {props['slope']:g}, aspect {props['aspect']:g}, "
                  f"V {props['skyViewFactor']:.2f}")


if __name__ == "__main__":
    main()
