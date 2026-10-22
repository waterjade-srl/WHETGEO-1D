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

/**
 * <b>Temporary: to be evaluated for the GEOframe components.</b> GEOtop 3.0's
 * rain and snow of a point from the measured precipitation intensity, in its
 * two passes:
 *
 * <ol>
 * <li>at the station ({@code get_precipitation}, {@code meteodistr.cc:450-461}):
 * the intensity is split on the station temperature with the linear ramp of
 * {@code part_snow} ({@code meteo.cc:137-166}) and corrected,
 * P' = c<sub>rain</sub> P<sub>rain</sub> + c<sub>snow</sub> P<sub>snow</sub>
 * ({@code RainCorrFactor}, {@code SnowCorrFactor}); the elevation factor is 1
 * for a point at the station's elevation;</li>
 * <li>at the point ({@code energy.balance.cc:380-400}): P' times the step in
 * hours, split again with the same ramp on the point temperature (or on the
 * dew point with {@code DewTempOrNormTemp = 1}).</li>
 * </ol>
 *
 * With one station at the point's elevation the two temperatures are the
 * same, and the corrected total is split again: the rain of the step is
 * f<sub>rain</sub> (c<sub>rain</sub> f<sub>rain</sub> + c<sub>snow</sub>
 * f<sub>snow</sub>) P, not c<sub>rain</sub> f<sub>rain</sub> P. The snow
 * reduction on steep slopes ({@code snow_curv}) is off by default and not
 * ported. Over and under the canopy are the same here (no canopy).
 *
 * @param rainThreshold   T<sub>rain</sub> [°C] ({@code ThresTempRain})
 * @param snowThreshold   T<sub>snow</sub> [°C] ({@code ThresTempSnow})
 * @param rainCorrection  c<sub>rain</sub> ({@code RainCorrFactor})
 * @param snowCorrection  c<sub>snow</sub> ({@code SnowCorrFactor})
 */
public record GeotopPrecipitation(double rainThreshold, double snowThreshold, double rainCorrection,
		double snowCorrection) {

	public GeotopPrecipitation {
		if (!(rainThreshold > snowThreshold) || !(rainCorrection >= 0.0) || !(snowCorrection >= 0.0)) {
			throw new IllegalArgumentException("need T_snow < T_rain and non-negative corrections");
		}
	}

	/** GEOtop's defaults: 3 °C, -1 °C, no correction. */
	public static GeotopPrecipitation geotop() {
		return new GeotopPrecipitation(3.0, -1.0, 1.0, 1.0);
	}

	/** Rain and snow of a step [mm]. */
	public record Split(double rain, double snow) {
	}

	/** Snow fraction of {@code part_snow} at T [°C]. */
	public double snowFraction(double temperature) {
		if (temperature <= snowThreshold) {
			return 1.0;
		}
		if (temperature >= rainThreshold) {
			return 0.0;
		}
		return (rainThreshold - temperature) / (rainThreshold - snowThreshold);
	}

	/**
	 * @param intensity   measured precipitation intensity [mm h-1], ≥ 0
	 * @param temperature air (or dew point) temperature [°C], station and point
	 * @param timeStep    Δt [s]
	 */
	public Split split(double intensity, double temperature, double timeStep) {
		double fs = snowFraction(temperature);
		double corrected = rainCorrection * (1.0 - fs) * intensity + snowCorrection * fs * intensity;
		double total = corrected * timeStep / 3600.0;
		return new Split(total * (1.0 - fs), total * fs);
	}
}
