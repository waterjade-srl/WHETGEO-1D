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
package org.geoframe.whetgeo1d.core.snowsoil.fidelity;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.geoframe.whetgeo1d.core.snowsoil.forcing.IncomingLongwave;
import org.geoframe.whetgeo1d.core.utils.EnumUtils;

/**
 * <b>Temporary: to be evaluated for the GEOframe components.</b> Writes the
 * forcing of a fidelity run as GEOframe OMS CSV time series, one file per
 * input of {@code SnowSoilSolver1DMain}, from a GEOtop case:
 *
 * <ul>
 * <li>from GEOtop's input files, with {@link GeotopForcing}: air temperature
 * [°C], relative humidity [%], wind [m s-1], pressure [Pa], rain and snow
 * [mm], beam and diffuse shortwave on the slope and the cosine of the snow
 * albedo, incoming longwave [W m-2];</li>
 * <li>optionally, from GEOtop's own point output (the fallback of DN-14 for
 * what the column does not compute): the bare-soil surface temperature
 * {@code Tsurface} [°C], a Dirichlet top without snow, since the column has no
 * bare-soil energy balance; the soil evaporation {@code Evap_surface} [mm],
 * a sink of the top cell without snow, since evapotranspiration comes from
 * GEOET in the GEOframe chain. Each hourly value of GEOtop holds for the steps
 * of its hour (the evaporation is shared among them).</li>
 * </ul>
 *
 * Usage: {@code GeotopForcingFiles <case.properties>}; the keys are listed in
 * {@link #run}. Times in the files are UTC, the end of each step, as the OMS
 * readers give them to the component.
 */
public final class GeotopForcingFiles {

