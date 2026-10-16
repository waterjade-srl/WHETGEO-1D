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
package org.geoframe.whetgeo1d.core.snowsoil.verification;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;

/**
 * Hourly forcing taken from the point output of a GEOtop run
 * ({@code PointOutputFile}, {@code DtPlotPoint = 1}), to drive the snow+soil
 * column with what GEOtop's own surface saw: precipitation already split and
 * corrected, under the canopy; air temperature, humidity, wind, pressure;
 * beam and diffuse shortwave and incoming longwave as GEOtop computed them.
 * The solar incidence on flat ground is computed here, at the middle of each
 * step (Spencer 1971 declination and equation of time).
 *
 * <p>
 * Each row of the file closes the hour that ends at its date. Also kept: the
 * soil surface temperature GEOtop computed, for the snow-free top of the soil,
 * and GEOtop's snow, for the comparison.
 *
 * @param end                  end of each step, local standard time
 * @param snowfall             [kg m-2]
 * @param rainfall             [kg m-2]
 * @param airTemperature       [K]
 * @param relativeHumidity     [-]
 * @param windSpeed            [m s-1]
 * @param pressure             [Pa]
 * @param shortwaveBeam        [W m-2]
 * @param shortwaveDiffuse     [W m-2]
 * @param longwaveIn           [W m-2]
 * @param surfaceTemperature   GEOtop's surface temperature [K]
 * @param snowDepth            GEOtop's snow depth [m]
 * @param snowWaterEquivalent  GEOtop's SWE [kg m-2]
 * @param evaporation          GEOtop's evaporation from the soil surface in
 *                             the step [kg m-2]
 * @param transpiration        GEOtop's transpiration in the step [kg m-2]
 */
