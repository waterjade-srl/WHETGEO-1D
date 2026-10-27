#!/usr/bin/env python3
"""Inputs of the Canadian case (NTGS1, NTGS2 x ERA5, JRA-3Q) from the GEOtop 3.0 runs.

The reference is GEOtop 3.0 without canopy, corrected:
    <sims>/<site>_<forcing>/   (canada/ of geotop_runs.tar.gz, shared with the case data; README.md)
or, in the project's own folders, <sims>/<site>_<forcing>_senza_chioma_corretta/.

For each of the four cases this writes geotop/<site>_<forcing>/ with the following files.

- GEOtop's own inputs:
  - meteo0001.txt.gz: meteo/meteo0001.txt.old gzipped. It is the original; GEOtop rewrites meteo0001.txt.
  - soil0001.txt, horizon0001.txt, geotop.inpts: copied as they are.
- forcing.properties: the settings of GeotopForcingFiles. run.sh turns them into the OMS forcing in data/<case>/.
- reference.csv.gz: GEOtop's hourly results used by the comparison and by the bare-soil fallback.
  - From point0001.txt: Tsurface, Evap_surface, snow depth, SWE, lowest and highest thawed depth.
  - From soilTz0001.txt: soil temperature at 0.1, 0.5, 1, 2 and 4 m.
  - Dates are the ends of the hours, UTC, in GEOtop's format.
- once for the four, data/canada_ntgs.gpkg: the case GeoPackage (CaseGeoPackage, tools/geotop2case) of the four runs: two points,
  NTGS1 and NTGS2, and a column per run, with its forcing set (ERA5 or JR3QG) in columns.forcing. Columns 1-4
  are NTGS1_ERA5, NTGS1_JR3QG, NTGS2_ERA5, NTGS2_JR3QG. It describes the sites as GEOtop's runs saw them: the
  slope and the flat horizon of the runs, and their sky view factor, 1 (none given; --svf-of-run).

Usage: python3 make_inputs.py [sims_folder]    (default: $CANADA_SIMS)
"""
import gzip
import io
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SITES = {
    # latitude, longitude, elevation [m], slope [deg], aspect [deg] (siti.py of the GEOtop project)
    "NTGS1": (68.538402, -133.773038, 96.0, 30.0, 0.0),
    "NTGS2": (68.537861, -133.762289, 107.0, 35.0, 0.0),
}
FORCINGS = ("ERA5", "JR3QG")
# point of each site in the case GeoPackage, and station ID of its forcing series
POINT_ID = {"NTGS1": 1, "NTGS2": 2}
REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
DEPTHS_MM = (100, 500, 1000, 2000, 4000)
POINT_COLUMNS = ("Tsurface[C]", "Evap_surface[mm]", "snow_depth[mm]", "snow_water_equivalent[mm]",
                 "lowest_thawed_soil_depth[mm]", "highest_thawed_soil_depth[mm]")


def gzip_writer(path, text=False):
    """A gzip file without the time of writing in its header, so that the same input gives the same bytes."""
    f = gzip.GzipFile(path, "wb", mtime=0)
    return io.TextIOWrapper(f) if text else f


def cases():
    return ["%s_%s" % (s, f) for s in SITES for f in FORCINGS]


def read_table(path):
    with open(path) as f:
        lines = [l.strip() for l in f if l.strip()]
    header = [h.strip() for h in lines[0].split(",")]
    return header, [[v.strip() for v in l.split(",")] for l in lines[1:]]


def reference(src, out):
    header, rows = read_table(os.path.join(src, "output-tabs", "point0001.txt"))
    idx = [header.index(c) for c in POINT_COLUMNS]
    theader, trows = read_table(os.path.join(src, "output-tabs", "soilTz0001.txt"))
    tidx = [theader.index("%f" % d) for d in DEPTHS_MM]
    soil = {r[0]: [r[i] for i in tidx] for r in trows}
    with gzip_writer(os.path.join(out, "reference.csv.gz"), text=True) as f:
        f.write("Date12[DDMMYYYYhhmm]," + ",".join(POINT_COLUMNS) + ","
                + ",".join("T%d[C]" % d for d in DEPTHS_MM) + "\n")
        for r in rows:
            t = soil.get(r[0])
            if t is None:
                continue
            f.write(r[0] + "," + ",".join(r[i] for i in idx) + "," + ",".join(t) + "\n")


