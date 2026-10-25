#!/usr/bin/env python3
"""Inputs of the ARF_1D snow regression from GEOtop 3.0's official 1D test ARF_1D.

ARF_1D (Anaktuvuk River Fire, Alaska North Slope, 68.93 N, -150.27 E, 760 m) has 50 soil layers to 9.7 m, Richards,
permafrost, and a repeated climatological NARR year as forcing (meteoNARRhourlyDynamicRepeatedWithSpinUp). The
regression runs one snow season of it, 1 September 2001 - 15 June 2002 (the snow is gone on 30 May; the summer bare soil, without evaporation, is left out), with frozen inputs: a numerical regression of
the porting against its own earlier outputs (VAL-4), neither a validation nor a fidelity reference.

This writes, from <geotop>/tests/1D/ARF_1D:
- geotop/meteo0001.txt.gz: the records of the meteo file from 15/08/2001 to 05/09/2002 (local standard time, UTC-9), the summer for the GEOET run;
- geotop/horizon0001.txt: a flat horizon (the test has none);
- grid/: the 50 layers of soil/SoilARF0001.txt as WHETGEO-1D CSV inputs, one parameter set per layer, initial
  T = InitSoilTemp (-4.8 degC) and psi = 0 (InitSoilPressure) everywhere.

Usage: python3 make_inputs.py [geotop_repository]    (default: $GEOTOP_REPO, or ~/wj-git/geotop)
"""
import gzip
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIRST = (2001, 8, 15)
LAST = (2002, 9, 5)
# GEOtop's default specific storativity, as WHETGEO's alpha_SS = S_s / (rho g); the file gives SS = 1e-7 mm-1
ALPHA_SS = 1e-7 * 1000.0 / (1000.0 * 9.81)
INIT_T = -4.8


def read_table(path):
    with open(path) as f:
        lines = [l.strip() for l in f if l.strip() and not l.lstrip().startswith("!")]
    header = [h.strip() for h in lines[0].split(",")]
    return header, [[v.strip() for v in l.split(",")] for l in lines[1:]]


def meteo(src, out):
    def key(date):
        d, m, rest = date.split("/")
        return (int(rest[:4]), int(m), int(d))
    with open(os.path.join(src, "meteo", "meteoNARRhourlyDynamicRepeatedWithSpinUp0001.txt")) as f, \
            gzip.open(os.path.join(out, "meteo0001.txt.gz"), "wt") as g:
        g.write(f.readline())
        for line in f:
            if FIRST <= key(line.split(",", 1)[0]) <= LAST:
                g.write(line)


def grid(src, out):
    header, rows = read_table(os.path.join(src, "soil", "SoilARF0001.txt"))
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
        for i in range(len(rows)):
            f.write("%.6f,0.0,%.2f\n" % (0.5 * (tops[i] + tops[i + 1]), INIT_T + 273.15))
    with open(os.path.join(out, "swrc_parameter_types.csv"), "w") as f:
        f.write("val1,val2\nn,par1\nalpha,par2\n")
    with open(os.path.join(out, "swrc_parameters.csv"), "w") as f:
        f.write("spDensity,spConductivity,spSpecificHeatCapacity,thetaS,thetaR,meltingT,alpha,n,"
                "alphaSpecificStorage,betaSpecificStorage,Ks\n")
        f.write("# SoilARF0001.txt in SI, one set per layer from the top: VG_alpha [mm-1] x 1000, solids rho c ="
                " thermcap as 1000 J kg-1 K-1 per (thermcap / 1000) kg m-3, Kv [mm s-1] / 1000\n")
        for r in rows:
            f.write("%.6g,%s,1000,%s,%s,273.15,%.6g,%s,%.10g,0.0,%.6g\n" % (
                float(r[col["thermcap"]]) / 1000.0, r[col["thermcond"]], r[col["vwc_s"]], r[col["vwc_r"]],
                float(r[col["VG_alpha"]]) * 1000.0, r[col["VG_n"]], ALPHA_SS, float(r[col["Kv"]]) / 1000.0))


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GEOTOP_REPO", os.path.expanduser("~/wj-git/geotop"))
    src = os.path.join(repo, "tests", "1D", "ARF_1D")
    out = os.path.join(HERE, "geotop")
    os.makedirs(out, exist_ok=True)
    meteo(src, out)
    with open(os.path.join(out, "horizon0001.txt"), "w") as f:
        f.write("AngleFromNorthClockwise,HorizonHeight\n0,0\n90,0\n180,0\n270,0\n")
    grid(src, os.path.join(HERE, "grid"))


if __name__ == "__main__":
    main()
