#!/usr/bin/env python3
"""Canadian case: the porting (fidelity, heat only) against GEOtop 3.0 without canopy, corrected.

Reads output/<case>/ of the porting (run.sh) and geotop/<case>/reference.csv.gz (make_inputs.py).
It compares, over 01/09/2021 - 01/09/2023 (the first year, from 01/09/2020, is warm-up as in GEOtop's project):

- HS and SWE: daily means, plus the peak of each winter;
- snow-on and snow-off dates: the longest run of days with daily mean SWE > 1 mm in each hydrological year;
- surface temperature with snow, hourly, where both models have snow at the end of the hour;
- soil temperature at 0.1, 0.5, 1, 2 and 4 m: hourly and daily. GEOtop's soilTz0001.txt
  (SoilAveragedTempProfileFile) holds the mean over the hour, so the porting's soilTzav.csv (mean over the
  record) is compared with it; the end-of-hour soilTz.csv is also reported ("end of hour"), to show the
  effect of the convention;
- thaw depth: GEOtop's lowest_thawed_soil_depth, and the same algorithm (find_activelayerdepth_up,
  tables.cc:33-78) on the porting's profiles; daily, plus the summer maximum.

Used by canada_ntgs_comparison.ipynb; also runnable as a script: python3 compare.py [CASE[:LABEL] ...]
(LABEL: a sensitivity run of run.sh, output/<case>_<label>/)
"""
import gzip
import os
import sqlite3
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
CASES = ("NTGS1_ERA5", "NTGS1_JR3QG", "NTGS2_ERA5", "NTGS2_JR3QG")
START, END = "2021-09-01 01:00", "2023-09-01 00:00"
DEPTHS_MM = (100, 500, 1000, 2000, 4000)
SNOW_SWE = 1.0  # snow on the ground: SWE above 1 mm


