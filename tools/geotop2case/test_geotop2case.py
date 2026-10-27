"""Tests of geotop2case.py on a small synthetic GEOtop point run (python3 -m pytest tools/geotop2case)."""
import math
import os
import sqlite3

import pytest

import geotop2case as g

INPTS = """! a synthetic point run
PointSim = 1
InitDateDDMMYYYYhhmm = 01/01/2020 00:00
EndDateDDMMYYYYhhmm = 01/01/2020 03:00
StandardTimeSimulation = 1
Latitude = 46.0
Longitude = 11.0
CoordinatePointX = 650000
CoordinatePointY = 5100000
PointElevation = 1500
PointSlope = 30
PointAspect = 180  ! south
HorizonPointFile = "hor"
HeaderHorizonAngle = "az"
HeaderHorizonHeight = "h"
MeteoStationWindVelocitySensorHeight = 4
MeteoFile = "meteo"
HeaderDateDDMMYYYYhhmmMeteo = "Date"
HeaderIPrec = "Iprec"
HeaderAirTemp = "AirT"
HeaderAirPress = "AirP"
SoilParFile = "soil/soil"
HeaderSoilDz = "Dz"
HeaderThetaSat = "sat"
HeaderAlpha = "a"
ThermalCapacitySoilSolids = 2.0E6
InitWaterTableDepth = 500
InitSoilTemp = 2
"""
SOIL = "Dz,sat,a\n100,0.45,0.002\n200,,0.002\n"  # the second layer's sat falls back to the keyword default
HORIZON = "az,h\n90,10\n270,30\n"
METEO = ("Date,Iprec,AirT,AirP\n01/01/2020 00:00,0,1.0,850\n01/01/2020 01:00,2,1.5,851\n"
         "01/01/2020 03:00,1,-9999,852\n01/01/2020 04:00,0,0.5,853\n")


@pytest.fixture
def run(tmp_path):
    d = tmp_path / "run"
    (d / "soil").mkdir(parents=True)
    (d / "geotop.inpts").write_text(INPTS)
    (d / "soil" / "soil0001.txt").write_text(SOIL)
    (d / "hor0001.txt").write_text(HORIZON)
    (d / "meteo0001.txt").write_text(METEO)
    return d


def test_point_from_the_keywords(run):
    p = g.Run(str(run)).points()
    assert len(p) == 1
    p = p[0]
    assert (p["x"], p["y"], p["z"], p["slope"], p["aspect"]) == (650000, 5100000, 1500, 30, 180)
    assert (p["sky"], p["lat"], p["lon"], p["id"], p["horizon"]) == (1.0, 46.0, 11.0, 1.0, 1.0)
    assert g.Run(str(run)).sensor_heights() == (4.0, 2.0)


def test_soil_cascade(run):
    pa, water_table = g.Run(str(run)).soil()
    assert pa["dz"] == [100.0, 200.0]
    assert pa["sat"] == [0.45, 0.5]  # file, then GEOtop's default
    assert pa["ct"] == [2.0e6, 2.0e6]  # keyword
    assert pa["n"] == [1.3, 1.3]  # default
    assert water_table == 500.0


def test_whetgeo_soil(run):
    pa, water_table = g.Run(str(run)).soil()
    grid, params, ic = g.whetgeo_soil(pa, water_table, 30.0)
    assert grid == [("L", 0.0, 1, 0, 1), ("L", -0.1, 1, 0, 2), ("L", -0.3, None, None, None)]
    assert params[0][:8] == (2000.0, 2.5, 1000.0, 0.45, 0.05, 273.15, 2.0, 1.3)
    assert params[0][10] == pytest.approx(1e-7)
    c = math.cos(math.radians(30.0))
    expected = [(0.0, -0.5 * c), (-0.05, (-500 + 50) * c / 1000), (-0.2, (-500 + 200) * c / 1000),
                (-0.3, (-500 + 300) * c / 1000)]
    for (eta, psi, t), (e, p) in zip(ic, expected):
        assert eta == pytest.approx(e) and psi == pytest.approx(p) and t == pytest.approx(275.15)


def test_meteo_in_utc(run):
    m = g.Run(str(run)).meteo()
    t = sorted(m["airTemperature"])
    # simulation and station in UTC+1: the run goes from 31/12 23:00 to 01/01 02:00 UTC, the records after
    # the start up to the end
    assert [x.strftime("%d %H") for x in t] == ["01 00", "01 02"]
    assert [m["airTemperature"][x] for x in t] == [1.5, g.NOVALUE]
    assert [m["precipitation"][x] for x in t] == [2.0, 2.0]  # intensity times the interval [h]
    assert m["pressure"][t[0]] == 85100.0


