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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.geoframe.whetgeo1d.core.snowsoil.forcing.Horizon;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.IncomingLongwave;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The fidelity forcing tools against what GEOtop 3.0 (commit 1f1fc5af) wrote
 * in the point output of the corrected Matsch_P2_Ref_007 without canopy
 * ({@code cases/snowsoil/matsch_p2_ref_007/geotop}: dates from the Date column,
 * {@code ActualOrProjectedArea = 1}, no cloud column): each hourly row of
 * GEOtop is the mean (fluxes, states) or the sum (precipitation) of its four
 * 900 s steps, which the tools compute here one by one.
 */
public class GeotopForcingTest {

	static final Path CASE = Path.of("cases", "snowsoil", "matsch_p2_ref_007", "geotop");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	/** GEOtop's point output, by header; the first column is the date. */
	static Map<String, double[]> point;
	static LocalDateTime[] dates;
	/** Hourly aggregates of the tool's 900 s steps. */
	static Map<String, double[]> tool = new LinkedHashMap<>();

	static GeotopForcing matsch() throws IOException {
		GeotopMeteo meteo = GeotopMeteo.read(CASE.resolve("meteo0001.txt"), "Date", "JDfrom0");
		return new GeotopForcing(meteo, GeotopForcing.Headers.geotop(), 46.668, 10.579, 1.0, 1549.0, 21.4, 221.6,
				Horizon.read(CASE.resolve("horizon0001.txt")), Horizon.flat(), 1.0,
				new GeotopPrecipitation(3.0, -3.2, 1.0, 1.398), IncomingLongwave.ClearSkyEmissivity.IDSO_HODGES);
	}

