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

import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * Cloud transmissivity τ<sub>c</sub> of one step from the measured shortwave
 * (LINK-1): the measured radiation over the clear-sky radiation at the ground,
 * as GEOtop 3.0 computes it ({@code cloud_transmittance},
 * {@code radiation.cc:620-706}, commit 1f1fc5af).
 *
 * <ul>
 * <li>Clear sky: I<sub>sc</sub> E<sub>0</sub> ⟨τ<sub>atm</sub> sin α⟩, the
 * product averaged over the step by adaptive Simpson (tolerance 10⁻⁶, depth
 * 20, {@code adaptiveSimpsons2}, {@code util_math.h:305-340}), with α
 * floored at asin 0.05 inside τ<sub>atm</sub> and sin α floored at 0.05
 * ({@code TauatmSinalpha}, {@code radiation.cc:204-229}).</li>
 * <li>With beam and diffuse measured: k<sub>d</sub> = SW<sub>d</sub>/(SW<sub>b</sub> + SW<sub>d</sub>),
 * τ<sub>c</sub> = (SW<sub>d</sub> - (1 - V) SW<sub>surr</sub>)/(clear V k<sub>d</sub>),
 * not capped at 1.</li>
 * <li>With global shortwave: τ<sub>c</sub> = (SW - (1 - V) SW<sub>surr</sub>)/(clear ((1 - k<sub>d</sub>) + V k<sub>d</sub>)),
 * in [0, 1], iterated with the diffuse fraction of Erbs et al. (1982)
 * k<sub>d</sub>(τ<sub>c</sub> τ<sub>atm</sub>) ({@code diff2glob},
 * {@code radiation.cc:382-403}) until k<sub>d</sub> changes by less than
 * 0.005. On flat ground (V = 1) the iteration has no effect.</li>
 * <li>Floor at 0.1 ({@code min_tau_cloud}, {@code constants.h:49}); undefined
 * (NaN) when the sun is below the horizon over the whole step.</li>
 * </ul>
 *
 * GEOtop computes the cloud transmissivity with no reflection from the
 * surrounding terrain (SW<sub>surr</sub> = 0, {@code input.cc:419}); it is kept
 * here as a parameter for completeness.
 *
 * @param site             position of the column
 * @param clearSky         Iqbal's clear-sky transmissivity
 * @param skyViewFactor    V [-], 1 on flat ground
 * @param surroundingShortwave SW reflected by the surrounding terrain
 *                         [W m-2], 0 as GEOtop
 */