	private static final DateTimeFormatter OMS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter GEOTOP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	private GeotopForcingFiles() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			System.err.println("usage: GeotopForcingFiles <case.properties>");
			System.exit(2);
		}
		Path file = Path.of(args[0]);
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file)) {
			p.load(r);
		}
		run(p, file.toAbsolutePath().getParent());
	}

	/**
	 * Keys (paths relative to {@code base}): {@code meteoFile},
	 * {@code dateHeader}, {@code julianDayHeader}, {@code stationHorizonFile},
	 * {@code pointHorizonFile} (empty: flat), {@code latitude},
	 * {@code longitude}, {@code standardTime}, {@code elevation}, {@code slope},
	 * {@code aspect}, {@code skyViewFactor}, {@code rainThreshold},
	 * {@code snowThreshold}, {@code rainCorrection}, {@code snowCorrection},
	 * {@code clearSkyEmissivity}, {@code firstStepEnd}
	 * and {@code lastStepEnd} (UTC, yyyy-MM-dd HH:mm), {@code timeStep} [s],
	 * {@code outputFolder}, {@code stationID}, {@code geotopPointFile}
	 * (optional: the fallbacks); optional column headers, see
	 * {@link #headers(Properties)}.
	 */
	public static void run(Properties p, Path base) throws IOException {
		GeotopMeteo meteo = GeotopMeteo.read(base.resolve(p.getProperty("meteoFile")), p.getProperty("dateHeader"),
				p.getProperty("julianDayHeader"));
		String pointHorizon = p.getProperty("pointHorizonFile", "");
		GeotopForcing forcing = new GeotopForcing(meteo, headers(p), number(p, "latitude"),
				number(p, "longitude"), number(p, "standardTime"), number(p, "elevation"), number(p, "slope"),
				number(p, "aspect"), Horizon.read(base.resolve(p.getProperty("stationHorizonFile"))),
				pointHorizon.isBlank() ? Horizon.flat() : Horizon.read(base.resolve(pointHorizon)),
				number(p, "skyViewFactor"),
				new GeotopPrecipitation(number(p, "rainThreshold"), number(p, "snowThreshold"),
						number(p, "rainCorrection"), number(p, "snowCorrection")),
				EnumUtils.fromString(IncomingLongwave.ClearSkyEmissivity.class, p.getProperty("clearSkyEmissivity")));
		long first = LocalDateTime.parse(p.getProperty("firstStepEnd"), OMS).toEpochSecond(ZoneOffset.UTC);
		long last = LocalDateTime.parse(p.getProperty("lastStepEnd"), OMS).toEpochSecond(ZoneOffset.UTC);
		long dt = (long) number(p, "timeStep");
		String id = p.getProperty("stationID", "1");

		Map<String, List<double[]>> series = new LinkedHashMap<>();
		for (String name : List.of("airTemperature", "relativeHumidity", "windSpeed", "pressure", "rainfall",
				"snowfall", "shortwaveDirect", "shortwaveDiffuse", "cosIncidence", "longwaveIn")) {
			series.put(name, new ArrayList<>());
		}
		String pointFile = p.getProperty("geotopPointFile", "");
		Map<Long, double[]> geotop = pointFile.isBlank() ? null
				: hourly(base.resolve(pointFile), number(p, "standardTime"));
		if (geotop != null) {
			series.put("surfaceTemperature", new ArrayList<>());
			series.put("evaporation", new ArrayList<>());
		}
		for (long t = first; t <= last; t += dt) {
			GeotopForcing.Step s = forcing.step(t, dt);
			add(series, "airTemperature", t, s.airTemperature());
			add(series, "relativeHumidity", t, s.relativeHumidity());
			add(series, "windSpeed", t, s.windSpeed());
			add(series, "pressure", t, s.pressure());
			add(series, "rainfall", t, s.rainfall());
			add(series, "snowfall", t, s.snowfall());
			add(series, "shortwaveDirect", t, s.shortwaveDirect());
			add(series, "shortwaveDiffuse", t, s.shortwaveDiffuse());
			add(series, "cosIncidence", t, s.cosIncidence());
			add(series, "longwaveIn", t, s.longwaveIn());
			if (geotop != null) {
				long hourEnd = Math.floorDiv(t + 3599, 3600) * 3600;
				double[] g = geotop.get(hourEnd);
				add(series, "surfaceTemperature", t, g == null ? Double.NaN : g[0]);
				add(series, "evaporation", t, g == null ? Double.NaN : Math.max(0.0, g[1]) * dt / 3600.0);
			}
		}
		Path out = base.resolve(p.getProperty("outputFolder"));
		Files.createDirectories(out);
		for (Map.Entry<String, List<double[]>> e : series.entrySet()) {
			write(out.resolve(e.getKey() + ".csv"), id, e.getValue());
		}
	}

	/** GEOtop's Tsurface [°C] and Evap_surface [mm] by the end of their hour, UTC epoch seconds. */
	private static Map<Long, double[]> hourly(Path pointFile, double standardTime) throws IOException {
		List<String> lines = Files.readAllLines(pointFile);
		List<String> header = Arrays.stream(lines.get(0).split(",")).map(String::trim).toList();
		int ts = header.indexOf("Tsurface[C]");
		int ev = header.indexOf("Evap_surface[mm]");
		Map<Long, double[]> result = new LinkedHashMap<>();
		for (String l : lines.subList(1, lines.size())) {
			if (l.isBlank()) {
				continue;
			}
			String[] r = l.split(",");
			long t = LocalDateTime.parse(r[0].trim(), GEOTOP).toEpochSecond(ZoneOffset.UTC)
					- Math.round(standardTime * 3600.0);
			result.put(t, new double[] { Double.parseDouble(r[ts].trim()), Double.parseDouble(r[ev].trim()) });
		}
		return result;
	}

	/**
	 * The column headers: GEOtop's defaults, or the optional keys
	 * {@code precipitationHeader}, {@code windSpeedHeader},
	 * {@code relativeHumidityHeader}, {@code airTemperatureHeader},
	 * {@code shortwaveGlobalHeader}, {@code longwaveInHeader} (the measured
	 * incoming longwave; absent or empty: estimated) and
	 * {@code cloudTransmissivityHeader} (absent or empty: none).
	 */
	static GeotopForcing.Headers headers(Properties p) {
		GeotopForcing.Headers d = GeotopForcing.Headers.geotop();
		String lw = p.getProperty("longwaveInHeader", "").trim();
		String tau = p.getProperty("cloudTransmissivityHeader", "").trim();
		return new GeotopForcing.Headers(p.getProperty("precipitationHeader", d.precipitation()).trim(),
				p.getProperty("windSpeedHeader", d.windSpeed()).trim(),
				p.getProperty("relativeHumidityHeader", d.relativeHumidity()).trim(),
				p.getProperty("airTemperatureHeader", d.airTemperature()).trim(),
				p.getProperty("shortwaveGlobalHeader", d.shortwaveGlobal()).trim(), lw.isEmpty() ? null : lw,
				tau.isEmpty() ? null : tau);
	}

	private static void add(Map<String, List<double[]>> series, String name, long t, double v) {
		series.get(name).add(new double[] { t, v });
	}

	private static double number(Properties p, String key) {
		String v = p.getProperty(key);
		if (v == null) {
			throw new IllegalArgumentException("missing key " + key);
		}
		return Double.parseDouble(v.trim());
	}

	/** An OMS CSV table, as the readers of the other GEOframe components take it. */
	private static void write(Path file, String id, List<double[]> rows) throws IOException {
		try (BufferedWriter w = Files.newBufferedWriter(file)) {
			w.write("@T,table\nCreated,GeotopForcingFiles\nAuthor,snowsoil.fidelity\n@H,timestamp,value_" + id
					+ "\nID,," + id + "\nType,Date,double\nFormat,yyyy-MM-dd HH:mm,\n");
			for (double[] r : rows) {
				String date = LocalDateTime.ofEpochSecond((long) r[0], 0, ZoneOffset.UTC).format(OMS);
				w.write("," + date + "," + (Double.isNaN(r[1]) ? "-9999.0" : String.format(Locale.ROOT, "%.10g", r[1]))
						+ "\n");
			}
		}
	}
}
