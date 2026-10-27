/*
 * GNU GPL v3 License
 *
 * Copyright 2026 WHETGEO-1D contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.geoframe.whetgeo1d.solvers;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.geoframe.whetgeo1d.core.snowsoil.forcing.ClearSkyTransmissivity;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.CloudTransmissivity;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.Horizon;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.SlopeShortwave;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.SolarGeometry;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.StandardAtmosphere;
import org.geoframe.whetgeo1d.io.snowsoil.CaseGeoPackage;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.hortonmachine.gears.libs.modules.HMModel;

import oms3.annotations.Author;
import oms3.annotations.Description;
import oms3.annotations.Execute;
import oms3.annotations.In;
import oms3.annotations.Keywords;
import oms3.annotations.License;
import oms3.annotations.Out;
import oms3.annotations.Unit;

/**
 * Shortwave on the terrain of a station, the input of the snow and soil column
 * ({@link SnowSoilSolver1DMain}) for an operational run (LINK-1), computed as
 * GEOtop 3.0 does for a point ({@link SlopeShortwave}):
 *
 * <ul>
 * <li>cloud transmissivity τ<sub>c</sub> of the step: given
 * ({@code inCloudTransmissivity}, clamped to [0, 1]), or from the measured
 * global shortwave on the horizontal ({@code inShortwaveGlobal}, GEOtop's
 * {@code find_tau_cloud_station} for a flat station);</li>
 * <li>Iqbal's clear sky times τ<sub>c</sub>, split into beam and diffuse with
 * Erbs; the beam on the slope, zero in the shadow of the horizon; the diffuse
 * times the sky view factor V;</li>
 * <li>the cosine passed to the snow albedo, k<sub>d</sub> ⟨sin α⟩ +
 * (1 - k<sub>d</sub>) ⟨cos i⟩;</li>
 * <li>the solar hour from the longitude and the standard time of the dates.</li>
 * </ul>
 *
 * With a measured global shortwave and no τ<sub>c</sub> (sun too low), the
 * measurement goes to the diffuse, times V.
 *
 * <p>
 * The terrain terms that depend on the surface stay in the column: the
 * emission to the sky and the reflection from the terrain, through its
 * {@code skyViewFactor}, which must be the station's V. Precipitation per unit
 * area of the slope is the column's {@code slope}.
 *
 * <p>
 * <b>Geometry.</b> From the station shapefile ({@code inStations}), one
 * feature per station, with the fields named by {@code fStations...}; a
 * field name left empty takes the scalar parameter instead (e.g. slope 0 and
 * V = 1 for a stake on a flat field, with its horizon). Without a shapefile every station takes the
 * scalar parameters. The horizon of each station comes from an OMS table
 * ({@code horizonFile}: a column {@code azimuth} and one {@code value_<id>}
 * per station, NivoGEO-1D's format); without it the horizon is flat.
 *
 * <p>
 * Outputs, keyed by station ID as the inputs: {@code outShortwaveDirect},
 * {@code outShortwaveDiffuse} [W m-2], {@code outCosIncidence} [-] and
 * {@code outCloudTransmissivity} [-], for the column's
 * {@code inShortwaveDirect}, {@code inShortwaveDiffuse}, {@code inCosIncidence}
 * and {@code inCloudTransmissivity} (the longwave estimate).
 */
@Description("Shortwave on the slope of each station (beam, diffuse times the sky view factor, incidence cosine) from a cloud transmissivity or a measured global shortwave, with the horizon, as GEOtop 3.0 for a point.")
@Author(name = "WHETGEO-1D contributors (GEO2GEO)", contact = "")
@Keywords("Shortwave, Slope, Horizon, Sky view factor, GEOtop")
@License("General Public License Version 3 (GPLv3)")
public class TerrainShortwaveMain extends HMModel {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	@Description("Stations: one feature per station with its geometry; empty: the scalar parameters.")
	@In
	public SimpleFeatureCollection inStations;
	@Description("Field of the station ID.")
	@In
	public String fStationsid = "id";
	@Description("Field of the slope [deg]; empty: the parameter slope.")
	@In
	public String fStationsSlope = "slope";
	@Description("Field of the aspect [deg from north, clockwise]; empty: the parameter aspect.")
	@In
	public String fStationsAspect = "aspect";
	@Description("Field of the sky view factor [-]; empty: the parameter skyViewFactor.")
	@In
	public String fStationsSkyViewFactor = "svf";
	@Description("Field of the latitude [deg]; empty: the parameter latitude.")
	@In
	public String fStationsLatitude = "";
	@Description("Field of the longitude [deg, east positive]; empty: the parameter longitude.")
	@In
	public String fStationsLongitude = "";
	@Description("Field of the elevation [m], for the pressure where it is missing; empty: the parameter elevation.")
	@In
	public String fStationsElevation = "";

