#!/usr/bin/env python3
"""Writes the case GeoPackage of 1D runs (CaseGeoPackage, snowsoil components) from GEOtop 3.0 point runs,
and optionally their meteorological time series as GEOframe OMS tables.

Usage:
    geotop2case.py OUT.gpkg RUN [RUN ...] --epsg CODE [--names A,B,...] [--site-type TYPE]
                   [--directions N] [--case TEXT] [--series FOLDER] [--forcing F1,F2,...] [--svf-of-run]

Each RUN is a folder with a geotop.inpts of a point run (PointSim = 1). Every point of every run becomes a
point and a column of the case, numbered from 1 in the order given; identical soil profiles and initial
conditions are written once.

With --forcing, each run is given the name of its forcing set (one name per run, for instance the reanalysis
it is driven by). A point of a run that is the same as a point already written (coordinates, elevation, slope,
aspect, horizon and sensor heights) is not written again: its column refers to the point already there, so a
place driven by several forcing sets is one point with one column per set. The column records its set in
`forcing`, and with --series the tables of each set go to FOLDER/<set>/, keyed by point as the GEOframe series
(value_<point id>).

With --svf-of-run, svf is the sky view factor of the GEOtop run instead of the one computed from the horizon:
for fidelity cases, which describe the site as GEOtop's run saw it (GEOtop takes 1 where a point run has no
sky view factor, whatever its slope and horizon).

What is read, as GEOtop 3.0 reads it (src/geotop/parameters.cc, input.cc):

- points: the Point* keywords, then the PointFile over them, then the elevation, slope, aspect and sky view
  factor maps (DemFile, SlopeMapFile, AspectMapFile, SkyViewFactorMapFile) for what is still undefined, then
  GEOtop's defaults (elevation 0, flat, sky view factor 1, Latitude and Longitude of the run);
- the horizon of each point: HorizonPointFile, file number PointHorizon (default: the point ID); without a
  file the horizon is flat (no rows in the GeoPackage);
- the soil: SoilParFile0001.txt over the soil keywords (thickness, van Genuchten, conductivities, thermal
  properties of the solids, initial pressure and temperature), cell by cell, as GEOtop's cascade;
- the initial condition: the water table at InitWaterTableDepth, hydrostatic along the column with GEOtop's
  cos(slope) (input.cc:973-983), unless every layer has an initial pressure; InitSoilTemp or the temperature
  column;
- the sensor heights: MeteoStationWindVelocitySensorHeight and MeteoStationTemperatureSensorHeight.

What is written (schema of CaseGeoPackage, SI units, eta negative in the soil):

- points (POINT, EPSG:CODE; the GEOtop coordinates, or latitude and longitude projected when the run has
  none): id, name, elevation, lat, lon, slope, aspect, svf (Dozier and Frew 1990, from the horizon resampled
  to N directions and the slope; with --svf-of-run, svf_geotop), svf_geotop (the one of the GEOtop run, for traceability), site_type,
  wind_height, temperature_height;
- horizon: point_id, azimuth, height, N directions from north, linear between GEOtop's lines across north;
- soil_layers, soil_parameters, soil_parameter_types, initial_condition: WHETGEO-1D's soil inputs, one
  control volume per GEOtop layer. Van Genuchten alpha [mm-1 -> m-1], Ks = GEOtop's normal conductivity
  [mm s-1 -> m s-1], theta_r, theta_s, specific storage [mm-1 -> Pa-1, / (rho g)], thermal conductivity of
  the solids, their heat capacity as density C/1000 [kg m-3] times 1000 J kg-1 K-1; the initial condition at
  the surface, at the centre of every layer and at the bottom (psi [m], T [K]);
- columns: id, point_id, profile_id, ic_id, forcing (the forcing set, NULL without --forcing), note;
- snow_initial: ic_id, swe [kg m-2], density [kg m-3], temperature [K], age [d], from InitSWE, InitSnowDensity,
  InitSnowTemp and InitSnowAge (GEOtop's defaults 200 kg m-3, -3 degC, 0 d), for the runs with InitSWE > 0;
- provenance: script, source runs, SHA-256 of every file read.

With --series, the meteorological file of each run (MeteoFile0001.txt, one station; MeteoFile0001.txt.old
where there is one: a GEOtop run moves its input there and rewrites the file without some of its columns) as
OMS tables, one column value_<id> per point, UTC, the records after InitDateDDMMYYYYhhmm up to
EndDateDDMMYYYYhhmm: air temperature [degC], relative humidity [%], wind speed [m s-1], pressure [hPa -> Pa],
precipitation [mm, the intensity times the interval to the previous record], cloud transmissivity [-], global
shortwave and incoming longwave [W m-2]; -9999 where the file has no value. Only the columns the runs have are
written.

Not supported (the script stops): more than one soil type, a bedrock above the bottom of the soil, slope,
aspect or sky view factor derived from a DEM without their own map, a smoothed DEM, more than one meteo
station, meteo dates given only as Julian days. Numbers are parsed with float(), which may differ from
GEOtop's own parser in the last digit.
"""
import argparse
import csv
import datetime
import hashlib
import math
import os
import re
import sqlite3
import sys

