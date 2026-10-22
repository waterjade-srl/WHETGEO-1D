#!/usr/bin/env python3
"""Writes the forcing of the synthetic snow season in GEOframe's OMS CSV format.

A flat point at 46.5 N, 11.35 E, 1500 m, hourly from 2025-11-15 01:00 UTC for
120 days (each record closes the hour ending at its date):

- air temperature: -5 degC until day 75, rising linearly to +7 degC at day 120,
  diurnal cycle of +-4 degC peaking at 14:00 UTC; -3 degC during snowfall,
  +3.5 degC during the rain-on-snow event;
- precipitation (total, split by the component): 2.5 mm/h for 10 h on days
  3, 12, 22, 33, 45, 58, 80 (snow), 2 mm/h for 12 h on day 66 (rain on snow);
- global shortwave: clear sky 1100 * 0.75**(1/sin h) * sin h with the sun height
  at the middle of the hour (Spencer 1971), a third of it under precipitation;
- relative humidity 75 % (95 % under precipitation), wind 2 m/s (3 m/s).

No pressure and no longwave: the component takes the pressure from the
elevation and estimates the incoming longwave as GEOtop does.

Usage: python3 make_inputs.py   (writes data/*.csv next to this script)
"""
import math
import os
from datetime import datetime, timedelta

START = datetime(2025, 11, 15, 0, 0)
DAYS = 120
LAT, LON = 46.5, 11.35
SNOW_DAYS = [3, 12, 22, 33, 45, 58, 80]
RAIN_DAY = 66


def sun_height(t):
    day = t.timetuple().tm_yday
    hours = t.hour + t.minute / 60.0
    g = 2 * math.pi / 365 * (day - 1 + (hours - 12) / 24)
    decl = (0.006918 - 0.399912 * math.cos(g) + 0.070257 * math.sin(g) - 0.006758 * math.cos(2 * g)
            + 0.000907 * math.sin(2 * g) - 0.002697 * math.cos(3 * g) + 0.00148 * math.sin(3 * g))
    eot = 229.18 * (0.000075 + 0.001868 * math.cos(g) - 0.032077 * math.sin(g) - 0.014615 * math.cos(2 * g)
                    - 0.040849 * math.sin(2 * g))
    solar_minutes = hours * 60 + eot + 4 * LON
    ha = math.radians(solar_minutes / 4 - 180)
    phi = math.radians(LAT)
    return math.asin(math.sin(phi) * math.sin(decl) + math.cos(phi) * math.cos(decl) * math.cos(ha))


def event(day_float):
    for d in SNOW_DAYS:
        if d <= day_float < d + 10 / 24:
            return "snow"
    if RAIN_DAY <= day_float < RAIN_DAY + 12 / 24:
        return "rain"
    return None


def main():
    rows = {k: [] for k in ("airTemperature", "relativeHumidity", "windSpeed", "shortwave", "precipitation")}
    for k in range(DAYS * 24):
        begin = START + timedelta(hours=k)
        end = begin + timedelta(hours=1)
        middle = begin + timedelta(minutes=30)
        day = k / 24.0
        ev = event(day)
        mean = -5.0 + 12.0 * max(0.0, day - 75.0) / 45.0
        hour = middle.hour + middle.minute / 60.0
        ta = mean + 4.0 * math.sin(2 * math.pi * (hour - 8.0) / 24.0)
        if ev == "snow":
            ta, p = -3.0, 2.5
        elif ev == "rain":
            ta, p = 3.5, 2.0
        else:
            p = 0.0
        h = sun_height(middle)
        sw = 1100.0 * 0.75 ** (1.0 / math.sin(h)) * math.sin(h) if h > 0.02 else 0.0
        if ev:
            sw /= 3.0
        date = end.strftime("%Y-%m-%d %H:%M")
        rows["airTemperature"].append((date, ta))
        rows["relativeHumidity"].append((date, 95.0 if ev else 75.0))
        rows["windSpeed"].append((date, 3.0 if ev else 2.0))
        rows["shortwave"].append((date, sw))
        rows["precipitation"].append((date, p))
    here = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(os.path.join(here, "data"), exist_ok=True)
    for name, values in rows.items():
        with open(os.path.join(here, "data", name + ".csv"), "w") as f:
            f.write("@T,table\nCreated,2026-10-22 00:00\nAuthor,make_inputs.py\n@H,timestamp,value_1\n"
                    "ID,,1\nType,Date,double\nFormat,yyyy-MM-dd HH:mm,\n")
            for date, v in values:
                f.write(",%s,%.2f\n" % (date, v))


if __name__ == "__main__":
    main()