	@Description("Case GeoPackage (CaseGeoPackage): the geometry and horizon of each station from its points and horizon tables, the station ID being the point id; in place of inStations and horizonFile.")
	@In
	public String caseFile = "";

	@Description("Horizon of the stations, OMS table: azimuth [deg] and one value_<id> column [deg] per station; empty: flat.")
	@In
	public String horizonFile = "";

	@In
	@Unit("deg")
	public double latitude;
	@Description("East positive.")
	@In
	@Unit("deg")
	public double longitude;
	@In
	@Unit("m")
	public double elevation = 0.0;
	@In
	@Unit("deg")
	public double slope = 0.0;
	@Description("From north, clockwise.")
	@In
	@Unit("deg")
	public double aspect = 0.0;
	@In
	@Unit("-")
	public double skyViewFactor = 1.0;

	@Description("Offset from UTC of the dates of the inputs [h]; 0 for UTC.")
	@In
	@Unit("h")
	public double standardTime = 0.0;
	@Description("Step length")
	@In
	@Unit("s")
	public double tTimeStep = 3600.0;
	@Description("End of the step, yyyy-MM-dd HH:mm, as the OMS readers give it")
	@In
	public String inCurrentDate;

	@In
	@Unit("°C")
	public Map<Integer, double[]> inAirTemperature;
	@In
	@Unit("%")
	public Map<Integer, double[]> inRelativeHumidity;
	@Description("Missing: the standard atmosphere at the elevation.")
	@In
	@Unit("Pa")
	public Map<Integer, double[]> inPressure;
	@In
	@Unit("-")
	public Map<Integer, double[]> inCloudTransmissivity;
	@Description("Measured on the horizontal, used where no cloud transmissivity is given.")
	@In
	@Unit("W m-2")
	public Map<Integer, double[]> inShortwaveGlobal;

	@Description("Beam on the slope.")
	@Out
	@Unit("W m-2")
	public HashMap<Integer, double[]> outShortwaveDirect;
	@Description("Diffuse, times the sky view factor.")
	@Out
	@Unit("W m-2")
	public HashMap<Integer, double[]> outShortwaveDiffuse;
	@Description("Cosine of the incidence for the snow albedo.")
	@Out
	@Unit("-")
	public HashMap<Integer, double[]> outCosIncidence;
	@Description("Cloud transmissivity of the step, NaN (-9999) where undefined.")
	@Out
	@Unit("-")
	public HashMap<Integer, double[]> outCloudTransmissivity;

	/** Geometry of a station. */
	record Station(SolarGeometry site, SlopeShortwave shortwave, CloudTransmissivity clouds, double elevation,
			double skyViewFactor) {
	}

	private Map<Integer, Station> stations;

	@Execute
	public void process() throws IOException {
		if (stations == null) {
			stations = new HashMap<>();
		}
		long end = LocalDateTime.parse(inCurrentDate, DATE).toEpochSecond(ZoneOffset.UTC)
				- Math.round(standardTime * 3600.0);
		outShortwaveDirect = new HashMap<>();
		outShortwaveDiffuse = new HashMap<>();
		outCosIncidence = new HashMap<>();
		outCloudTransmissivity = new HashMap<>();
		for (Integer id : inAirTemperature.keySet()) {
			Station s = stations.containsKey(id) ? stations.get(id) : station(id);
			stations.put(id, s);
			double[] r = shortwave(s, end, value(inAirTemperature, id), value(inRelativeHumidity, id),
					value(inPressure, id), value(inCloudTransmissivity, id), value(inShortwaveGlobal, id));
			outShortwaveDirect.put(id, new double[] { r[0] });
			outShortwaveDiffuse.put(id, new double[] { r[1] });
			outCosIncidence.put(id, new double[] { r[2] });
			outCloudTransmissivity.put(id, new double[] { Double.isNaN(r[3]) ? -9999.0 : r[3] });
		}
	}

