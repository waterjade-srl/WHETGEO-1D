#!/usr/bin/env python3
"""Writes the inputs of the Friuli case (VAL-3, phase 2 of the prototype plan) from the SMET files of
the MobyGIS-GEOtop point simulation `pysnow/tests/gt_sim1D` (repository geotop_moby).

Four stations of the Friuli Venezia Giulia network, 1 November 2025 - 1 May 2026, hourly:

    1 Chiansaveit       46.4518 N 12.62653 E 1710 m
    2 Sauris di sopra   46.46722 N 12.68198 E 1414 m
    3 Sauris di sotto   46.4662 N 12.71044 E 1200 m
    4 Casera Pieltinis  46.48414 N 12.72301 E 1742 m

The stations measure air temperature, relative humidity, wind, pressure, precipitation and snow
depth; no radiation. The SMET files carry a cloud transmissivity TAU_CLD, which is what GEOtop
reads as `CloudTrans`: the component then takes GEOtop's clear-sky shortwave times it, and the
incoming longwave from it (LWinParameterization = 7 in the GEOtop runs, Konzelmann 1994).

Writes, next to this script:

- data/<variable>.csv: OMS time series, one column per station (ID 1-4), UTC, each record the
  step ending at its date: airTemperature [degC], relativeHumidity [%], windSpeed [m/s],
  pressure [Pa], precipitation [mm], cloudTransmissivity [-];
- data/observed_snow_depth.csv: the measured snow depth [m], same layout (for the comparison only);
- grid/: the soil of the case (soil/soil0001.txt of the source), WHETGEO-1D CSV inputs: three
  cells of 0.28, 0.50 and 2.00 m as GEOtop's three layers, Van Genuchten alpha = 4 m-1, n = 1.1,
  theta_r = 0, theta_s = 0.40 / 0.35, solids 2.5 W m-1 K-1 and 2.3e6 J m-3 K-1 (2300 kg m-3 x
  1000 J kg-1 K-1); initial temperature 1 degC, water table at 1 m (psi = -1 m at the surface,
  hydrostatic), as GEOtop's InitSoilTemp = 1 and InitWaterTableDepth = 1000 mm.

SMET timestamps are UTC+1 (tz = 1 in the header) and are converted to UTC. The values are copied
as they are (TAU_CLD is clamped to [0, 1] by the component, as by GEOtop).

Usage: python3 make_inputs.py [source_folder]
       default source: ~/git/geotop_moby/pysnow/tests/gt_sim1D
"""
import os
import sys
from datetime import datetime, timedelta

STATIONS = [1, 2, 3, 4]
START = datetime(2025, 11, 1, 1, 0)  # end of the first step, UTC
END = datetime(2026, 5, 1, 0, 0)  # end of the last step
FIELDS = "timestamp HS P PSUM RH TA TAU_CLD VW".split()
SOIL = [  # thickness [m], parameter id
    (0.28, 1),
    (0.50, 2),
    (2.00, 2),
]


def read_smet(path):
    header = {}
    rows = {}
    with open(path) as f:
        for line in f:
            if line.startswith("[DATA]"):
                break
            if "=" in line:
                k, v = line.split("=", 1)
                header[k.strip()] = v.strip()
        fields = header["fields"].split()
        if fields != FIELDS:
            raise SystemExit(f"{path}: unexpected fields {fields}")
        tz = timedelta(hours=float(header["tz"]))
        nodata = float(header["nodata"])
        for line in f:
            parts = line.split()
            if not parts:
                continue
            t = datetime.strptime(parts[0], "%Y-%m-%dT%H:%M:%S") - tz
            values = [float(v) for v in parts[1:]]
            rows[t] = {k: (None if v == nodata else v) for k, v in zip(fields[1:], values)}
    return header, rows