import geopandas as gpd
import numpy as np
from pyproj import Transformer
from shapely.geometry import Point

NOVALUE = -9999.0
ABSENT = -9998.0
SCHEMA = "geo2geo 1D case, version 2"
RHO_G = 9810.0  # [Pa m-1], specific storage per unit head -> per unit pressure
SOLID_SPECIFIC_HEAT = 1000.0  # [J kg-1 K-1]
MELTING = 273.15  # [K]

# Point columns, as GEOtop's: the inline keyword and the header keyword of the PointFile.
POINT = {
    "id": ("PointID", "HeaderPointID"),
    "x": ("CoordinatePointX", "HeaderCoordinatePointX"),
    "y": ("CoordinatePointY", "HeaderCoordinatePointY"),
    "z": ("PointElevation", "HeaderPointElevation"),
    "soil": ("PointSoilType", "HeaderPointSoilType"),
    "slope": ("PointSlope", "HeaderPointSlope"),
    "aspect": ("PointAspect", "HeaderPointAspect"),
    "sky": ("PointSkyViewFactor", "HeaderPointSkyViewFactor"),
    "horizon": ("PointHorizon", "HeaderPointHorizon"),
    "lat": ("PointLatitude", "HeaderPointLatitude"),
    "lon": ("PointLongitude", "HeaderPointLongitude"),
    "bedrock": (None, "HeaderPointBedrockDepth"),
}
MAPS = {"z": "DemFile", "slope": "SlopeMapFile", "aspect": "AspectMapFile", "sky": "SkyViewFactorMapFile"}

# Soil properties: header keyword of the soil file, keyword of the value of the layers, GEOtop's default.
SOIL = {
    "dz": ("HeaderSoilDz", None, None),
    "psi": ("HeaderSoilInitPres", "InitSoilPressure", NOVALUE),
    "T": ("HeaderSoilInitTemp", "InitSoilTemp", 5.0),
    "Kn": ("HeaderNormalHydrConductivity", "NormalHydrConductivity", 1.0e-4),
    "res": ("HeaderThetaRes", "ThetaRes", 0.05),
    "sat": ("HeaderThetaSat", "ThetaSat", 0.5),
    "a": ("HeaderAlpha", "AlphaVanGenuchten", 0.004),
    "n": ("HeaderN", "NVanGenuchten", 1.3),
    "kt": ("HeaderKthSoilSolids", "ThermalConductivitySoilSolids", 2.5),
    "ct": ("HeaderCthSoilSolids", "ThermalCapacitySoilSolids", 1.0e6),
    "ss": ("HeaderSpecificStorativity", "SpecificStorativity", 1.0e-7),
}
DEFAULT_LAYER_THICKNESS = 100.0  # [mm]
DEFAULT_LAYER_NUMBER = 5
DEFAULT_WATER_TABLE = 5000.0  # [mm]

# Meteorological series: OMS name, header keyword, conversion of a value (and of the interval [h]).
SERIES = [
    ("airTemperature", "HeaderAirTemp", lambda v, h: v),
    ("relativeHumidity", "HeaderRH", lambda v, h: v),
    ("windSpeed", "HeaderWindVelocity", lambda v, h: v),
    ("pressure", "HeaderAirPress", lambda v, h: 100.0 * v),
    ("precipitation", "HeaderIPrec", lambda v, h: v * h),
    ("cloudTransmissivity", "HeaderCloudSWTransmissivity", lambda v, h: v),
    ("shortwaveGlobal", "HeaderSWglobal", lambda v, h: v),
    ("longwaveIn", "HeaderLWin", lambda v, h: v),
]
# series the column cannot run without (cases/snowsoil/README.md, Forcing)
REQUIRED = ("airTemperature", "relativeHumidity", "windSpeed")


