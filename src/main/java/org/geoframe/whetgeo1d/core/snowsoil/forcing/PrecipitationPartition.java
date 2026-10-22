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
package org.geoframe.whetgeo1d.core.snowsoil.forcing;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

/**
 * Split of total precipitation into rain and snow, for a forcing that gives
 * only the total (LINK-1). In the GEOframe chain the split is
 * {@code RainSnowSep}'s, upstream of the column; these are the same formulas
 * inside the component, so that a single .sim can run from total
 * precipitation.
 */
public sealed interface PrecipitationPartition {

	/** Rain and snow of a step [kg m-2]. */
	record Split(double rain, double snow) {
	}

	/**
	 * @param total          precipitation of the step [kg m-2]
	 * @param airTemperature [K]
	 */
	Split split(double total, double airTemperature);

	/**
	 * GEOtop 3.0's linear ramp on the air temperature ({@code part_snow},
	 * {@code meteo.cc:137-166}; called in {@code energy.balance.cc:396-399}):
	 * all snow at or below T<sub>snow</sub>, all rain at or above T<sub>rain</sub>,
	 * linear in between. GEOtop's defaults are T<sub>rain</sub> = 3 °C,
	 * T<sub>snow</sub> = -1 °C ({@code parameters.cc:1347-1348}). The option of
	 * splitting on the dew point ({@code DewTempOrNormTemp}) is not ported.
	 *
	 * @param rainThreshold T<sub>rain</sub> [K]
	 * @param snowThreshold T<sub>snow</sub> [K], below T<sub>rain</sub>
	 */
	record GeotopLinear(double rainThreshold, double snowThreshold) implements PrecipitationPartition {

		public GeotopLinear {
			if (!(rainThreshold > snowThreshold) || !(snowThreshold > 0.0)) {
				throw new IllegalArgumentException("need 0 K < T_snow < T_rain");
			}
		}

		/** GEOtop's defaults, 3 °C and -1 °C. */
		public static GeotopLinear geotop() {
			return new GeotopLinear(MELTING_TEMPERATURE + 3.0, MELTING_TEMPERATURE - 1.0);
		}

		@Override
		public Split split(double total, double airTemperature) {
			if (airTemperature <= snowThreshold) {
				return new Split(0.0, total);
			}
			if (airTemperature >= rainThreshold) {
				return new Split(total, 0.0);
			}
			double span = rainThreshold - snowThreshold;
			return new Split(total * (airTemperature - snowThreshold) / span,
					total * (rainThreshold - airTemperature) / span);
		}
	}

	/**
	 * Kavetski et al. (2006), the formula of GEOframe's {@code RainSnowSep}
	 * ({@code RainSnowSeparationPointCase}): snow fraction
	 * 1/2 - atan((T - T<sub>m</sub>)/m<sub>1</sub>)/π, with the gauge correction
	 * factors of rain and snow.
	 *
	 * @param meltingTemperature T<sub>m</sub> [K]
	 * @param smoothing          m<sub>1</sub> [K]
	 * @param rainCorrection     α<sub>r</sub>
	 * @param snowCorrection     α<sub>s</sub>
	 */
	record Kavetski(double meltingTemperature, double smoothing, double rainCorrection, double snowCorrection)
			implements PrecipitationPartition {

		public Kavetski {
			if (!(meltingTemperature > 0.0) || !(smoothing > 0.0) || !(rainCorrection > 0.0)
					|| !(snowCorrection > 0.0)) {
				throw new IllegalArgumentException("invalid Kavetski parameters");
			}
		}

		@Override
		public Split split(double total, double airTemperature) {
			double snowFraction = 0.5 - Math.atan((airTemperature - meltingTemperature) / smoothing) / Math.PI;
			return new Split(rainCorrection * total * (1.0 - snowFraction), snowCorrection * total * snowFraction);
		}
	}
}
