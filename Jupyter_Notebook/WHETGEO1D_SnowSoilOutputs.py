# -*- coding: utf-8 -*-
"""
Readers and plots of the outputs of the WHETGEO-1D snow+soil column
(io.snowsoil: SnowSoilNetcdfWriter, SnowSoilCsvWriter).

- read_netcdf: the NetCDF file as an xarray Dataset (engine 'scipy', as the
  other GEOframe notebooks), its run header in ds.attrs;
- read_csv: a CSV file (point.csv, soilTz.csv, snowT.csv, ...) as a pandas
  DataFrame indexed by date, with its run header; GEOtop's own point and
  profile files read the same way, so the columns align by name;
- plot_snow_cells: snow profiles drawn as cells of variable thickness;
- plot_soil_profile: a soil variable in time and eta.

Conventions (ARCH-5): eta [m], zero at the soil surface, negative in the
soil, positive in the snow; index 0 at the bottom. NetCDF in SI units, CSV in
GEOtop's units (mm, C, W/m2), -9999 for no value.

@license: GPL v3, as WHETGEO-1D
"""
import numpy as np
import pandas as pd
import xarray as xr
import matplotlib.pyplot as plt
from matplotlib.collections import PatchCollection
from matplotlib.patches import Rectangle
from matplotlib import cm, colors

NO_VALUE = -9999.0
DATE_COLUMN = "Date12[DDMMYYYYhhmm]"


def read_netcdf(path):
    """The NetCDF output; the run header (code version, configuration, hashes) is ds.attrs."""
    return xr.open_dataset(path, engine="scipy")


def read_header(path):
    """The '# key: value' lines at the top of a CSV output, as a dict."""
    header = {}
    with open(path) as f:
        for line in f:
            if not line.startswith("#"):
                break
            key, _, value = line[1:].strip().partition(": ")
            header[key] = value
    return header


def read_csv(path, date_format="%d/%m/%Y %H:%M"):
    """A CSV output of the column or a GEOtop table: DataFrame indexed by date, -9999 as NaN.

    Returns (data, header). Column names are stripped of the blanks GEOtop
    leaves around them; the depth columns of the soil profiles become floats [mm].
    """
    header = read_header(path)
    data = pd.read_csv(path, comment="#", skipinitialspace=True)
    data.columns = [c.strip() for c in data.columns]
    data.index = pd.to_datetime(data[data.columns[0]].str.strip(), format=date_format)
    data = data.replace(NO_VALUE, np.nan)
    renamed = {}
    for c in data.columns:
        try:
            renamed[c] = float(c)
        except ValueError:
            pass
    return data.rename(columns=renamed), header


def profile_columns(data):
    """The value columns of a profile file: depths (soil, float) or L1, L2, ... (snow)."""
    return [c for c in data.columns if isinstance(c, float) or (isinstance(c, str) and c[:1] == "L" and c[1:].isdigit())]


def plot_snow_cells(ds, variable="snowCellTemperature", ax=None, cmap="viridis", vmin=None, vmax=None,
                    label=None, scale=1.0, offset=0.0):
    """Snow cells of variable thickness, coloured by `variable`: one bar per cell and output time.

    The cells are drawn from snowCellEtaBottom to snowCellEtaTop, so the pack
    reads as it is in the column, and its top is HS.
    """
    if ax is None:
        _, ax = plt.subplots(figsize=(12, 4))
    time = ds.time.values
    edges = np.empty(len(time) + 1, dtype="datetime64[ns]")
    step = np.diff(time).min() if len(time) > 1 else np.timedelta64(1, "h")
    edges[1:] = time
    edges[0] = time[0] - step
    x0 = plt.matplotlib.dates.date2num(edges[:-1])
    x1 = plt.matplotlib.dates.date2num(edges[1:])
    bottom = ds.snowCellEtaBottom.values
    top = ds.snowCellEtaTop.values
    value = ds[variable].values * scale + offset
    active = ~np.isnan(bottom)
    vmin = np.nanmin(value[active]) if vmin is None else vmin
    vmax = np.nanmax(value[active]) if vmax is None else vmax
    norm = colors.Normalize(vmin=vmin, vmax=vmax)
    rects, values = [], []
    for k, c in zip(*np.nonzero(active)):
        rects.append(Rectangle((x0[k], bottom[k, c]), x1[k] - x0[k], top[k, c] - bottom[k, c]))
        values.append(value[k, c])
    pc = PatchCollection(rects, cmap=cmap, norm=norm, edgecolor="face", linewidth=0.2)
    pc.set_array(np.asarray(values))
    ax.add_collection(pc)
    ax.set_xlim(x0[0], x1[-1])
    ax.set_ylim(0, max(np.nanmax(top) * 1.05, 0.01))
    ax.xaxis_date()
    ax.set_ylabel("$\\eta$ [m]")
    cb = plt.colorbar(pc, ax=ax, pad=0.02)
    cb.set_label(label or f"{variable} [{ds[variable].attrs.get('units', '')}]")
    return ax


def plot_soil_profile(ds, variable="T", ax=None, cmap="RdBu_r", label=None, scale=1.0, offset=0.0, **kw):
    """A soil variable (time, depth) on the faces of the soil cells (dualDepth)."""
    if ax is None:
        _, ax = plt.subplots(figsize=(12, 4))
    time = ds.time.values
    step = np.diff(time).min() if len(time) > 1 else np.timedelta64(1, "h")
    edges = np.concatenate([[time[0] - step], time])
    mesh = ax.pcolormesh(edges, ds.dualDepth.values, (ds[variable].values * scale + offset).T, cmap=cmap,
                         shading="flat", **kw)
    ax.set_ylabel("$\\eta$ [m]")
    cb = plt.colorbar(mesh, ax=ax, pad=0.02)
    cb.set_label(label or f"{variable} [{ds[variable].attrs.get('units', '')}]")
    return ax
