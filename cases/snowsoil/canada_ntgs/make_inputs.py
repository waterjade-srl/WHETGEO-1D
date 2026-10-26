#!/usr/bin/env python3
"""Inputs of the Canadian case (NTGS1, NTGS2 x ERA5, JRA-3Q) from the GEOtop 3.0 runs.

The reference is GEOtop 3.0 without canopy, corrected:
    <sims>/<site>_<forcing>_senza_chioma_corretta/
These runs are written by build.py and run.sh of casi/Canada_NTGS in the GEO2GEO workspace.

For each of the four cases this writes geotop/<site>_<forcing>/ with the following files.

- GEOtop's own inputs:
  - meteo0001.txt.gz: meteo/meteo0001.txt.old gzipped. It is the original; GEOtop rewrites meteo0001.txt.
  - soil0001.txt, horizon0001.txt, geotop.inpts: copied as they are.
- forcing.properties: the settings of GeotopForcingFiles. run.sh turns them into the OMS forcing in data/<case>/.
- reference.csv.gz: GEOtop's hourly results used by the comparison and by the bare-soil fallback.
  - From point0001.txt: Tsurface, Evap_surface, snow depth, SWE, lowest and highest thawed depth.
  - From soilTz0001.txt: soil temperature at 0.1, 0.5, 1, 2 and 4 m.
  - Dates are the ends of the hours, UTC, in GEOtop's format.
- grid/: the soil as WHETGEO-1D CSV inputs. It has the 20 layers of soil0001.txt in eta, one parameter set per
  layer, and the initial psi (InitPres) and T (InitTemp) at the layer centres.

Usage: python3 make_inputs.py [sims_folder]    (default: $CANADA_SIMS)
"""
import gzip
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SITES = {
    # latitude, longitude, elevation [m], slope [deg], aspect [deg] (siti.py of the GEOtop project)
    "NTGS1": (68.538402, -133.773038, 96.0, 30.0, 0.0),
    "NTGS2": (68.537861, -133.762289, 107.0, 35.0, 0.0),
}
FORCINGS = ("ERA5", "JR3QG")
DEPTHS_MM = (100, 500, 1000, 2000, 4000)
POINT_COLUMNS = ("Tsurface[C]", "Evap_surface[mm]", "snow_depth[mm]", "snow_water_equivalent[mm]",
                 "lowest_thawed_soil_depth[mm]", "highest_thawed_soil_depth[mm]")
# GEOtop's default specific storativity, 1e-7 mm-1 (parameters.cc:1663), as WHETGEO's alpha_SS = S_s / (rho g)
ALPHA_SS = 1e-7 * 1000.0 / (1000.0 * 9.81)
# hydraulic conductivity: irrelevant with WaterBalance = 0 / soilWaterMode Fixed
KS = 1e-5


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
    with gzip.open(os.path.join(out, "reference.csv.gz"), "wt") as f:
        f.write("Date12[DDMMYYYYhhmm]," + ",".join(POINT_COLUMNS) + ","
                + ",".join("T%d[C]" % d for d in DEPTHS_MM) + "\n")
        for r in rows:
            t = soil.get(r[0])
            if t is None:
                continue
            f.write(r[0] + "," + ",".join(r[i] for i in idx) + "," + ",".join(t) + "\n")


def grid(src, out):
    header, rows = read_table(os.path.join(src, "soil", "soil0001.txt"))
    col = {h: i for i, h in enumerate(header)}
    os.makedirs(out, exist_ok=True)
    tops = [0.0]
    for r in rows:
        tops.append(tops[-1] - float(r[col["Dz"]]) / 1000.0)
    with open(os.path.join(out, "grid_geometry.csv"), "w") as f:
        f.write("Type,eta,K,equationStateID,parameterID\n")
        for i, r in enumerate(rows):
            f.write("L,%.6f,1,0,%d\n" % (tops[i], i + 1))
        f.write("L,%.6f,nan,nan,nan\n" % tops[-1])
    with open(os.path.join(out, "initial_condition.csv"), "w") as f:
        f.write("eta,psi0,t0\n")
        for i, r in enumerate(rows):
            centre = 0.5 * (tops[i] + tops[i + 1])
            f.write("%.6f,%.6f,%.6f\n" % (centre, float(r[col["InitPres"]]) / 1000.0,
                                          float(r[col["InitTemp"]]) + 273.15))
    with open(os.path.join(out, "swrc_parameter_types.csv"), "w") as f:
        f.write("val1,val2\nn,par1\nalpha,par2\n")
    with open(os.path.join(out, "swrc_parameters.csv"), "w") as f:
        f.write("spDensity,spConductivity,spSpecificHeatCapacity,thetaS,thetaR,meltingT,alpha,n,"
                "alphaSpecificStorage,betaSpecificStorage,Ks\n")
        f.write("# soil0001.txt of GEOtop in SI, one set per layer from the top: a [mm-1] x 1000, solids rho c ="
                " thermcap as 2200 kg m-3 x 1000 J kg-1 K-1, S_s = GEOtop's default 1e-7 mm-1, Ks irrelevant\n")
        for r in rows:
            f.write("%.6g,%s,1000,%s,%s,273.15,%.6g,%s,%.10g,0.0,%g\n" % (
                float(r[col["thermcap"]]) / 1000.0, r[col["thermcond"]], r[col["sat"]], r[col["res"]],
                float(r[col["a"]]) * 1000.0, r[col["n"]], ALPHA_SS, KS))


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
stationID = 1
outputFolder = ../../data/%s
# fallback from GEOtop's own output: Tsurface without snow (no bare-soil energy balance in the column)
geotopPointFile = reference.csv
""" % (lat, lon, z, slope, aspect, os.path.basename(out)))


def main():
    sims = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("CANADA_SIMS")
    if not sims:
        sys.exit("usage: make_inputs.py <folder of the GEOtop runs>  (or set CANADA_SIMS)")
    for case in cases():
        site = case.split("_")[0]
        src = os.path.join(sims, case + "_senza_chioma_corretta")
        out = os.path.join(HERE, "geotop", case)
        os.makedirs(out, exist_ok=True)
        with open(os.path.join(src, "meteo", "meteo0001.txt.old"), "rb") as fin, \
                gzip.open(os.path.join(out, "meteo0001.txt.gz"), "wb") as fout:
            shutil.copyfileobj(fin, fout)
        shutil.copy(os.path.join(src, "soil", "soil0001.txt"), out)
        shutil.copy(os.path.join(src, "horizon", "horizon0001.txt"), out)
        shutil.copy(os.path.join(src, "geotop.inpts"), out)
        reference(src, out)
        grid(src, os.path.join(HERE, "grid", case))
        properties(site, out)
        print(case, "written")


if __name__ == "__main__":
    main()