def test_geopackage(run, tmp_path):
    out = tmp_path / "case.gpkg"
    g.main([str(out), str(run), "--epsg", "32632", "--series", str(tmp_path / "series")])
    with sqlite3.connect(out) as c:
        assert c.execute("SELECT id, point_id, profile_id, ic_id FROM columns").fetchall() == [(1, 1, 1, 1)]
        h = c.execute("SELECT azimuth, height FROM horizon ORDER BY azimuth").fetchall()
        assert len(h) == 16 and h[4] == (90.0, 10.0) and h[0] == (0.0, 20.0) and h[12] == (270.0, 30.0)
        svf, svf_geotop = c.execute("SELECT svf, svf_geotop FROM points").fetchone()
        assert svf_geotop == 1.0 and 0.5 < svf < (1 + math.cos(math.radians(30))) / 2
        assert c.execute("SELECT data_type FROM gpkg_contents WHERE table_name = 'columns'").fetchone() == (
            "attributes",)
    assert sorted(os.listdir(tmp_path / "series")) == ["airTemperature.csv", "precipitation.csv", "pressure.csv"]


def test_forcing_sets(run, tmp_path):
    """Two forcing sets at the same point make one point with two columns; another point stays apart."""
    other = tmp_path / "other"
    other.mkdir()
    for f in ("soil", "hor0001.txt", "meteo0001.txt"):
        os.symlink(run / f, other / f)
    (other / "geotop.inpts").write_text(INPTS.replace("PointSlope = 30", "PointSlope = 20"))
    out = tmp_path / "case.gpkg"
    series = tmp_path / "series"
    g.main([str(out), str(run), str(run), str(other), "--epsg", "32632", "--names", "A,B",
            "--forcing", "ERA5,JRA3Q,ERA5", "--series", str(series)])
    with sqlite3.connect(out) as c:
        assert c.execute("SELECT id, point_id, forcing, note FROM columns ORDER BY id").fetchall() == [
            (1, 1, "ERA5", "A ERA5"), (2, 1, "JRA3Q", "A JRA3Q"), (3, 2, "ERA5", "B ERA5")]
        assert c.execute("SELECT id, name, slope FROM points ORDER BY id").fetchall() == [(1, "A", 30.0),
                                                                                          (2, "B", 20.0)]
        assert c.execute("SELECT COUNT(*) FROM horizon").fetchone() == (32,)
    assert sorted(os.listdir(series)) == ["ERA5", "JRA3Q"]
    header = lambda s: (series / s / "airTemperature.csv").read_text().splitlines()[3]
    assert header("ERA5") == "@H,timestamp,value_1,value_2"
    assert header("JRA3Q") == "@H,timestamp,value_1"
    with pytest.raises(SystemExit):  # the same set twice at the same point
        g.main([str(out), str(run), str(run), "--epsg", "32632", "--forcing", "ERA5,ERA5", "--series", str(series)])


def test_initial_snow(run, tmp_path):
    with open(run / "geotop.inpts", "a") as f:
        f.write("InitSWE = 23.78\nInitSnowDensity = 208.72\nInitSnowTemp = -21.47\n")
    out = tmp_path / "case.gpkg"
    g.main([str(out), str(run), "--epsg", "32632"])
    with sqlite3.connect(out) as c:
        swe, rho, t, age = c.execute("SELECT swe, density, temperature, age FROM snow_initial WHERE ic_id = 1").fetchone()
    assert (swe, rho, age) == (23.78, 208.72, 0.0) and t == pytest.approx(273.15 - 21.47)


def test_svf_of_run(run, tmp_path):
    out = tmp_path / "case.gpkg"
    g.main([str(out), str(run), "--epsg", "32632", "--svf-of-run"])
    with sqlite3.connect(out) as c:
        assert c.execute("SELECT svf, svf_geotop FROM points").fetchone() == (1.0, 1.0)


def test_flat_ground_sees_the_whole_sky():
    assert g.sky_view_factor([0.0], [0.0], 0.0, 0.0) == pytest.approx(1.0)
    assert g.sky_view_factor([0.0], [0.0], 30.0, 120.0) == pytest.approx((1 + math.cos(math.radians(30))) / 2)


def test_unsupported(run):
    with open(run / "geotop.inpts", "a") as f:
        f.write("SoilLayerTypes = 2\n")
    with pytest.raises(SystemExit):
        g.Run(str(run))
