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
 * Shortwave on
 * a slope as GEOtop 3.0 computes it for a point ({@code energy.balance.cc:488-524},
 * {@code shortwave_radiation}, {@code radiation.cc:320-375}), from the cloud
 * transmissivity τ<sub>c</sub> of the step:
 *
 * <pre>
 * k_d   = k_d(τ_c ⟨τ_atm⟩)                                (Erbs, diff2glob)
 * SW_d  = I_sc E_0 τ_c ⟨τ_atm sin α⟩ V k_d + (1 - V) SW_surr
 * SW_b  = (1 - k_d) I_sc E_0 τ_c ⟨τ_atm cos i⟩,   0 in the shadow of the horizon
 * cos i = max(0, cos β sin α + sin β cos α cos(ψ - ψ_s))  (slope β, aspect ψ_s)
 * </pre>
 *
 * ⟨·⟩ are means over the step by GEOtop's adaptive Simpson (tolerance 10⁻⁶,
 * 10⁻⁸ for ⟨τ<sub>atm</sub> cos i⟩), with the sun of the middle of the step;
 * the shadow is tested on the mean solar height and azimuth with no
 * tolerance ({@code shadows_point}). The beam and diffuse are on the slope:
 * the column uses them as they are. The cosine passed on to the snow albedo is
 * GEOtop's k<sub>d</sub> ⟨sin α⟩ + (1 - k<sub>d</sub>) ⟨cos i⟩. The reflection
 * from the surroundings is zero here (V = 1 in a point simulation without a
 * sky view factor).
 *
 * @param site          the point
 * @param clearSky      Iqbal's clear-sky parameters
 * @param slope         β [rad]
 * @param aspect        ψ<sub>s</sub> [rad], from north clockwise
 * @param skyViewFactor V
 * @param horizon       horizon of the point
 */
public record SlopeShortwave(SolarGeometry site, ClearSkyTransmissivity clearSky, double slope, double aspect,
		double skyViewFactor, Horizon horizon) {

	public SlopeShortwave {
		Objects.requireNonNull(site, "site");
		Objects.requireNonNull(clearSky, "clearSky");
		Objects.requireNonNull(horizon, "horizon");
	}

	/**
	 * Shortwave of a step.
	 *
	 * @param beam          SW<sub>b</sub> on the slope [W m-2]
	 * @param diffuse       SW<sub>d</sub> [W m-2]
	 * @param cosIncidence  k<sub>d</sub> ⟨sin α⟩ + (1 - k<sub>d</sub>) ⟨cos i⟩
	 * @param diffuseFraction k<sub>d</sub>
	 * @param shadow        the beam is hidden by the horizon
	 */
	public record Result(double beam, double diffuse, double cosIncidence, double diffuseFraction, boolean shadow) {
	}

	/**
	 * @param dayBegin            start of the step, fractional day of the year
	 * @param dayEnd              end of the step
	 * @param cloudTransmissivity τ<sub>c</sub> of the step
	 * @param pressure            [Pa]
	 * @param relativeHumidity    [0, 1]
	 * @param airTemperature      [K]
	 */
	public Result step(double dayBegin, double dayEnd, double cloudTransmissivity, double pressure,
			double relativeHumidity, double airTemperature) {
		double mid = 0.5 * (dayBegin + dayEnd);
		SolarGeometry.Sun sun = SolarGeometry.sun(mid);
		CloudTransmissivity flat = new CloudTransmissivity(site, clearSky, 1.0, 0.0);
		double tauAtm = flat.atmosphericTransmissivity(dayBegin, dayEnd, mid, pressure, relativeHumidity,
				airTemperature);
		double kd = CloudTransmissivity.diffuseFraction(cloudTransmissivity * tauAtm);
		double clearHorizontal = flat.clearSkyShortwave(dayBegin, dayEnd, mid, pressure, relativeHumidity,
				airTemperature);
		double diffuse = clearHorizontal * cloudTransmissivity * skyViewFactor * kd;

		double height = average(t -> site.height(t, sun), dayBegin, dayEnd, 1e-6);
		double azimuth = average(t -> azimuth(t, sun), dayBegin, dayEnd, 1e-6);
		double sinHeight = average(t -> {
			double h = site.height(t, sun);
			return h > 0.0 ? Math.max(Math.sin(h), 0.05) : 0.0;
		}, dayBegin, dayEnd, 1e-6);
		boolean shadow = horizon.inShadow(Math.toDegrees(height), Math.toDegrees(azimuth), 0.0, 0.0);
		double beam = 0.0;
		double cosInc = 0.0;
		if (!shadow) {
			cosInc = average(t -> cosIncidence(t, sun), dayBegin, dayEnd, 1e-6);
			double tauCos = average(t -> {
				double h = site.height(t, sun);
				return h > 0.0 ? clearSky.transmissivity(Math.max(h, Math.asin(0.05)), pressure, relativeHumidity,
						airTemperature) * cosIncidence(t, sun) : 0.0;
			}, dayBegin, dayEnd, 1e-8);
			beam = (1.0 - kd) * SolarGeometry.SOLAR_CONSTANT * sun.distanceFactor() * cloudTransmissivity * tauCos;
		}
		return new Result(beam, diffuse, kd * sinHeight + (1.0 - kd) * cosInc, kd, shadow);
	}

	/** max(0, cos β sin α + sin β cos α cos(ψ - ψ_s)), 0 with the sun down ({@code Cosinc}). */
	public double cosIncidence(double day, SolarGeometry.Sun sun) {
		double h = site.height(day, sun);
		if (!(h > 0.0)) {
			return 0.0;
		}
		return Math.max(0.0, Math.cos(slope) * Math.sin(h)
				+ Math.sin(slope) * Math.cos(h) * Math.cos(-aspect + azimuth(day, sun)));
	}

	/** Solar azimuth from north clockwise [rad], GEOtop's {@code SolarAzimuth} ({@code radiation.cc:98-146}). */
	public double azimuth(double day, SolarGeometry.Sun sun) {
		double h = (day - Math.floor(day)) * 24.0 + site.hourShift(sun);
		if (h >= 24.0) {
			h -= 24.0;
		}
		if (h < 0.0) {
			h += 24.0;
		}
		double lat = site.latitude();
		double delta = sun.declination();
		double sine = Math.sin(lat) * Math.sin(delta)
				+ Math.cos(lat) * Math.cos(delta) * Math.cos(SolarGeometry.OMEGA * (12.0 - h));
		sine = Math.max(-1.0, Math.min(1.0, sine));
		double alpha = Math.asin(sine);
		if (alpha == 0.5 * Math.PI) {
			return h <= 12.0 ? 0.5 * Math.PI : 1.5 * Math.PI;
		}
		double cosine = (Math.sin(alpha) * Math.sin(lat) - Math.sin(delta)) / (Math.cos(alpha) * Math.cos(lat));
		cosine = Math.max(-1.0, Math.min(1.0, cosine));
		return h <= 12.0 ? Math.PI - Math.acos(cosine) : Math.PI + Math.acos(cosine);
	}

	/** Mean of f over [a, b] by GEOtop's adaptive Simpson ({@code adaptiveSimpsons2}, depth 20). */
	static double average(DoubleUnaryOperator f, double a, double b, double tolerance) {
		double c = 0.5 * (a + b);
		double fa = f.applyAsDouble(a);
		double fb = f.applyAsDouble(b);
		double fc = f.applyAsDouble(c);
		double s = (b - a) / 6.0 * (fa + 4.0 * fc + fb);
		return simpson(f, a, b, tolerance, s, fa, fb, fc, 20) / (b - a);
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
