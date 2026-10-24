#!/usr/bin/env python3
"""Stored copy of the ARF_1D snow regression and its comparison (tools/snow_regression.sh).

Usage: snow_regression_compare.py <output_folder> <stored.csv.gz> capture|check
"""
import csv
import gzip
import sys

COLUMNS = ["snow_water_equivalent[mm]", "snow_depth[mm]", "Tsurface[C]", "snowRunoff[mm]"]
DEPTHS_MM = (100.0, 500.0, 2000.0)


def table(path):
    with open(path) as f:
        rows = list(csv.reader(l for l in f if not l.startswith("#")))
    return rows[0], rows[1:]


def current(out):
    ph, pr = table(out + "/point.csv")
    th, tr = table(out + "/soilTzav.csv")
    pi = [ph.index(c) for c in COLUMNS]
    depths = [float(h) for h in th[2:]]
    ti = [2 + min(range(len(depths)), key=lambda i: abs(depths[i] - d)) for d in DEPTHS_MM]
    names = COLUMNS + ["T%.0fmm[C]" % depths[i - 2] for i in ti]
    rows = [[p[0]] + [p[i] for i in pi] + [t[i] for i in ti] for p, t in zip(pr, tr)]
    return names, rows


def main():
    out, stored, mode = sys.argv[1:4]
    names, rows = current(out)
    if mode == "capture":
        with gzip.open(stored, "wt", newline="") as f:
            w = csv.writer(f)
            w.writerow(["date"] + names)
            w.writerows(rows)
        return
    with gzip.open(stored, "rt") as f:
        old = list(csv.reader(f))[1:]
    if len(old) != len(rows):
        print("records: %d stored, %d now" % (len(old), len(rows)))
        return
    for j, name in enumerate(names, start=1):
        worst, at = 0.0, ""
        for a, b in zip(old, rows):
            d = abs(float(a[j]) - float(b[j]))
            if d > worst:
                worst, at = d, b[0]
        print("  %-26s max |difference| %.6g at %s" % (name, worst, at))


if __name__ == "__main__":
    main()