	static Map<String, double[]> readPoint(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file);
		String[] header = Arrays.stream(lines.get(0).split(",")).map(String::trim).toArray(String[]::new);
		List<String[]> rows = new ArrayList<>();
		for (String l : lines.subList(1, lines.size())) {
			if (!l.isBlank()) {
				rows.add(l.split(","));
			}
		}
		Map<String, double[]> result = new LinkedHashMap<>();
		dates = new LocalDateTime[rows.size()];
		for (int c = 1; c < header.length; c++) {
			double[] v = new double[rows.size()];
			for (int k = 0; k < rows.size(); k++) {
				v[k] = Double.parseDouble(rows.get(k)[c].trim());
			}
			result.put(header[c], v);
		}
		for (int k = 0; k < rows.size(); k++) {
			dates[k] = LocalDateTime.parse(rows.get(k)[0].trim(), DATE);
		}
		return result;
	}

	@BeforeClass
	public static void computeAll() throws IOException {
		point = readPoint(CASE.resolve("output-tabs/point0001.txt"));
		GeotopForcing forcing = matsch();
		String[] names = { "Tair", "RH", "Wind", "P", "Prain", "Psnow", "SWin", "SWbeam", "SWdiff", "LWin", "LWmin", "tauAv" };
		for (String n : names) {
			tool.put(n, new double[dates.length]);
		}
		for (int k = 0; k < dates.length; k++) {
			long hourEnd = dates[k].toEpochSecond(ZoneOffset.ofHours(1));
			for (int s = 3; s >= 0; s--) {
				GeotopForcing.Step f = forcing.step(hourEnd - 900L * s, 900.0);
				add("Tair", k, f.airTemperature() / 4);
				add("RH", k, f.relativeHumidity() / 400);
				add("Wind", k, f.windSpeed() / 4);
				add("P", k, f.pressure() / 400);
				add("Prain", k, f.rainfall());
				add("Psnow", k, f.snowfall());
				add("SWin", k, (f.shortwaveDirect() + f.shortwaveDiffuse()) / 4);
				add("SWbeam", k, f.shortwaveDirect() / 4);
				add("SWdiff", k, f.shortwaveDiffuse() / 4);
				add("LWin", k, f.longwaveIn() / 4);
				add("LWmin", k, IncomingLongwave.of(IncomingLongwave.ClearSkyEmissivity.IDSO_HODGES,
						f.airTemperature() + 273.15, f.relativeHumidity() / 100, f.pressure(), 1.0, 1.0) / 4);
				add("tauAv", k, f.averagedTransmissivity() / 4);
			}
		}
	}

	private static void add(String name, int k, double v) {
		tool.get(name)[k] += v;
	}

	/** Max |tool - GEOtop| and the RMSE, printed for the report. */
	static double[] compare(String toolName, String geotopName) {
		double[] a = tool.get(toolName);
		double[] b = point.get(geotopName);
		double max = 0.0;
		double sum = 0.0;
		double total = 0.0;
		double totalTool = 0.0;
		for (int k = 0; k < a.length; k++) {
			max = Math.max(max, Math.abs(a[k] - b[k]));
			sum += (a[k] - b[k]) * (a[k] - b[k]);
			total += b[k];
			totalTool += a[k];
		}
		double rmse = Math.sqrt(sum / a.length);
		System.out.printf("%-8s vs %-28s max |diff| %10.4g  RMSE %10.4g  mean GEOtop %10.4g  mean tool %10.4g%n",
				toolName, geotopName, max, rmse, total / a.length, totalTool / a.length);
		return new double[] { max, rmse };
	}

	@Test
	public void meteorologyAsGeotopStepsIt() {
		assertTrue(compare("Tair", "Tair[C]")[0] < 1e-5);
		// GEOtop prints RH with 6 decimals
		assertTrue(compare("RH", "Relative_Humidity[-]")[0] < 1e-4);
		assertTrue(compare("P", "Pressure[mbar]")[0] < 1e-5);
		// GEOtop distributes the wind through its components: a few hundredths
		assertTrue(compare("Wind", "Wind_speed[m/s]")[1] < 0.05);
	}

	@Test
	public void precipitationSplitCorrectedAndProjectedOnce() {
		// GEOtop prints 6 decimals
		assertTrue(compare("Prain", "Prain_over_canopy[mm]")[0] < 1e-5);
		assertTrue(compare("Psnow", "Psnow_over_canopy[mm]")[0] < 1e-5);
	}

	@Test
	public void shortwaveOnTheSlope() {
		assertTrue(compare("SWin", "SWin[W/m2]")[0] < 1e-5);
		assertTrue(compare("SWbeam", "SWbeam[W/m2]")[0] < 1e-5);
		assertTrue(compare("SWdiff", "SWdiff[W/m2]")[0] < 1e-5);
	}

	@Test
	public void averagedCloudinessAsGeotopFillsIt() throws IOException {
		// GEOtop writes the series it computed back into the meteo file
		GeotopMeteo filled = GeotopMeteo.read(CASE.resolve("meteo0001_cloud_filled_by_geotop.txt"), "Date", "JDfrom0");
		double[] geotop = filled.column("CloudTrans");
		double[] ours = matsch().averagedCloudTransmissivity();
		double max = 0.0;
		int defined = 0;
		// the file of the case is cut on 4 November, GEOtop's ran on to 2015: the last night and day differ
		int end = ours.length - 48;
		for (int k = 0; k < end; k++) {
			assertEquals("record " + k, Double.isNaN(geotop[k]), Double.isNaN(ours[k]));
			if (!Double.isNaN(geotop[k])) {
				max = Math.max(max, Math.abs(geotop[k] - ours[k]));
				defined++;
			}
		}
		System.out.printf("averaged cloudiness: %d records, max |diff| %.3g%n", defined, max);
		assertTrue(defined > 700 && max < 1e-5);
	}

	@Test
	public void longwaveIdsoHodgesWithShortwaveClouds() {
		assertTrue(compare("LWmin", "LWin_min[W/m2]")[0] < 1e-5);
		assertTrue(compare("LWin", "LWin[W/m2]")[0] < 1e-5);
	}

	@Test
	public void julianDayOfGeotop() {
		assertEquals(734048.0, GeotopMeteo.julianDay(LocalDateTime.of(2009, 10, 2, 0, 0)), 0.0);
	}
}