public record GeotopPointForcing(LocalDateTime[] end, double[] snowfall, double[] rainfall, double[] airTemperature,
		double[] relativeHumidity, double[] windSpeed, double[] pressure, double[] shortwaveBeam,
		double[] shortwaveDiffuse, double[] longwaveIn, double[] surfaceTemperature, double[] snowDepth,
		double[] snowWaterEquivalent, double[] evaporation, double[] transpiration) {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	/** Reads GEOtop's point file, every row. */
	public static GeotopPointForcing read(Path pointFile) throws IOException {
		List<String> lines = Files.readAllLines(pointFile);
		List<String> header = Arrays.stream(lines.get(0).split(",")).map(String::trim).toList();
		int date = 0;
		int snow = header.indexOf("Psnow_under_canopy[mm]");
		int rain = header.indexOf("Prain_under_canopy[mm]");
		int ta = header.indexOf("Tair[C]");
		int rh = header.indexOf("Relative_Humidity[-]");
		int wind = header.indexOf("Wind_speed[m/s]");
		int p = header.indexOf("Pressure[mbar]");
		int beam = header.indexOf("SWbeam[W/m2]");
		int diff = header.indexOf("SWdiff[W/m2]");
		int lw = header.indexOf("LWin[W/m2]");
		int ts = header.indexOf("Tsurface[C]");
		int hs = header.indexOf("snow_depth[mm]");
		int swe = header.indexOf("snow_water_equivalent[mm]");
		int evap = header.indexOf("Evap_surface[mm]");
		int trasp = header.indexOf("Trasp_canopy[mm]");
		List<String[]> rows = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			if (!line.isBlank()) {
				rows.add(line.split(","));
			}
		}
		int n = rows.size();
		GeotopPointForcing f = new GeotopPointForcing(new LocalDateTime[n], new double[n], new double[n],
				new double[n], new double[n], new double[n], new double[n], new double[n], new double[n],
				new double[n], new double[n], new double[n], new double[n], new double[n], new double[n]);
		for (int k = 0; k < n; k++) {
			String[] r = rows.get(k);
			f.end[k] = LocalDateTime.parse(r[date].trim(), DATE);
			f.snowfall[k] = value(r, snow);
			f.rainfall[k] = value(r, rain);
			f.airTemperature[k] = value(r, ta) + MELTING_TEMPERATURE;
			f.relativeHumidity[k] = Math.min(1.0, Math.max(0.0, value(r, rh)));
			f.windSpeed[k] = value(r, wind);
			f.pressure[k] = 100.0 * value(r, p);
			f.shortwaveBeam[k] = Math.max(0.0, value(r, beam));
			f.shortwaveDiffuse[k] = Math.max(0.0, value(r, diff));
			f.longwaveIn[k] = value(r, lw);
			f.surfaceTemperature[k] = value(r, ts) + MELTING_TEMPERATURE;
			f.snowDepth[k] = 1e-3 * value(r, hs);
			f.snowWaterEquivalent[k] = value(r, swe);
			f.evaporation[k] = Math.max(0.0, value(r, evap));
			f.transpiration[k] = Math.max(0.0, value(r, trasp));
		}
		return f;
	}

	private static double value(String[] fields, int column) {
		return Double.parseDouble(fields[column].trim());
	}

	/**
	 * GEOtop's evapotranspiration of step k as sinks of the cells of a layered
	 * soil, bottom first [kg m-2]: the evaporation from the top cell, the
	 * transpiration spread over the root zone in proportion to the thickness of
	 * each cell inside it, as GEOtop's {@code root} ({@code vegetation.cc:659},
	 * flat ground). GEOtop also weights it by the water stress of each layer;
	 * here it is not.
	 *
	 * @param rootDepth [m]
	 */
	public double[] sinks(int k, LayeredSoil soil, double rootDepth) {
		int n = soil.cells();
		double[] sinks = new double[n];
		sinks[n - 1] = evaporation[k];
		double top = 0.0;
		for (int i = n - 1; i >= 0 && top < rootDepth; i--) {
			double bottom = top + soil.cellSize(i);
			sinks[i] += transpiration[k] * (Math.min(bottom, rootDepth) - top) / rootDepth;
			top = bottom;
		}
		return sinks;
	}

	public int steps() {
		return end.length;
	}

	/**
	 * Surface forcing of step k, with the cosine of the solar zenith at the
	 * middle of the step on flat ground.
	 *
	 * @param latitude     [deg]
	 * @param longitude    [deg], east positive
	 * @param standardTime offset of the local standard time from UTC [h]
	 * @param timeDelta    step [s]
	 */
	public SurfaceForcing surface(int k, double latitude, double longitude, double standardTime, double timeDelta) {
		LocalDateTime middle = end[k].minusSeconds((long) (0.5 * timeDelta));
		double cos = cosZenith(middle, latitude, longitude, standardTime);
		double beam = shortwaveBeam[k];
		if (beam > 0.0 && cos <= 0.0) {
			// the hour holds a sliver of daylight but its middle is below the horizon
			cos = 1e-3;
		}
		return new SurfaceForcing(beam, shortwaveDiffuse[k], Math.max(0.0, Math.min(1.0, cos)), longwaveIn[k],
				airTemperature[k], relativeHumidity[k], windSpeed[k], pressure[k]);
	}

	/** Cosine of the solar zenith angle (Spencer 1971). */
	public static double cosZenith(LocalDateTime localStandard, double latitude, double longitude,
			double standardTime) {
		double hours = localStandard.getHour() + localStandard.getMinute() / 60.0 + localStandard.getSecond() / 3600.0;
		double gamma = 2.0 * Math.PI / 365.0 * (localStandard.getDayOfYear() - 1 + (hours - 12.0) / 24.0);
		double declination = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
				- 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma) - 0.002697 * Math.cos(3 * gamma)
				+ 0.00148 * Math.sin(3 * gamma);
		double equationOfTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
				- 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
		double solarMinutes = hours * 60.0 + equationOfTime + 4.0 * (longitude - 15.0 * standardTime);
		double hourAngle = Math.toRadians(solarMinutes / 4.0 - 180.0);
		double phi = Math.toRadians(latitude);
		return Math.sin(phi) * Math.sin(declination) + Math.cos(phi) * Math.cos(declination) * Math.cos(hourAngle);
	}
}
