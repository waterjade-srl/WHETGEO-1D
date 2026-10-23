#!/usr/bin/env python3
"""Snow depth of the Friuli case: the porting (recommended configuration) against the observations,
GEOtop 3.0 (flat and with topography) and the MobyGIS-GEOtop run, per station.

Used by friuli_comparison.ipynb; also runnable as a script:

    python3 compare.py [geotop3_folder] [mobygis_output_tabs]

geotop3_folder: the gt3/ folder written by converti.py and run.sh of the case folder
(casi/Friuli/gt3, outside this repository), with piano/ and topografia/ inside; mobygis_output_tabs:
output_tabs/ of pysnow/tests/gt_sim1D. Missing folders are skipped. Environment variables
FRIULI_GT3 and FRIULI_MOBYGIS do the same.
"""
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
STATIONS = {1: "Chiansaveit", 2: "Sauris di sopra", 3: "Sauris di sotto", 4: "Casera Pieltinis"}
FOLDERS = {1: "1_Chiansaveit", 2: "2_Sauris_di_sopra", 3: "3_Sauris_di_sotto", 4: "4_Casera_Pieltinis"}
DEFAULT_GT3 = os.path.expanduser("~/WORK/COMMESSE/2026_RigonianLab/work/geo2geo/casi/Friuli/gt3")
DEFAULT_MOBYGIS = os.path.expanduser("~/git/geotop_moby/pysnow/tests/gt_sim1D/output_tabs")
SNOW = 0.05  # snow on the ground: daily mean HS at least 5 cm [m]


def read_oms(path):
    """OMS CSV time series: one column per station ID, -9999 as missing."""
    with open(path) as f:
        lines = f.readlines()
    ids = lines[4].strip().split(",")[2:]
    rows = [l.strip().split(",") for l in lines[7:] if l.strip()]
    index = pd.to_datetime([r[1] for r in rows])
    data = np.array([[float(v) for v in r[2:]] for r in rows])
    df = pd.DataFrame(data, index=index, columns=[int(i) for i in ids])
    return df.where(df != -9999.0)


def read_point(path):
    """GEOtop point file or the porting's point.csv: snow depth [m] by UTC date (end of step)."""
    df = pd.read_csv(path, comment="#")
    t = pd.to_datetime(df.iloc[:, 0], format="%d/%m/%Y %H:%M")
    hs = df["snow_depth[mm]"].where(df["snow_depth[mm]"] != -9999.0) / 1000.0
    swe = df["snow_water_equivalent[mm]"].where(df["snow_water_equivalent[mm]"] != -9999.0)
    return pd.DataFrame({"HS": hs.values, "SWE": swe.values}, index=t)


def load(gt3=None, mobygis=None):
    """Snow depth [m] hourly, a column per run, a frame per station; SWE [mm] of the models."""
    gt3 = gt3 or os.environ.get("FRIULI_GT3", DEFAULT_GT3)
    mobygis = mobygis or os.environ.get("FRIULI_MOBYGIS", DEFAULT_MOBYGIS)
    obs = read_oms(os.path.join(HERE, "data", "observed_snow_depth.csv"))
    hs, swe = {}, {}
    for s in STATIONS:
        h = {"observed": obs[s]}
        w = {}
        runs = {"porting": os.path.join(HERE, "output", "station_%d" % s, "point.csv"),
                "porting, unstable only": os.path.join(HERE, "output", "station_%d_unstable_only" % s,
                                                       "point.csv"),
                "porting, no shortwave penetration": os.path.join(HERE, "output", "station_%d_sw_none" % s,
                                                                  "point.csv"),
                "GEOtop 3.0 flat": os.path.join(gt3, "piano", FOLDERS[s], "output-tabs", "point0001.txt"),
                "GEOtop 3.0 topography": os.path.join(gt3, "topografia", FOLDERS[s], "output-tabs",
                                                      "point0001.txt"),
                "MobyGIS": os.path.join(mobygis, "point%04d.txt" % s)}
        for name, path in runs.items():
            if os.path.exists(path):
                p = read_point(path)
                h[name] = p.HS
                w[name] = p.SWE
        hs[s] = pd.DataFrame(h).loc["2025-11-01 01:00":"2026-05-01 00:00"]
        swe[s] = pd.DataFrame(w).loc["2025-11-01 01:00":"2026-05-01 00:00"]
    return hs, swe


def dates(daily):
    """Onset: first day with HS >= 5 cm; melt-out: the day after the last one."""
    on = daily.index[daily >= SNOW]
    if len(on) == 0:
        return pd.NaT, pd.NaT
    return on[0], on[-1] + pd.Timedelta(days=1)


def metrics(hs):
    """Per run against the observation, on daily means: bias, RMSE [m], max HS [m], onset, melt-out."""
    daily = hs.resample("D").mean()
    rows = []
    for name in daily.columns:
        d = daily[name]
        both = daily[["observed", name]].dropna()
        err = both[name] - both["observed"]
        onset, meltout = dates(d.dropna())
        rows.append({"run": name, "bias [m]": err.mean() if name != "observed" else np.nan,
                     "RMSE [m]": np.sqrt((err ** 2).mean()) if name != "observed" else np.nan,
                     "max HS [m]": d.max(), "date of max": d.idxmax().date(),
                     "onset": onset.date() if pd.notna(onset) else None,
                     "melt-out": meltout.date() if pd.notna(meltout) else None,
                     "days with snow": int((d >= SNOW).sum())})
    return pd.DataFrame(rows).set_index("run")


def main():
    hs, swe = load(*(sys.argv[1:3] if len(sys.argv) > 2 else ()))
    pd.set_option("display.width", 200)
    for s, name in STATIONS.items():
        print("\n%d %s" % (s, name))
        print(metrics(hs[s]).round(3).to_string())
        print("max SWE [mm]: " + ", ".join("%s %.0f" % (k, v) for k, v in swe[s].max().items()))


if __name__ == "__main__":
    main()