def undefined(v):
    return v is None or int(v) in (int(NOVALUE), int(ABSENT))


class Inpts:
    """The keywords of a geotop.inpts: case-insensitive, later occurrences win, '!' starts a comment."""

    DATE = re.compile(r"^\s*(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})")

    def __init__(self, path):
        self.values = {}
        with open(path, encoding="latin-1") as f:
            for line in f:
                line = self._strip_comment(line)
                if "=" not in line:
                    continue
                key, value = line.split("=", 1)
                key = re.sub(r"\s", "", key).lower()
                value = value.strip()
                if value.startswith('"'):
                    self.values[key] = value[1:value.index('"', 1)] if '"' in value[1:] else value[1:]
                elif self.DATE.match(value):
                    self.values[key] = value
                elif value:
                    self.values[key] = [float(v) for v in value.split(",") if v.strip()]

    @staticmethod
    def _strip_comment(line):
        quoted = False
        for i, c in enumerate(line):
            if c == '"':
                quoted = not quoted
            elif c == "!" and not quoted:
                return line[:i]
        return line

    def components(self, key):
        v = self.values.get(key.lower())
        return len(v) if isinstance(v, list) else 0

    def number(self, key, j=0, default=None):
        v = self.values.get(key.lower())
        if isinstance(v, list) and j < len(v) and not undefined(v[j]):
            return v[j]
        return NOVALUE if default is None else default

    def string(self, key):
        v = self.values.get(key.lower())
        return v if isinstance(v, str) and v.lower() != "none" else None

    def date(self, key):
        v = self.values.get(key.lower())
        if not isinstance(v, str):
            return None
        d, m, y, hh, mm = (int(g) for g in self.DATE.match(v).groups())
        return datetime.datetime(y, m, d, hh, mm)


def read_table(path, columns):
    """GEOtop's read_txt_matrix: a header line, then rows of numbers; each wanted column by its header name
    (case-insensitive), ABSENT where the file has no such column, NOVALUE for a missing field."""
    with open(path, encoding="latin-1") as f:
        lines = [l for l in f if l.strip() and not l.lstrip().startswith("!")]
    header = [h.strip().strip('"').lower() for h in lines[0].split(",")]
    index = [header.index(c.lower()) if c and c.lower() in header else -1 for c in columns]
    rows = []
    for line in lines[1:]:
        fields = [x.strip() for x in line.split(",")]
        raw = [float(x) if x else NOVALUE for x in fields] + [NOVALUE] * (len(header) - len(fields))
        rows.append([raw[k] if k >= 0 else ABSENT for k in index])
    return rows


def read_ascii_grid(path):
    head = {}
    with open(path) as f:
        for _ in range(6):
            k, v = f.readline().split()
            head[k.lower()] = float(v)
        return head, np.loadtxt(f)


def sample(grid, x, y):
    head, a = grid
    c = int(math.floor((x - head["xllcorner"]) / head["cellsize"]))
    r = int(head["nrows"]) - 1 - int(math.floor((y - head["yllcorner"]) / head["cellsize"]))
    if not (0 <= r < a.shape[0] and 0 <= c < a.shape[1]):
        raise SystemExit(f"point ({x}, {y}) outside the map")
    v = float(a[r, c])
    return NOVALUE if v == head.get("nodata_value", NOVALUE) else v


def periodic(azimuth, height, at):
    """Horizon height at the azimuths `at`, linear between the given ones, across north."""
    a = np.concatenate([azimuth - 360.0, azimuth, azimuth + 360.0])
    return np.interp(at, a, np.concatenate([height, height, height]))


def sky_view_factor(azimuth, height, slope, aspect, n=3600):
    """Dozier and Frew (1990): V = 1/2pi int [cos b sin^2 H + sin b cos(phi - A)(H - sin H cos H)] dphi, with H the
    zenith angle of the horizon, no lower than the slope's own plane (as Horizon.skyViewFactor in Java)."""
    phi = np.radians((np.arange(n) + 0.5) * 360.0 / n)
    h = np.radians(periodic(np.asarray(azimuth, float), np.asarray(height, float), np.degrees(phi)))
    b, a = np.radians(slope), np.radians(aspect)
    plane = np.arctan(-np.tan(b) * np.cos(phi - a))
    zenith = 0.5 * np.pi - np.maximum(h, plane)
    return float(np.mean(np.cos(b) * np.sin(zenith) ** 2
                         + np.sin(b) * np.cos(phi - a) * (zenith - np.sin(zenith) * np.cos(zenith))))


