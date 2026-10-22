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
package org.geoframe.whetgeo1d.core.snowsoil;

import java.util.Objects;

import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;

/**
 * Forcing of one step of the {@link SnowSoilSolver}. Precipitation is already
 * split into rain and snow (specification §2.2 step 1, §6.5: done outside, by
 * RainSnowSep).
 *
 * @param surface            radiation and meteorology at the surface
 * @param snowfall           snow reaching the ground in the step [kg m-2]
 * @param rainfall           rain reaching the ground in the step [kg m-2]
 * @param groundAlbedoVisible albedo of the soil under thin snow, visible [-]
 * @param groundAlbedoNearInfrared albedo of the soil under thin snow, near
 *                           infrared [-]
 * @param bareSoilTop        condition at the top of the soil when there is no
 *                           snow at the start of the step: the soil surface
 *                           balance belongs to WHETGEO's bare-soil path, not
 *                           to the snow library
 * @param bottomValue        temperature [K] or flux into the column [W m-2] at
 *                           the bottom, as the heat driver's bottom type
 * @param timeDelta          step length [s]
 * @param soilWaterSinks     evapotranspiration to take from each soil cell in
 *                           the step [kg m-2], bottom first, e.g. from GEOET;
 *                           null for none. Only with soil water; with snow on
 *                           the ground the caller passes none (§6.5, B4)
 * @param precipitationTemperature temperature of the precipitation given by
 *                           the forcing [K], NaN for none: where given it
 *                           replaces the wet-bulb temperature of the
 *                           recommended rule, with the same limits at 0 °C
 *                           (SNOW-5); ignored in fidelity
 * @param soilWaterBottomValue value of the bottom condition of Richards, as
 *                           in {@code RichardsSolver1D} (ψ [m] for Dirichlet,
 *                           flux for Neumann; unused with free drainage or
 *                           an impervious bottom), COL-6
 * @param snowTop            condition at the top of the snow when there is
 *                           snow at the start of the step, in place of the
 *                           surface energy balance (COL-6): an imposed surface
 *                           temperature, capped at 0 °C over snow, or an
 *                           imposed flux into the column; null for the surface
 *                           energy balance, the normal case
 */
public record StepForcing(SurfaceForcing surface, double snowfall, double rainfall, double groundAlbedoVisible,
		double groundAlbedoNearInfrared, TopBoundary bareSoilTop, double bottomValue, double timeDelta,
		double[] soilWaterSinks, double precipitationTemperature, double soilWaterBottomValue, TopBoundary snowTop) {

	/** Forcing with the surface energy balance over snow. */
	public StepForcing(SurfaceForcing surface, double snowfall, double rainfall, double groundAlbedoVisible,
			double groundAlbedoNearInfrared, TopBoundary bareSoilTop, double bottomValue, double timeDelta,
			double[] soilWaterSinks, double precipitationTemperature, double soilWaterBottomValue) {
		this(surface, snowfall, rainfall, groundAlbedoVisible, groundAlbedoNearInfrared, bareSoilTop, bottomValue,
				timeDelta, soilWaterSinks, precipitationTemperature, soilWaterBottomValue, null);
	}

	/** Forcing with evapotranspiration, no precipitation temperature and a zero bottom value for Richards. */
	public StepForcing(SurfaceForcing surface, double snowfall, double rainfall, double groundAlbedoVisible,
			double groundAlbedoNearInfrared, TopBoundary bareSoilTop, double bottomValue, double timeDelta,
			double[] soilWaterSinks) {
		this(surface, snowfall, rainfall, groundAlbedoVisible, groundAlbedoNearInfrared, bareSoilTop, bottomValue,
				timeDelta, soilWaterSinks, Double.NaN, 0.0);
	}

	/** Forcing without evapotranspiration. */
	public StepForcing(SurfaceForcing surface, double snowfall, double rainfall, double groundAlbedoVisible,
			double groundAlbedoNearInfrared, TopBoundary bareSoilTop, double bottomValue, double timeDelta) {
		this(surface, snowfall, rainfall, groundAlbedoVisible, groundAlbedoNearInfrared, bareSoilTop, bottomValue,
				timeDelta, null);
	}

	public StepForcing {
		Objects.requireNonNull(surface, "surface");
		Objects.requireNonNull(bareSoilTop, "bareSoilTop");
		requireNonNegative(snowfall, "snowfall");
		requireNonNegative(rainfall, "rainfall");
		if (!(groundAlbedoVisible >= 0.0 && groundAlbedoVisible <= 1.0)
				|| !(groundAlbedoNearInfrared >= 0.0 && groundAlbedoNearInfrared <= 1.0)) {
			throw new IllegalArgumentException("ground albedos must be in [0, 1]");
		}
		if (!Double.isFinite(bottomValue)) {
			throw new IllegalArgumentException("bottomValue must be finite, got " + bottomValue);
		}
		if (!(timeDelta > 0.0) || Double.isInfinite(timeDelta)) {
			throw new IllegalArgumentException("timeDelta must be positive and finite, got " + timeDelta);
		}
		if (!Double.isNaN(precipitationTemperature) && !(precipitationTemperature > 0.0
				&& Double.isFinite(precipitationTemperature))) {
			throw new IllegalArgumentException(
					"precipitationTemperature must be NaN or positive [K], got " + precipitationTemperature);
		}
		if (!Double.isFinite(soilWaterBottomValue)) {
			throw new IllegalArgumentException("soilWaterBottomValue must be finite, got " + soilWaterBottomValue);
		}
		if (soilWaterSinks != null) {
			soilWaterSinks = soilWaterSinks.clone();
			for (double sink : soilWaterSinks) {
				requireNonNegative(sink, "soilWaterSinks");
			}
		}
	}

	private static void requireNonNegative(double value, String name) {
		if (!(value >= 0.0) || Double.isInfinite(value)) {
			throw new IllegalArgumentException(name + " must be non-negative and finite, got " + value);
		}
	}
}