def write_series(path, name, dates, columns):
    with open(path, "w") as f:
        f.write("@T,table\nCreated,%s\nAuthor,make_inputs.py\n" % datetime(2026, 10, 22).strftime("%Y-%m-%d %H:%M"))
        f.write("@H,timestamp," + ",".join("value_%d" % s for s in STATIONS) + "\n")
        f.write("ID,," + ",".join(str(s) for s in STATIONS) + "\n")
        f.write("Type,Date," + ",".join("double" for _ in STATIONS) + "\n")
        f.write("Format,yyyy-MM-dd HH:mm," + "," * (len(STATIONS) - 1) + "\n")
        for k, t in enumerate(dates):
            f.write("," + t.strftime("%Y-%m-%d %H:%M") + "," + ",".join(columns[s][k] for s in STATIONS) + "\n")


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/git/geotop_moby/pysnow/tests/gt_sim1D")
    here = os.path.dirname(os.path.abspath(__file__))
    dates = []
    t = START
    while t <= END:
        dates.append(t)
        t += timedelta(hours=1)
    conversions = {
        "airTemperature": lambda r: r["TA"] - 273.15,
        "relativeHumidity": lambda r: 100.0 * r["RH"],
        "windSpeed": lambda r: r["VW"],
        "pressure": lambda r: r["P"],
        "precipitation": lambda r: r["PSUM"],
        "cloudTransmissivity": lambda r: r["TAU_CLD"],
        "observed_snow_depth": lambda r: r["HS"],
    }
    series = {name: {} for name in conversions}
    for s in STATIONS:
        header, rows = read_smet(os.path.join(source, "meteo", "smet", "%d.smet" % s))
        print("station %d: %s, %s m" % (s, header["station_name"], header["altitude"]))
        for name, conv in conversions.items():
            col = []
            for d in dates:
                r = rows.get(d)
                key = {"airTemperature": "TA", "relativeHumidity": "RH", "windSpeed": "VW", "pressure": "P",
                       "precipitation": "PSUM", "cloudTransmissivity": "TAU_CLD", "observed_snow_depth": "HS"}[name]
                if r is None or r[key] is None:
                    if name != "observed_snow_depth":
                        raise SystemExit("station %d: %s missing at %s" % (s, key, d))
                    col.append("-9999.0")
                else:
                    col.append("%.4f" % conv(r))
            series[name][s] = col
    os.makedirs(os.path.join(here, "data"), exist_ok=True)
    for name, columns in series.items():
        write_series(os.path.join(here, "data", name + ".csv"), name, dates, columns)

    grid = os.path.join(here, "grid")
    os.makedirs(grid, exist_ok=True)
    with open(os.path.join(grid, "grid_geometry.csv"), "w") as f:
        f.write("Type,eta,K,equationStateID,parameterID\n")
        eta = 0.0
        for dz, pid in SOIL:
            f.write("L,%g,1,0,%d\n" % (eta, pid))
            eta -= dz
        f.write("L,%g,nan,nan,nan\n" % eta)
    with open(os.path.join(grid, "initial_condition.csv"), "w") as f:
        f.write("eta,psi0,t0\n")
        f.write("0.0,-1.0,274.15\n")
        f.write("%g,%g,274.15\n" % (eta, -1.0 - eta))
    with open(os.path.join(grid, "swrc_parameters.csv"), "w") as f:
        f.write("spDensity,spConductivity,spSpecificHeatCapacity,thetaS,thetaR,meltingT,alpha,n,"
                "alphaSpecificStorage,betaSpecificStorage,Ks\n")
        f.write("# soil0001.txt of gt_sim1D: a = 0.004 mm-1, n = 1.1, SS = 1e-6 mm-1, Kv = 1e-7 mm/s;"
                " solids of geotop.inpts\n")
        for theta_s in (0.40, 0.35):
            f.write("2300,2.5,1000,%.2f,0.0,273.15,4.0,1.1,%r,0.0,1e-10\n" % (theta_s, 1e-3 / 9810.0))
    with open(os.path.join(grid, "swrc_parameter_types.csv"), "w") as f:
        f.write("val1,val2\nn,par1\nalpha,par2\n")


if __name__ == "__main__":
    main()