class Run:
    """A GEOtop point run: its points, their horizons, the soil, the initial condition."""

    def __init__(self, folder):
        self.folder = folder
        self.inpts = Inpts(os.path.join(folder, "geotop.inpts"))
        self.read = [os.path.join(folder, "geotop.inpts")]
        if self.inpts.number("PointSim", 0, 0.0) != 1.0:
            raise SystemExit(f"{folder}: not a point run (PointSim = 1)")
        if self.inpts.number("SoilLayerTypes", 0, 1.0) > 1:
            raise SystemExit(f"{folder}: more than one soil type is not supported")

    def path(self, stem, suffix):
        return os.path.join(self.folder, stem + suffix)

    def points(self):
        """GEOtop's point matrix (parameters.cc:1450-1474, 2651-2723; input.cc:2952-3459), one dict per point."""
        k = self.inpts
        n = max([1] + [k.components(kw) for kw, _ in POINT.values() if kw])
        pts = [{c: (k.number(kw, i) if kw else NOVALUE) for c, (kw, _) in POINT.items()} for i in range(n)]
        stem = k.string("PointFile")
        if stem and os.path.exists(self.path(stem, ".txt")):
            names = list(POINT)
            rows = read_table(self.path(stem, ".txt"), [k.string(h) for _, h in POINT.values()])
            self.read.append(self.path(stem, ".txt"))
            base = pts
            pts = []
            for i, r in enumerate(rows):
                fallback = base[min(i, len(base) - 1)]
                pts.append({c: (fallback[c] if undefined(v) else v) for c, v in zip(names, r)})
        coordinates = all(not undefined(p["x"]) and not undefined(p["y"]) for p in pts)
        for c, kw in MAPS.items():
            if not coordinates or not any(undefined(p[c]) for p in pts):
                continue
            stem = k.string(kw)
            if stem and os.path.exists(self.path(stem, ".asc")):
                if c == "z" and k.number("NumLowPassFilterOnDemForAll", 0, 0.0) > 0:
                    raise SystemExit(f"{self.folder}: a smoothed DEM is not supported")
                grid = read_ascii_grid(self.path(stem, ".asc"))
                self.read.append(self.path(stem, ".asc"))
                for p in pts:
                    if undefined(p[c]):
                        p[c] = sample(grid, p["x"], p["y"])
            elif c != "z" and k.string("DemFile") and os.path.exists(self.path(k.string("DemFile"), ".asc")):
                raise SystemExit(f"{self.folder}: {c} derived from the DEM is not supported")
        defaults = {"z": 0.0, "soil": 1.0, "slope": 0.0, "aspect": 0.0, "sky": 1.0,
                    "lat": k.number("Latitude", 0, 45.0), "lon": k.number("Longitude", 0, 0.0)}
        for i, p in enumerate(pts):
            for c, v in defaults.items():
                if undefined(p[c]):
                    p[c] = v
            if undefined(p["id"]):
                p["id"] = float(i + 1)
            if undefined(p["horizon"]):
                p["horizon"] = p["id"]
            if int(p["soil"]) != 1:
                raise SystemExit(f"{self.folder}: point {i + 1} has soil type {p['soil']}, only 1 is supported")
        return pts

    def horizon(self, p):
        """Azimuths and heights [deg] of the point's horizon file, or None (flat)."""
        stem = self.inpts.string("HorizonPointFile")
        if not stem:
            return None
        path = self.path(stem, "%04d.txt" % int(p["horizon"]))
        if not os.path.exists(path):
            return None
        cols = [self.inpts.string("HeaderHorizonAngle"), self.inpts.string("HeaderHorizonHeight")]
        rows = read_table(path, cols)
        if any(undefined(v) for r in rows for v in r):
            raise SystemExit(f"{path}: columns {cols} not found or incomplete")
        self.read.append(path)
        rows.sort()
        return np.array([r[0] for r in rows]), np.array([r[1] for r in rows])

    def soil(self):
        """GEOtop's soil matrix of type 1 (parameters.cc:2358-2644): {property: [value per layer]}, and the
        initial water table depth [mm] or None."""
        k = self.inpts
        if k.components("SoilLayerThicknesses") > 1:
            dz = [k.number("SoilLayerThicknesses", i) for i in range(k.components("SoilLayerThicknesses"))]
            for i in range(1, len(dz)):
                if undefined(dz[i]):
                    dz[i] = dz[i - 1]
        else:
            a = k.number("SoilLayerThicknesses", 0, DEFAULT_LAYER_THICKNESS)
            dz = [a] * int(k.number("SoilLayerNumber", 0, DEFAULT_LAYER_NUMBER))
        keyword = {"dz": dz}
        for c, (_, kw, default) in SOIL.items():
            if kw:
                v = [k.number(kw, 0, default)]
                for i in range(1, len(dz)):
                    v.append(k.number(kw, i, v[-1]))
                keyword[c] = v
        stem = k.string("SoilParFile")
        if stem:
            path = self.path(stem, "0001.txt")
            if not os.path.exists(path):
                raise SystemExit(f"{path}: soil file not found")
            rows = read_table(path, [k.string(h) for h, _, _ in SOIL.values()])
            self.read.append(path)
            pa = {c: [r[j] for r in rows] for j, c in enumerate(SOIL)}
            nl = len(rows)
            for c in SOIL:  # thickness first, as GEOtop
                for j in range(nl):
                    if undefined(pa[c][j]):
                        pa[c][j] = keyword[c][j] if j < len(keyword[c]) else pa[c][j - 1]
        else:
            pa = keyword
        water_table = k.number("InitWaterTableDepth", 0, DEFAULT_WATER_TABLE)
        if all(not undefined(v) for v in pa["psi"]):
            water_table = None
        return pa, water_table

    def bedrock_check(self, p, pa):
        if not undefined(p["bedrock"]) and p["bedrock"] < sum(pa["dz"]):
            raise SystemExit(f"{self.folder}: a bedrock above the bottom of the soil is not supported")
        if self.inpts.string("BedrockDepthMapFile"):
            raise SystemExit(f"{self.folder}: a bedrock map is not supported")

    def snow(self):
        """GEOtop's initial snow (InitSWE [mm], InitSnowDensity, InitSnowTemp [degC], InitSnowAge [d]) as
        (swe [kg m-2], density [kg m-3], temperature [K], age [d]); None without snow."""
        k = self.inpts
        for key in ("InitSWEMapFile", "InitSnowDepthMapFile", "InitSnowAgeMapFile"):
            if k.string(key):
                raise SystemExit(f"{self.folder}: {key} is not supported")
        swe = k.number("InitSWE", 0, 0.0)
        if not swe > 0.0:
            return None
        return (swe, k.number("InitSnowDensity", 0, 200.0), k.number("InitSnowTemp", 0, -3.0) + MELTING,
                k.number("InitSnowAge", 0, 0.0))

    def sensor_heights(self):
        return (self.inpts.number("MeteoStationWindVelocitySensorHeight", 0, 10.0),
                self.inpts.number("MeteoStationTemperatureSensorHeight", 0, 2.0))

    def meteo(self):
        """{OMS name: {UTC end of the record: value}} of the run's meteo station."""
        k = self.inpts
        if k.number("NumberOfMeteoStations", 0, 1.0) > 1:
            raise SystemExit(f"{self.folder}: more than one meteo station is not supported")
        date = k.string("HeaderDateDDMMYYYYhhmmMeteo")
        if not date:
            raise SystemExit(f"{self.folder}: meteo dates as Julian days only are not supported")
        path = self.path(k.string("MeteoFile"), "0001.txt")
        if os.path.exists(path + ".old"):  # GEOtop rewrites the file it read, without some columns
            path += ".old"
        self.read.append(path)
        with open(path, encoding="latin-1") as f:
            lines = [l for l in f if l.strip() and not l.lstrip().startswith("!")]
        header = [h.strip().strip('"').lower() for h in lines[0].split(",")]
        st = k.number("StandardTimeSimulation", 0, 0.0)
        station = datetime.timedelta(hours=k.number("MeteoStationStandardTime", 0, st))
        start = k.date("InitDateDDMMYYYYhhmm") - datetime.timedelta(hours=st)
        end = k.date("EndDateDDMMYYYYhhmm")
        end = end - datetime.timedelta(hours=st) if end else None
        columns = [(name, header.index(k.string(h).lower()), f) for name, h, f in SERIES
                   if k.string(h) and k.string(h).lower() in header]
        for name, h, _ in SERIES:
            if k.string(h) and k.string(h).lower() not in header:
                print(f"warning: {path}: {h} = {k.string(h)} has no column in the file, {name} not written",
                      file=sys.stderr)
        di = header.index(date.lower())
        out = {name: {} for name, _, _ in columns}
        previous = None
        for line in lines[1:]:
            fields = [x.strip() for x in line.split(",")]
            t = datetime.datetime.strptime(fields[di], "%d/%m/%Y %H:%M") - station
            hours = (t - previous).total_seconds() / 3600.0 if previous else None
            previous = t
            if t <= start or (end and t > end):
                continue
            for name, j, f in columns:
                v = float(fields[j]) if j < len(fields) and fields[j] else NOVALUE
                out[name][t] = NOVALUE if undefined(v) or hours is None else f(v, hours)
        return out


