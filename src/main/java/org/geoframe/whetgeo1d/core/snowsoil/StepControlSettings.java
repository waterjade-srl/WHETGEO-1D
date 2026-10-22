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

/**
 * Settings of the {@link StepController}: rejection and sub-stepping (SOL-5),
 * periodic restart (ARCH-5).
 *
 * @param maxHalvings              how many times an interval may be halved: N
 *                                 halvings go down to Δt / 2<sup>N</sup>
 * @param richardsBalanceTolerance largest |Richards balance error| of an
 *                                 accepted step [kg m-2]
 * @param onFailure                what happens when the finest sub-step fails
 * @param restartInterval          period of the restart states [s], measured
 *                                 from time zero of the controller; 0 for
 *                                 none
 * @param maxLoggedRejections      rejections kept in the log of the run; the
 *                                 count goes on beyond it
 */
public record StepControlSettings(int maxHalvings, double richardsBalanceTolerance, FailurePolicy onFailure,
		double restartInterval, int maxLoggedRejections) {

	/** What to do when a step fails even at the finest sub-step. */
	public enum FailurePolicy {
		/** Stop the run with a {@link StepNotConvergedException} (SOL-5, the default). */
		STOP,
		/**
		 * Accept the failed sub-step and flag it: counted in the diagnostics and
		 * marked as degraded in the result of the interval, to be excluded from
		 * accepted results and from validation (SOL-5, "if an operational mode
		 * allows it"). An invalid state still stops the run.
		 */
		FLAG
	}

	public StepControlSettings {
		if (maxHalvings < 0 || maxHalvings > 30) {
			throw new IllegalArgumentException("maxHalvings must be in [0, 30], got " + maxHalvings);
		}
		if (!(richardsBalanceTolerance > 0.0)) {
			throw new IllegalArgumentException(
					"richardsBalanceTolerance must be positive, got " + richardsBalanceTolerance);
		}
		Objects.requireNonNull(onFailure, "onFailure");
		if (!(restartInterval >= 0.0) || Double.isInfinite(restartInterval)) {
			throw new IllegalArgumentException("restartInterval must be >= 0 and finite, got " + restartInterval);
		}
		if (maxLoggedRejections < 0) {
			throw new IllegalArgumentException("maxLoggedRejections must be >= 0, got " + maxLoggedRejections);
		}
	}

	/**
	 * 4 halvings (an hour down to 3.75 minutes, the example of SOL-5), Richards
	 * tolerance 10⁻⁵ kg m-2, stop on failure, no periodic restart, 10 000
	 * rejections logged.
	 */
	public static StepControlSettings defaults() {
		return new StepControlSettings(4, 1e-5, FailurePolicy.STOP, 0.0, 10_000);
	}

	/** Same, with another number of halvings. */
	public StepControlSettings withMaxHalvings(int halvings) {
		return new StepControlSettings(halvings, richardsBalanceTolerance, onFailure, restartInterval,
				maxLoggedRejections);
	}

	/** Same, with another Richards tolerance [kg m-2]. */
	public StepControlSettings withRichardsBalanceTolerance(double tolerance) {
		return new StepControlSettings(maxHalvings, tolerance, onFailure, restartInterval, maxLoggedRejections);
	}

	/** Same, with another failure policy. */
	public StepControlSettings withOnFailure(FailurePolicy policy) {
		return new StepControlSettings(maxHalvings, richardsBalanceTolerance, policy, restartInterval,
				maxLoggedRejections);
	}

	/** Same, with restart states every {@code interval} seconds (0: none). */
	public StepControlSettings withRestartInterval(double interval) {
		return new StepControlSettings(maxHalvings, richardsBalanceTolerance, onFailure, interval,
				maxLoggedRejections);
	}
}