public record CloudTransmissivity(SolarGeometry site, ClearSkyTransmissivity clearSky, double skyViewFactor,
		double surroundingShortwave) {

	/** Lower bound of τ<sub>c</sub>. */
	public static final double MINIMUM = 0.1;
	private static final double SIMPSON_TOLERANCE = 1e-6;
	private static final int SIMPSON_DEPTH = 20;

	public CloudTransmissivity {
		Objects.requireNonNull(site, "site");
		Objects.requireNonNull(clearSky, "clearSky");
		if (!(skyViewFactor > 0.0 && skyViewFactor <= 1.0) || !(surroundingShortwave >= 0.0)) {
			throw new IllegalArgumentException("invalid sky view factor or surrounding shortwave");
		}
	}

	/** Flat ground with GEOtop's clear-sky parameters. */
	public static CloudTransmissivity flat(SolarGeometry site) {
		return new CloudTransmissivity(site, ClearSkyTransmissivity.geotop(), 1.0, 0.0);
	}

	/**
	 * Clear-sky shortwave on a horizontal surface averaged over a step,
	 * I<sub>sc</sub> E<sub>0</sub> ⟨τ<sub>atm</sub> sin α⟩ [W m-2], with the sun
	 * of the record at {@code julianDay}.
	 *
	 * @param julianDayBegin start of the step (fractional day of the year)
	 * @param julianDayEnd   end of the step
	 * @param julianDay      time of the record, where GEOtop evaluates the sun
	 */
	public double clearSkyShortwave(double julianDayBegin, double julianDayEnd, double julianDay, double pressure,
			double relativeHumidity, double airTemperature) {
		SolarGeometry.Sun sun = SolarGeometry.sun(julianDay);
		return SolarGeometry.SOLAR_CONSTANT * sun.distanceFactor()
				* average(jd -> tauSinHeight(jd, sun, pressure, relativeHumidity, airTemperature), julianDayBegin,
						julianDayEnd);
	}

	/**
	 * Clear-sky transmissivity ⟨τ<sub>atm</sub>⟩ averaged over a step, with the
	 * solar height floored at asin 0.05 ({@code Tauatm},
	 * {@code radiation.cc:294-311}): GEOtop's argument of the diffuse fraction,
	 * k<sub>d</sub> = k<sub>d</sub>(τ<sub>c</sub> τ<sub>atm</sub>).
	 */
	public double atmosphericTransmissivity(double julianDayBegin, double julianDayEnd, double julianDay,
			double pressure, double relativeHumidity, double airTemperature) {
		SolarGeometry.Sun sun = SolarGeometry.sun(julianDay);
		return average(jd -> clearSky.transmissivity(Math.max(site.height(jd, sun), Math.asin(0.05)), pressure,
				relativeHumidity, airTemperature), julianDayBegin, julianDayEnd);
	}

	/**
	 * τ<sub>c</sub> from global shortwave.
	 *
	 * @param shortwave measured global shortwave on the horizontal [W m-2]
	 * @return τ<sub>c</sub>, NaN at night
	 */
	public double fromGlobal(double julianDayBegin, double julianDayEnd, double julianDay, double shortwave,
			double pressure, double relativeHumidity, double airTemperature) {
		SolarGeometry.Sun sun = SolarGeometry.sun(julianDay);
		double tauSin = average(jd -> tauSinHeight(jd, sun, pressure, relativeHumidity, airTemperature),
				julianDayBegin, julianDayEnd);
		if (!(tauSin > 0.0)) {
			return Double.NaN;
		}
		double clear = SolarGeometry.SOLAR_CONSTANT * sun.distanceFactor() * tauSin;
		double tauAtm = atmosphericTransmissivity(julianDayBegin, julianDayEnd, julianDay, pressure, relativeHumidity,
				airTemperature);
		double kd = 0.2;
		double tau;
		int j = 0;
		double kd0;
		do {
			j++;
			kd0 = kd;
			tau = (shortwave - (1.0 - skyViewFactor) * surroundingShortwave)
					/ (clear * ((1.0 - kd) + skyViewFactor * kd));
			tau = Math.max(0.0, Math.min(1.0, tau));
			kd = diffuseFraction(tau * tauAtm);
		} while (Math.abs(kd0 - kd) > 0.005 && j < 1000);
		return Math.max(tau, MINIMUM);
	}

	/**
	 * τ<sub>c</sub> from beam and diffuse shortwave on the horizontal.
	 *
	 * @return τ<sub>c</sub>, NaN at night or without diffuse radiation
	 */
	public double fromBeamAndDiffuse(double julianDayBegin, double julianDayEnd, double julianDay, double beam,
			double diffuse, double pressure, double relativeHumidity, double airTemperature) {
		SolarGeometry.Sun sun = SolarGeometry.sun(julianDay);
		double tauSin = average(jd -> tauSinHeight(jd, sun, pressure, relativeHumidity, airTemperature),
				julianDayBegin, julianDayEnd);
		if (!(tauSin > 0.0) || !(beam + diffuse > 0.0 && diffuse > 0.0)) {
			return Double.NaN;
		}
		double kd = diffuse / (Math.max(0.0, beam) + diffuse);
		double tau = (diffuse - (1.0 - skyViewFactor) * surroundingShortwave)
				/ (SolarGeometry.SOLAR_CONSTANT * sun.distanceFactor() * tauSin * skyViewFactor * kd);
		return Math.max(tau, MINIMUM);
	}

	/** Diffuse fraction of global radiation, Erbs et al. (1982), {@code diff2glob}. */
	public static double diffuseFraction(double clearness) {
		if (clearness < 0.22) {
			return 1.0 - 0.09 * clearness;
		}
		if (clearness < 0.80) {
			return 0.9511 - 0.1604 * clearness + 4.388 * clearness * clearness - 16.638 * Math.pow(clearness, 3)
					+ 12.336 * Math.pow(clearness, 4);
		}
		return 0.165;
	}

	private double tauSinHeight(double julianDay, SolarGeometry.Sun sun, double pressure, double relativeHumidity,
			double airTemperature) {
		double height = site.height(julianDay, sun);
		if (height <= 0.0) {
			return 0.0;
		}
		return clearSky.transmissivity(Math.max(height, Math.asin(0.05)), pressure, relativeHumidity, airTemperature)
				* Math.max(Math.sin(height), 0.05);
	}

	/** Mean of f over [a, b] by GEOtop's adaptive Simpson. */
	static double average(DoubleUnaryOperator f, double a, double b) {
		if (!(b > a)) {
			throw new IllegalArgumentException("the step must have a positive length");
		}
		double c = 0.5 * (a + b);
		double fa = f.applyAsDouble(a);
		double fb = f.applyAsDouble(b);
		double fc = f.applyAsDouble(c);
		double s = (b - a) / 6.0 * (fa + 4.0 * fc + fb);
		return simpson(f, a, b, SIMPSON_TOLERANCE, s, fa, fb, fc, SIMPSON_DEPTH) / (b - a);
	}

	private static double simpson(DoubleUnaryOperator f, double a, double b, double epsilon, double s, double fa,
			double fb, double fc, int bottom) {
		double c = 0.5 * (a + b);
		double h = b - a;
		double d = 0.5 * (a + c);
		double e = 0.5 * (c + b);
		double fd = f.applyAsDouble(d);
		double fe = f.applyAsDouble(e);
		double left = h / 12.0 * (fa + 4.0 * fd + fc);
		double right = h / 12.0 * (fc + 4.0 * fe + fb);
		double s2 = left + right;
		if (bottom <= 0 || Math.abs(s2 - s) <= 15.0 * epsilon) {
			return s2 + (s2 - s) / 15.0;
		}
		return simpson(f, a, c, epsilon / 2.0, left, fa, fc, fd, bottom - 1)
				+ simpson(f, c, b, epsilon / 2.0, right, fc, fb, fe, bottom - 1);
	}
}