def whetgeo_soil(pa, water_table, slope):
    """WHETGEO-1D's soil inputs of a GEOtop soil column: the rows of grid_geometry and swrc_parameters, and
    of initial_condition (GEOtop's hydrostatic profile with cos(slope), input.cc:973-983)."""
    params, ids = [], []
    for j in range(len(pa["dz"])):
        row = (pa["ct"][j] / SOLID_SPECIFIC_HEAT, pa["kt"][j], SOLID_SPECIFIC_HEAT, pa["sat"][j], pa["res"][j],
               MELTING, pa["a"][j] * 1e3, pa["n"][j], pa["ss"][j] * 1e3 / RHO_G, 0.0, pa["Kn"][j] * 1e-3)
        if row not in params:
            params.append(row)
        ids.append(params.index(row) + 1)
    grid, depth = [], 0.0  # [mm]
    for j, dz in enumerate(pa["dz"]):
        grid.append(("L", -depth / 1000.0, 1, 0, ids[j]))
        depth += dz
    grid.append(("L", -depth / 1000.0, None, None, None))
    cos = math.cos(math.radians(slope))
    if water_table is not None:
        p0 = -water_table * cos
        psi, z = [], 0.0
        for dz in pa["dz"]:
            z += 0.5 * dz * cos
            psi.append(p0 + z)
            z += 0.5 * dz * cos
        top, bottom = p0, p0 + z
    else:
        psi = list(pa["psi"])
        top, bottom = psi[0], psi[-1]
    ic, depth = [(0.0, top / 1000.0, pa["T"][0] + MELTING)], 0.0
    for j, dz in enumerate(pa["dz"]):
        ic.append((-(depth + 0.5 * dz) / 1000.0, psi[j] / 1000.0, pa["T"][j] + MELTING))
        depth += dz
    ic.append((-depth / 1000.0, bottom / 1000.0, pa["T"][-1] + MELTING))
    return grid, params, ic