	/** Beam, diffuse, cosine and τ<sub>c</sub> of the step that ends at {@code end} (UTC epoch seconds). */
	double[] shortwave(Station s, long end, double airTemperature, double relativeHumidity, double pressure,
			double cloudTransmissivity, double global) {
		if (Double.isNaN(airTemperature) || Double.isNaN(relativeHumidity)) {
			throw new IllegalArgumentException("air temperature and relative humidity are needed for the shortwave");
		}
		double ta = airTemperature + MELTING_TEMPERATURE;
		double rh = Math.max(0.0, Math.min(1.0, relativeHumidity / 100.0));
		double p = Double.isNaN(pressure) ? StandardAtmosphere.pressure(s.elevation()) : pressure;
		double jdEnd = s.site().julianDay(end);
		double jdBegin = jdEnd - tTimeStep / 86400.0;
		double tau = cloudTransmissivity;
		if (Double.isNaN(tau)) {
			if (Double.isNaN(global)) {
				throw new IllegalArgumentException("a cloud transmissivity or a global shortwave is needed");
			}
			global = Math.max(0.0, global);
			tau = s.clouds().fromGlobal(jdBegin, jdEnd, 0.5 * (jdBegin + jdEnd), global, p, Math.max(rh, 0.01), ta);
			if (Double.isNaN(tau)) {
				return new double[] { 0.0, s.skyViewFactor() * global, 0.0, Double.NaN };
			}
		}
		tau = Math.max(0.0, Math.min(1.0, tau));
		SlopeShortwave.Result r = s.shortwave().step(jdBegin, jdEnd, tau, p, rh, ta);
		return new double[] { r.beam(), r.diffuse(), Math.max(0.0, Math.min(1.0, r.cosIncidence())), tau };
	}

	private Station station(int id) throws IOException {
		if (caseFile != null && !caseFile.isBlank()) {
			return caseStation(id);
		}
		double lat = latitude;
		double lon = longitude;
		double z = elevation;
		double beta = slope;
		double psi = aspect;
		double v = skyViewFactor;
		if (inStations != null) {
			SimpleFeature f = feature(id);
			beta = field(f, fStationsSlope, beta);
			psi = field(f, fStationsAspect, psi);
			v = field(f, fStationsSkyViewFactor, v);
			lat = field(f, fStationsLatitude, lat);
			lon = field(f, fStationsLongitude, lon);
			z = field(f, fStationsElevation, z);
		}
		if (!(v > 0.0 && v <= 1.0)) {
			throw new IllegalArgumentException("station " + id + ": sky view factor must be in (0, 1], got " + v);
		}
		Horizon horizon = horizonFile == null || horizonFile.isBlank() ? Horizon.flat()
				: Horizon.readTable(Path.of(horizonFile), id);
		SolarGeometry site = SolarGeometry.ofDegrees(lat, lon, standardTime);
		ClearSkyTransmissivity clearSky = ClearSkyTransmissivity.geotop();
		return new Station(site,
				new SlopeShortwave(site, clearSky, Math.toRadians(beta), Math.toRadians(psi), v, horizon),
				new CloudTransmissivity(site, clearSky, 1.0, 0.0), z, v);
	}

	private Station caseStation(int id) {
		CaseGeoPackage file = new CaseGeoPackage(Path.of(caseFile));
		CaseGeoPackage.Point p = file.point(id);
		double v = file.skyViewFactor(p);
		SolarGeometry site = SolarGeometry.ofDegrees(p.latitude(), p.longitude(), standardTime);
		ClearSkyTransmissivity clearSky = ClearSkyTransmissivity.geotop();
		return new Station(site,
				new SlopeShortwave(site, clearSky, Math.toRadians(p.slope()), Math.toRadians(p.aspect()), v,
						file.horizon(id)),
				new CloudTransmissivity(site, clearSky, 1.0, 0.0), p.elevation(), v);
	}

	private SimpleFeature feature(int id) {
		try (SimpleFeatureIterator it = inStations.features()) {
			while (it.hasNext()) {
				SimpleFeature f = it.next();
				if ((int) Math.round(number(f, fStationsid)) == id) {
					return f;
				}
			}
		}
		throw new IllegalArgumentException("station " + id + " is not in inStations");
	}

	/** The field of the feature, or the parameter where the field name is empty. */
	private static double field(SimpleFeature f, String name, double parameter) {
		return name == null || name.isBlank() ? parameter : number(f, name);
	}

	private static double number(SimpleFeature f, String field) {
		Object v = f.getAttribute(field);
		if (!(v instanceof Number n)) {
			throw new IllegalArgumentException("field " + field + " missing or not a number in " + f.getID());
		}
		return n.doubleValue();
	}

	private static double value(Map<Integer, double[]> series, int id) {
		if (series == null || !series.containsKey(id)) {
			return Double.NaN;
		}
		double v = series.get(id)[0];
		return v == -9999.0 ? Double.NaN : v;
	}
}
