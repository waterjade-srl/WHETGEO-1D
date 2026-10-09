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
package org.geoframe.whetgeo1d.snow;

/**
 * Non-dimensional age of the snow surface, the only use of snow age in GEOtop:
 * it drives the decay of the albedo. It is one value per column, not per cell,
 * and the restratification never touches it.
 *
 * <p>
 * Port of {@code update_snow_age} and {@code non_dimensionalize_snowage} of
 * GEOtop 3.0 ({@code snow.cc:865-882, 967-978}, commit 1f1fc5af), after
 * Dickinson et al. (1993) as used in BATS: the age grows at a rate set by the
 * temperature of the surface snow (vapour-driven grain growth, melt and
 * refreezing, dirt), and snowfall restores it, completely after
 * {@link #DEFAULT_RESTORING_SNOWFALL} of fresh snow.
 *
 * <pre>
 * r1  = exp(5000 (1/273.15 - 1/T))
 * r2  = min(r1^10, 1)
 * r3  = 0.3
 * age = max(0, (age + (r1 + r2 + r3) Δt 10⁻⁶) (1 - P_snow / P_restore))
 * </pre>
 *
 * GEOtop updates the age only while there is snow on the ground, with the
 * temperature of the top snow cell at the start of the step; the caller does
 * the same.
 */
public final class SurfaceSnowAge {

	/** Snowfall that fully restores the fresh-snow albedo [kg m-2] (GEOtop keyword 400, 10 mm). */
	public static final double DEFAULT_RESTORING_SNOWFALL = 10.0;

	/** Reference temperature of the growth rate [K], GEOtop {@code tk}. */
	private static final double REFERENCE_TEMPERATURE = 273.15;

	/** Reference used by GEOtop when converting the initial age, {@code 273.16} in the source. */
	private static final double INITIAL_REFERENCE_TEMPERATURE = 273.16;

	private SurfaceSnowAge() {
	}

	/**
	 * Age at the end of a step.
	 *
	 * @param age                current non-dimensional age [-]
	 * @param snowfall           snowfall over the step [kg m-2]
	 * @param surfaceTemperature temperature of the top snow cell [K]
	 * @param timeDelta          step length [s]
	 * @param restoringSnowfall  snowfall that fully restores the age [kg m-2]
	 */
	public static double advance(double age, double snowfall, double surfaceTemperature, double timeDelta,
			double restoringSnowfall) {
		if (!(restoringSnowfall > 0.0)) {
			throw new IllegalArgumentException("restoringSnowfall must be positive, got " + restoringSnowfall);
		}
		double grown = age + growthRate(surfaceTemperature, REFERENCE_TEMPERATURE) * timeDelta;
		return Math.max(0.0, grown * (1.0 - snowfall / restoringSnowfall));
	}

	/** {@link #advance} with GEOtop's default restoring snowfall. */
	public static double advance(double age, double snowfall, double surfaceTemperature, double timeDelta) {
		return advance(age, snowfall, surfaceTemperature, timeDelta, DEFAULT_RESTORING_SNOWFALL);
	}

	/**
	 * Initial non-dimensional age from an age in seconds and the initial snow
	 * temperature, as GEOtop converts its input ({@code AgeSnow0}, given in days).
	 */
	public static double fromSeconds(double ageSeconds, double temperature) {
		return ageSeconds * growthRate(temperature, INITIAL_REFERENCE_TEMPERATURE);
	}

	/** (r1 + r2 + r3) · 10⁻⁶ [s-1]. */
	private static double growthRate(double temperature, double reference) {
		double r1 = Math.exp(5000.0 * (1.0 / reference - 1.0 / (temperature - REFERENCE_TEMPERATURE + reference)));
		double r2 = Math.min(Math.pow(r1, 10.0), 1.0);
		double r3 = 0.3;
		return (r1 + r2 + r3) * 1.0e-6;
	}
}