def _csv(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt") as f:
        df = pd.read_csv(f, comment="#")
    df.index = pd.to_datetime(df.iloc[:, 0], format="%d/%m/%Y %H:%M")
    return df.where(df != -9999.0)


def grid(case):
    """Thickness [mm] and theta_r of the layers, from the top, from the case GeoPackage (column of the case)."""
    with sqlite3.connect(os.path.join(HERE, "data", "canada_ntgs.gpkg")) as c:
        (profile,) = c.execute("SELECT profile_id FROM columns WHERE id = ?", (CASES.index(case) + 1,)).fetchone()
        layers = c.execute("SELECT eta, parameterID FROM soil_layers WHERE profile_id = ? ORDER BY fid",
                           (profile,)).fetchall()
        theta_r = dict(c.execute("SELECT parameter_id, thetaR FROM soil_parameters WHERE profile_id = ?",
                                 (profile,)).fetchall())
    eta = np.array([e for e, _ in layers])
    return -np.diff(eta) * 1000.0, np.array([theta_r[p] for _, p in layers[:-1]])


def thaw_depth(temperature, ice, liquid, dz, theta_r):
    """GEOtop's find_activelayerdepth_up on profiles from the top (rows: times) [mm]."""
    n = temperature.shape[1]
    out = np.zeros(temperature.shape[0])
    for k in range(temperature.shape[0]):
        t = temperature[k]
        if t[n - 1] >= 0.0:
            out[k] = dz.sum()
            continue
        for j in range(n - 2, -1, -1):
            if t[j] >= 0.0 and t[j + 1] < 0.0:
                frozen = ice[k, j + 1] / (ice[k, j + 1] + liquid[k, j + 1] - theta_r[j + 1])
                out[k] = dz[:j + 1].sum() + (1.0 - frozen) * dz[j + 1]
                break
    return out


def load(case, label=None):
    """Hourly frame with the GEOtop (gt_*) and porting (wg_*) quantities."""
    ref = _csv(os.path.join(HERE, "geotop", case, "reference.csv.gz"))
    out = os.path.join(HERE, "output", case + ("_" + label if label else ""))
    pt = _csv(os.path.join(out, "point.csv"))
    tz = _csv(os.path.join(out, "soilTz.csv"))
    tzav_path = os.path.join(out, "soilTzav.csv")
    tzav = _csv(tzav_path) if os.path.exists(tzav_path) else tz
    ice = _csv(os.path.join(out, "thetaice.csv"))
    liq = _csv(os.path.join(out, "thetaliq.csv"))
    depths = np.array([float(c) for c in tz.columns[2:]])
    t = tz.iloc[:, 2:].values
    d = pd.DataFrame(index=pt.index)
    d["gt_HS"] = ref["snow_depth[mm]"]
    d["wg_HS"] = pt["snow_depth[mm]"]
    d["gt_SWE"] = ref["snow_water_equivalent[mm]"]
    d["wg_SWE"] = pt["snow_water_equivalent[mm]"]
    d["gt_Ts"] = ref["Tsurface[C]"]
    d["wg_Ts"] = pt["Tsurface[C]"]
    tav = tzav.iloc[:, 2:].values
    for z in DEPTHS_MM:
        d["gt_T%d" % z] = ref["T%d[C]" % z]
        d["wg_T%d" % z] = [np.interp(z, depths, row) for row in tav]
        d["wg_Tend%d" % z] = [np.interp(z, depths, row) for row in t]
    dz, theta_r = grid(case)
    d["gt_thaw"] = ref["lowest_thawed_soil_depth[mm]"]
    d["wg_thaw"] = thaw_depth(t, ice.iloc[:, 2:].values, liq.iloc[:, 2:].values, dz, theta_r)
    return d.loc[START:END]


def _stats(a, b):
    e = (a - b).dropna()
    return e.mean(), np.sqrt((e ** 2).mean())


def snow_season(swe):
    """Snow-on and snow-off days of the longest snow-covered run of each hydrological year."""
    daily = swe.resample("D").mean() > SNOW_SWE
    seasons = {}
    for year in sorted(set(daily.index.year)):
        s = daily.loc["%d-09-01" % year:"%d-08-31" % (year + 1)]
        if len(s) < 300:
            continue
        best, run, start = (None, None, 0), 0, None
        for day, on in s.items():
            if on:
                if run == 0:
                    start = day
                run += 1
                if run > best[2]:
                    best = (start, day, run)
            else:
                run = 0
        seasons["%d-%d" % (year, year + 1)] = (best[0], best[1] + pd.Timedelta(days=1) if best[1] is not None else None)
    return seasons


def compare(case, label=None):
    """Rows of the comparison table of a case."""
    d = load(case, label)
    day = d.resample("D").mean()
    rows = []
    for q, unit in (("HS", "mm"), ("SWE", "mm")):
        bias, rmse = _stats(day["wg_" + q], day["gt_" + q])
        rows.append((q + " daily", unit, bias, rmse))
    for season in ("2021-2022", "2022-2023"):
        y0 = int(season[:4])
        w = d.loc["%d-09-01" % y0:"%d-08-31" % (y0 + 1)]
        for q in ("HS", "SWE"):
            rows.append(("%s peak %s (porting / GEOtop)" % (q, season), "mm", w["wg_" + q].max(), w["gt_" + q].max()))
    gt, wg = snow_season(d["gt_SWE"]), snow_season(d["wg_SWE"])
    for season in gt:
        if season in wg and gt[season][0] is not None and wg[season][0] is not None:
            rows.append(("snow-on %s (porting - GEOtop)" % season, "d", (wg[season][0] - gt[season][0]).days,
                         str(gt[season][0].date())))
            rows.append(("snow-off %s (porting - GEOtop)" % season, "d", (wg[season][1] - gt[season][1]).days,
                         str(gt[season][1].date())))
    both = (d["gt_SWE"] > SNOW_SWE) & (d["wg_SWE"] > SNOW_SWE)
    bias, rmse = _stats(d.loc[both, "wg_Ts"], d.loc[both, "gt_Ts"])
    rows.append(("Ts with snow, hourly", "K", bias, rmse))
    for z in DEPTHS_MM:
        bias, rmse = _stats(d["wg_T%d" % z], d["gt_T%d" % z])
        rows.append(("T %.1f m hourly" % (z / 1000.0), "K", bias, rmse))
        bias, rmse = _stats(day["wg_T%d" % z], day["gt_T%d" % z])
        rows.append(("T %.1f m daily" % (z / 1000.0), "K", bias, rmse))
        bias, rmse = _stats(d["wg_Tend%d" % z], d["gt_T%d" % z])
        rows.append(("T %.1f m hourly, end of hour" % (z / 1000.0), "K", bias, rmse))
    bias, rmse = _stats(day["wg_thaw"], day["gt_thaw"])
    rows.append(("thaw depth daily", "mm", bias, rmse))
    for summer in (2022, 2023):
        s = d.loc["%d-05-01" % summer:"%d-08-31" % summer]
        rows.append(("max thaw %d (porting / GEOtop)" % summer, "mm", s["wg_thaw"].max(), s["gt_thaw"].max()))
    return pd.DataFrame(rows, columns=["quantity", "unit", "porting-GEOtop bias / porting", "RMSE / GEOtop"])


def main():
    pd.set_option("display.width", 200)
    for arg in sys.argv[1:] or CASES:
        case, _, label = arg.partition(":")
        print("==", arg)
        print(compare(case, label or None).to_string(index=False, float_format=lambda v: "%.3g" % v))


if __name__ == "__main__":
    main()