def attributes(con, name, header, rows, types, description):
    """A GeoPackage attributes table (registered in gpkg_contents)."""
    cols = ", ".join(f'"{h}" {t}' for h, t in zip(header, types))
    con.execute(f'DROP TABLE IF EXISTS "{name}"')
    con.execute(f'CREATE TABLE "{name}" (fid INTEGER PRIMARY KEY AUTOINCREMENT, {cols})')
    con.executemany(f'INSERT INTO "{name}" ({", ".join(chr(34) + h + chr(34) for h in header)}) '
                    f'VALUES ({", ".join("?" * len(header))})', rows)
    con.execute("DELETE FROM gpkg_contents WHERE table_name = ?", (name,))
    con.execute("INSERT INTO gpkg_contents (table_name, data_type, identifier, description, last_change) "
                "VALUES (?, 'attributes', ?, ?, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))", (name, name, description))


def write_series(folder, series, ids):
    """One OMS table per variable, one column value_<id> per point."""
    os.makedirs(folder, exist_ok=True)
    for name, by_point in series.items():
        dates = sorted(set().union(*(by_point.get(i, {}).keys() for i in ids)))
        with open(os.path.join(folder, name + ".csv"), "w") as f:
            f.write("@T,table\nCreated,%s\nAuthor,geotop2case.py\n" % datetime.date.today().isoformat())
            f.write("@H,timestamp," + ",".join("value_%d" % i for i in ids) + "\n")
            f.write("ID,," + ",".join(str(i) for i in ids) + "\n")
            f.write("Type,Date," + ",".join("double" for _ in ids) + "\n")
            f.write("Format,yyyy-MM-dd HH:mm," + "," * (len(ids) - 1) + "\n")
            for t in dates:
                f.write("," + t.strftime("%Y-%m-%d %H:%M") + ","
                        + ",".join("%.10g" % by_point.get(i, {}).get(t, NOVALUE) for i in ids) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("out", help="the GeoPackage to write (replaced)")
    ap.add_argument("runs", nargs="+", help="folders of GEOtop point runs")
    ap.add_argument("--epsg", type=int, required=True, help="coordinate system of the GEOtop coordinates")
    ap.add_argument("--names", help="comma-separated names of the points (default: the folder names)")
    ap.add_argument("--site-type", default="station", help="site_type of the points (default: station)")
    ap.add_argument("--directions", type=int, default=16, help="directions of the horizon (default: 16)")
    ap.add_argument("--case", default="", help="description of the case, for the provenance")
    ap.add_argument("--series", help="folder for the OMS meteorological tables")
    ap.add_argument("--forcing", help="comma-separated forcing set of each run: runs at the same point become "
                                      "one point with a column per set, the tables go to SERIES/<set>/")
    ap.add_argument("--svf-of-run", action="store_true",
                    help="svf = the one of the GEOtop run, not computed from the horizon (fidelity cases)")
    a = ap.parse_args(argv)
    forcings = a.forcing.split(",") if a.forcing else [None] * len(a.runs)
    if len(forcings) != len(a.runs):
        raise SystemExit(f"{len(forcings)} forcing sets for {len(a.runs)} runs")

    points, horizon, profiles, ics, columns, series = [], [], [], [], [], {}
    sources, place = [], {}
    directions = np.arange(a.directions) * 360.0 / a.directions
    to_crs = Transformer.from_crs(4326, a.epsg, always_xy=True)
    for folder, forcing in zip(a.runs, forcings):
        run = Run(folder)
        pa, water_table = run.soil()
        wind, temperature = run.sensor_heights()
        meteo = run.meteo() if a.series else {}
        pts = run.points()
        for p in pts:
            run.bedrock_check(p, pa)
            hor = run.horizon(p)
            h16 = np.zeros(a.directions) if hor is None \
                else np.array([round(float(v), 4) for v in periodic(hor[0], hor[1], directions)])
            x, y = (p["x"], p["y"]) if not (undefined(p["x"]) or undefined(p["y"])) \
                else to_crs.transform(p["lon"], p["lat"])
            key = (x, y, p["z"], p["slope"], p["aspect"], tuple(h16), wind, temperature)
            grid, params, ic = whetgeo_soil(pa, water_table, p["slope"])
            if (grid, params) not in profiles:
                profiles.append((grid, params))
            ic = (ic, run.snow())
            if ic not in ics:
                ics.append(ic)
            cid = len(columns) + 1
            if forcing is not None and key in place:
                pid = place[key]
                name = points[pid - 1]["name"]
            else:
                pid = len(points) + 1
                place[key] = pid
                if hor is not None:
                    horizon += [(pid, float(az), float(h)) for az, h in zip(directions, h16)]
                name = os.path.basename(os.path.normpath(folder)) if len(pts) == 1 \
                    else "%s_%d" % (os.path.basename(os.path.normpath(folder)), int(p["id"]))
                points.append({"id": pid, "name": name, "elevation": p["z"], "lat": p["lat"], "lon": p["lon"],
                               "slope": p["slope"], "aspect": p["aspect"],
                               "svf": p["sky"] if a.svf_of_run
                               else round(sky_view_factor(directions, h16, p["slope"], p["aspect"]), 4),
                               "svf_geotop": p["sky"], "site_type": a.site_type, "wind_height": wind,
                               "temperature_height": temperature, "geometry": Point(x, y)})
            columns.append((cid, pid, profiles.index((grid, params)) + 1, ics.index(ic) + 1, forcing))
            for v, values in meteo.items():
                by_point = series.setdefault(forcing, {}).setdefault(v, {})
                if pid in by_point:
                    raise SystemExit(f"{folder}: point {pid} has forcing set {forcing} twice")
                by_point[pid] = values
        sources += run.read
    if a.names:
        names = a.names.split(",")
        if len(names) != len(points):
            raise SystemExit(f"{len(names)} names for {len(points)} points")
        for p, name in zip(points, names):
            p["name"] = name
    columns = [c + (" ".join([points[c[1] - 1]["name"]] + ([c[4]] if c[4] else [])),) for c in columns]

    if os.path.exists(a.out):
        os.remove(a.out)
    gpd.GeoDataFrame(points, crs=f"EPSG:{a.epsg}").to_file(a.out, layer="points", driver="GPKG")
    with sqlite3.connect(a.out) as con:
        attributes(con, "horizon", ["point_id", "azimuth", "height"], horizon, ["INTEGER", "REAL", "REAL"],
                   f"horizon of each point, {a.directions} directions [deg]")
        attributes(con, "soil_layers", ["profile_id", "Type", "eta", "K", "equationStateID", "parameterID"],
                   [(i + 1,) + r for i, (grid, _) in enumerate(profiles) for r in grid],
                   ["INTEGER", "TEXT", "REAL", "INTEGER", "INTEGER", "INTEGER"],
                   "WHETGEO-1D grid_geometry per soil profile")
        h = ["spDensity", "spConductivity", "spSpecificHeatCapacity", "thetaS", "thetaR", "meltingT", "alpha", "n",
             "alphaSpecificStorage", "betaSpecificStorage", "Ks"]
        attributes(con, "soil_parameters", ["profile_id", "parameter_id"] + h,
                   [(i + 1, j + 1) + r for i, (_, params) in enumerate(profiles) for j, r in enumerate(params)],
                   ["INTEGER", "INTEGER"] + ["REAL"] * len(h), "WHETGEO-1D swrc_parameters per soil profile")
        attributes(con, "soil_parameter_types", ["val1", "val2"], [("n", "par1"), ("alpha", "par2")],
                   ["TEXT", "TEXT"], "WHETGEO-1D swrc_parameter_types")
        attributes(con, "initial_condition", ["ic_id", "eta", "psi0", "t0"],
                   [(i + 1,) + r for i, (ic, _) in enumerate(ics) for r in ic], ["INTEGER", "REAL", "REAL", "REAL"],
                   "WHETGEO-1D initial_condition")
        attributes(con, "snow_initial", ["ic_id", "swe", "density", "temperature", "age"],
                   [(i + 1,) + snow for i, (_, snow) in enumerate(ics) if snow],
                   ["INTEGER", "REAL", "REAL", "REAL", "REAL"],
                   "initial snow, one layer as GEOtop's InitSWE [kg m-2], density [kg m-3], T [K], age [d]")
        attributes(con, "columns", ["id", "point_id", "profile_id", "ic_id", "forcing", "note"], columns,
                   ["INTEGER", "INTEGER", "INTEGER", "INTEGER", "TEXT", "TEXT"], "one row per 1D run")
        root = os.path.dirname(os.path.commonpath([os.path.abspath(r) for r in a.runs]))
        runs = ", ".join(os.path.relpath(os.path.abspath(r), root) for r in a.runs)
        prov = [("schema", SCHEMA), ("case", a.case), ("source", "GEOtop 3.0 point runs: " + runs),
                ("written", datetime.date.today().isoformat()), ("script", "tools/geotop2case/geotop2case.py"),
                ("svf", "the one of the GEOtop run (fidelity)" if a.svf_of_run
                 else f"computed from the {a.directions}-direction horizon and the slope (Dozier and Frew 1990); "
                      "svf_geotop is the one of the GEOtop run")]
        seen = []
        for p in sources:
            if p not in seen:
                seen.append(p)
                with open(p, "rb") as f:
                    prov.append(("sha256:" + os.path.relpath(os.path.abspath(p), root),
                                 hashlib.sha256(f.read()).hexdigest()))
        attributes(con, "provenance", ["key", "value"], prov, ["TEXT", "TEXT"], "where the data come from")
    if a.series:
        for forcing, tables in series.items():
            missing = [n for n in REQUIRED if n not in tables]
            if missing:
                print(f"warning: no {', '.join(missing)}" + (f" in forcing set {forcing}" if forcing else "")
                      + ": SnowSoilSolver1DMain needs it, give it before running the column", file=sys.stderr)
            ids = sorted(set().union(*(t.keys() for t in tables.values())))
            write_series(a.series if forcing is None else os.path.join(a.series, forcing), tables, ids)
    print(gpd.read_file(a.out, layer="points").drop(columns="geometry").to_string(index=False))


if __name__ == "__main__":
    main()
