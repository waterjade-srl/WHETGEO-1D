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
 */
public record StepForcing(SurfaceForcing surface, double snowfall, double rainfall, double groundAlbedoVisible,
		double groundAlbedoNearInfrared, TopBoundary bareSoilTop, double bottomValue, double timeDelta) {

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
	}

	private static void requireNonNegative(double value, String name) {
		if (!(value >= 0.0) || Double.isInfinite(value)) {
			throw new IllegalArgumentException(name + " must be non-negative and finite, got " + value);
		}
	}
}
