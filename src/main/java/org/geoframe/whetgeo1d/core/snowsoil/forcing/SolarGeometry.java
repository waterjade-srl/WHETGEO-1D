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

import java.time.LocalDateTime;

/**
 * Position of the sun as GEOtop 3.0 computes it (commit 1f1fc5af), for the
 * clear-sky radiation of the cloud transmissivity (LINK-1).
 *
 * <ul>
 * <li>Earth–sun distance factor E<sub>0</sub>, equation of time E<sub>t</sub>
 * and declination δ from the day angle Γ = 2π JD/365.25, Spencer's series
 * ({@code sun}, {@code radiation.cc:43-59});</li>
 * <li>solar height from the solar hour h = 24 frac(JD) + dh, with
 * dh = (λ - ST π/12 + E<sub>t</sub>)/ω, ω = 0.261799388 rad h-1
 * ({@code SolarHeight}, {@code radiation.cc:65-89}; the shift dh is built in
 * {@code find_cloudiness}, {@code clouds.cc:255-256}).</li>
 * </ul>
 *
 * JD is GEOtop's day of the year counted from 0 at 1 January 00:00, in local
 * standard time, with its fraction ({@code convert_JDfrom0_JD},
 * {@code times.cc:180-215}).
 *
 * @param latitude     [rad]
 * @param longitude    [rad], east positive
 * @param standardTime offset of the local standard time from UTC [h]
 */
public record SolarGeometry(double latitude, double longitude, double standardTime) {

	/** Earth rotation [rad h-1] ({@code omega}, {@code constants.h:66}). */
	public static final double OMEGA = 0.261799388;
	/** Solar constant [W m-2] ({@code Isc}, {@code constants.h:67}). */
	public static final double SOLAR_CONSTANT = 1367.0;

	public SolarGeometry {
		if (!(Math.abs(latitude) <= 0.5 * Math.PI) || !Double.isFinite(longitude) || !Double.isFinite(standardTime)) {
			throw new IllegalArgumentException("invalid site: latitude " + latitude + ", longitude " + longitude
					+ ", standard time " + standardTime);
		}
	}

	/** Site from degrees. */
	public static SolarGeometry ofDegrees(double latitude, double longitude, double standardTime) {
		return new SolarGeometry(Math.toRadians(latitude), Math.toRadians(longitude), standardTime);
	}

	/**
	 * Sun-dependent quantities of a day angle.
	 *
	 * @param distanceFactor E<sub>0</sub> [-]
	 * @param equationOfTime E<sub>t</sub> [rad]
	 * @param declination    δ [rad]
	 */
	public record Sun(double distanceFactor, double equationOfTime, double declination) {
	}

	/** GEOtop's {@code sun}, at the fractional day of the year JD. */
	public static Sun sun(double julianDay) {
		double gamma = 2.0 * Math.PI * julianDay / 365.25;
		double e0 = 1.00011 + 0.034221 * Math.cos(gamma) + 0.00128 * Math.sin(gamma) + 0.000719 * Math.cos(2 * gamma)
				+ 0.000077 * Math.sin(2 * gamma);
		double et = 0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
				- 0.014615 * Math.cos(2 * gamma) - 0.04089 * Math.sin(2 * gamma);
		double delta = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
				- 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma) - 0.002697 * Math.cos(3 * gamma)
				+ 0.00148 * Math.sin(3 * gamma);
		return new Sun(e0, et, delta);
	}

	/** Shift dh of the solar hour [h] for the sun of the step. */
	public double hourShift(Sun sun) {
		return (longitude - standardTime * Math.PI / 12.0 + sun.equationOfTime()) / OMEGA;
	}

	/**
	 * Solar height [rad], not negative ({@code SolarHeight}).
	 *
	 * @param julianDay fractional day of the year
	 * @param sun       sun of the step (GEOtop keeps it fixed over the step)
	 */
	public double height(double julianDay, Sun sun) {
		double h = (julianDay - Math.floor(julianDay)) * 24.0 + hourShift(sun);
		if (h >= 24.0) {
			h -= 24.0;
		}
		if (h < 0.0) {
			h += 24.0;
		}
		double sine = Math.sin(latitude) * Math.sin(sun.declination())
				+ Math.cos(latitude) * Math.cos(sun.declination()) * Math.cos(OMEGA * (12.0 - h));
		sine = Math.max(-1.0, Math.min(1.0, sine));
		return Math.max(Math.asin(sine), 0.0);
	}

	/** GEOtop's fractional day of the year of a local standard time. */
	public static double julianDay(LocalDateTime localStandardTime) {
		return localStandardTime.getDayOfYear() - 1 + (localStandardTime.getHour()
				+ localStandardTime.getMinute() / 60.0 + localStandardTime.getSecond() / 3600.0) / 24.0;
	}

	/**
	 * Fractional day of the year in local standard time of an instant.
	 *
	 * @param epochSeconds seconds since 1970-01-01 UTC
	 */
	public double julianDay(long epochSeconds) {
		LocalDateTime local = LocalDateTime.ofEpochSecond(epochSeconds + Math.round(standardTime * 3600.0), 0,
				java.time.ZoneOffset.UTC);
		return julianDay(local);
	}
}