def properties(site, out):
    lat, lon, z, slope, aspect = SITES[site]
    with open(os.path.join(out, "forcing.properties"), "w") as f:
        f.write("""# Forcing of the fidelity run, written by make_inputs.py; read by GeotopForcingFiles (run.sh).
# Values from geotop.inpts of the GEOtop 3.0 run without canopy, corrected.
meteoFile = meteo0001.txt
dateHeader = Date
# no JDfrom0 column: GEOtop dates the records by the Date column
julianDayHeader =
precipitationHeader = IPrec
windSpeedHeader = WindVelocity
relativeHumidityHeader = RH
airTemperatureHeader = AirTemp
shortwaveGlobalHeader = SWglobal
# HeaderLWin: the reanalysis longwave, used as it is
longwaveInHeader = LWin
stationHorizonFile = horizon0001.txt
pointHorizonFile = horizon0001.txt
latitude = %s
longitude = %s
# StandardTimeSimulation = 0 (UTC)
standardTime = 0
elevation = %s
slope = %s
aspect = %s
# no PointSkyViewFactor and no DEM: GEOtop uses 1
skyViewFactor = 1
# ThresTempRain, ThresTempSnow, RainCorrFactor, SnowCorrFactor
rainThreshold = 1
snowThreshold = -3
rainCorrection = 1
snowCorrection = 1
# LWinParameterization not set (Dilley, GEOtop's default); only where LWin has no value
clearSkyEmissivity = DILLEY_OBRIEN_1998
# 01/09/2020 00:00 - 01/09/2023 00:00 UTC, TimeStepEnergyAndWater = 3600
firstStepEnd = 2020-09-01 01:00
lastStepEnd = 2023-09-01 00:00
timeStep = 3600
stationID = %d
# rain and snow per unit horizontal area: the column counts them on the slope of the case
precipitationOnSlope = false
outputFolder = ../../data/%s
# fallback from GEOtop's own output: Tsurface without snow (no bare-soil energy balance in the column)
geotopPointFile = reference.csv
""" % (lat, lon, z, slope, aspect, POINT_ID[site], os.path.basename(out)))


def run_folder(sims, case):
    """The GEOtop run of a case: <sims>/<case>, or <sims>/<case>_senza_chioma_corretta."""
    for name in (case, case + "_senza_chioma_corretta"):
        if os.path.isfile(os.path.join(sims, name, "geotop.inpts")):
            return os.path.join(sims, name)
    sys.exit(f"no GEOtop run of {case} in {sims}")


def main():
    sims = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("CANADA_SIMS")
    if not sims:
        sys.exit("usage: make_inputs.py <folder of the GEOtop runs>  (or set CANADA_SIMS)")
    for case in cases():
        site = case.split("_")[0]
        src = run_folder(sims, case)
        out = os.path.join(HERE, "geotop", case)
        os.makedirs(out, exist_ok=True)
        with open(os.path.join(src, "meteo", "meteo0001.txt.old"), "rb") as fin, \
                gzip_writer(os.path.join(out, "meteo0001.txt.gz")) as fout:
            shutil.copyfileobj(fin, fout)
        shutil.copy(os.path.join(src, "soil", "soil0001.txt"), out)
        shutil.copy(os.path.join(src, "horizon", "horizon0001.txt"), out)
        shutil.copy(os.path.join(src, "geotop.inpts"), out)
        reference(src, out)
        properties(site, out)
        print(case, "written")
    runs = [run_folder(sims, c) for c in cases()]
    os.makedirs(os.path.join(HERE, "data"), exist_ok=True)
    subprocess.run([sys.executable, os.path.join(REPO, "tools", "geotop2case", "geotop2case.py"),
                    os.path.join(HERE, "data", "canada_ntgs.gpkg")] + runs
                   + ["--epsg", "32608", "--names", ",".join(SITES),
                      "--forcing", ",".join(f for _ in SITES for f in FORCINGS), "--svf-of-run",
                      "--case", "NTGS1 and NTGS2 boreholes near Inuvik (NWT), GEOtop 3.0 without canopy, corrected"],
                   check=True)


if __name__ == "__main__":
    main()
