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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.geoframe.whetgeo1d.snow.SnowConstants;
import org.geoframe.whetgeo1d.snow.surface.AirProperties;
import org.junit.Test;

/** The forcing estimates of LINK-1: pressure, cloud transmissivity, longwave, rain/snow split. */
public class ForcingEstimatesTest {

	private static final double T0 = SnowConstants.MELTING_TEMPERATURE;
	private static final SolarGeometry TRENTO = SolarGeometry.ofDegrees(46.07, 11.12, 1.0);

	@Test
	public void pressureFromElevation() {
		assertEquals(101325.0, StandardAtmosphere.pressure(0.0), 0.0);
		assertEquals(101325.0 / Math.E, StandardAtmosphere.pressure(8500.0), 1e-9);
		assertEquals(101325.0 * Math.exp(-2000.0 / 8500.0), StandardAtmosphere.pressure(2000.0), 1e-9);
	}

	@Test
	public void solarHeightAtSolarNoonOfTheEquinox() {
		// 21 March, near solar noon at the meridian of the standard time with no equation of time
		SolarGeometry site = SolarGeometry.ofDegrees(45.0, 15.0, 1.0);
		double jd = 79.0 + 12.0 / 24.0;
		SolarGeometry.Sun sun = SolarGeometry.sun(jd);
		double height = site.height(jd + 0.0, sun);
		// 90 - 45 + δ, δ ≈ 0, within the equation of time and declination of the day
		assertEquals(45.0, Math.toDegrees(height), 1.5);
		assertEquals(0.0, site.height(79.0, sun), 0.0);
	}

	@Test
	public void cloudTransmissivityIsOneUnderClearSkyAndScalesWithShortwave() {
		CloudTransmissivity c = CloudTransmissivity.flat(TRENTO);
		double begin = 172.0 + 11.0 / 24.0;
		double end = 172.0 + 12.0 / 24.0;
		double mid = 0.5 * (begin + end);
		double p = StandardAtmosphere.pressure(200.0);
		double clear = c.clearSkyShortwave(begin, end, mid, p, 0.5, T0 + 20.0);
		assertTrue("summer noon clear sky " + clear, clear > 700.0 && clear < 1100.0);
		assertEquals(1.0, c.fromGlobal(begin, end, mid, clear, p, 0.5, T0 + 20.0), 1e-12);
		assertEquals(0.5, c.fromGlobal(begin, end, mid, 0.5 * clear, p, 0.5, T0 + 20.0), 1e-12);
		assertEquals(CloudTransmissivity.MINIMUM, c.fromGlobal(begin, end, mid, 0.0, p, 0.5, T0 + 20.0), 0.0);
		assertEquals(0.5, c.fromBeamAndDiffuse(begin, end, mid, 0.3 * clear, 0.2 * clear, p, 0.5, T0 + 20.0), 1e-12);
		// midnight: undefined
		assertTrue(Double.isNaN(c.fromGlobal(172.0, 172.0 + 1.0 / 24.0, 172.02, 0.0, p, 0.5, T0 + 10.0)));
	}

	@Test
	public void geotopSeriesAveragesDaylightPartsAndInterpolatesTheNight() {
		// two days of 24 records, daylight from 6 to 17 (12 records), 3 parts of 4
		int n = 48;
		double[] tau = new double[n];
		boolean[] day = new boolean[n];
		for (int k = 0; k < n; k++) {
			int hour = k % 24;
			day[k] = hour >= 6 && hour < 18;
			tau[k] = day[k] ? (k < 24 ? 0.2 + 0.1 * ((hour - 6) / 4) : 0.8) : Double.NaN;
		}
		double[] av = DailyCloudiness.geotopSeries(tau, day, 3);
		// first night: no previous day
		for (int k = 0; k < 6; k++) {
			assertTrue(Double.isNaN(av[k]));
		}
		assertEquals(0.2, av[6], 1e-12);
		assertEquals(0.3, av[10], 1e-12);
		assertEquals(0.4, av[17], 1e-12);
		// night from the last part of day 1 (0.4) to the first part of day 2 (0.8)
		assertEquals(0.4, av[18], 1e-12);
		assertEquals(0.4 + 0.4 * (29 - 18) / 12.0, av[29], 1e-12);
		assertEquals(0.8, av[30], 1e-12);

		DailyCloudiness.Running running = new DailyCloudiness.Running();
		double last = Double.NaN;
		for (int k = 0; k < 24; k++) {
			last = running.add(3600.0 * (k + 1), tau[k], day[k]);
		}
		assertEquals(0.3, last, 1e-12);
	}

	@Test
	public void longwaveAsGeotop() {
		double ta = T0 + 5.0;
		double p = 90000.0;
		double rh = 0.7;
		double clear = IncomingLongwave.ClearSkyEmissivity.DILLEY_OBRIEN_1998.emissivity(
				rh * org.geoframe.whetgeo1d.snow.surface.SaturationCurve.WATER.pressure(ta, p), ta);
		assertTrue("Dilley clear-sky emissivity " + clear, clear > 0.6 && clear < 0.85);
		assertEquals(clear * AirProperties.emissivePower(ta),
				IncomingLongwave.of(IncomingLongwave.ClearSkyEmissivity.DILLEY_OBRIEN_1998, ta, rh, p, 1.0, 1.0), 1e-9);
		assertEquals(AirProperties.emissivePower(ta),
				IncomingLongwave.of(IncomingLongwave.ClearSkyEmissivity.DILLEY_OBRIEN_1998, ta, rh, p, 0.0, 1.0), 1e-9);
		assertEquals(0.5 * clear + 0.5, IncomingLongwave.atmosphereEmissivity(clear, 0.5), 1e-15);
	}

	/** Konzelmann (1994) with GEOtop's default coefficients ({@code radiation.cc:514-517}). */
	@Test
	public void konzelmannAsGeotop() {
		double t = 268.15;
		double e = 400.0; // Pa
		assertEquals(0.23 + 0.484 * Math.pow(e / t, 1.0 / 8.0),
				IncomingLongwave.ClearSkyEmissivity.KONZELMANN_1994.emissivity(e, t), 1e-15);
		assertEquals(IncomingLongwave.ClearSkyEmissivity.KONZELMANN_1994, org.geoframe.whetgeo1d.core.utils.EnumUtils
				.fromString(IncomingLongwave.ClearSkyEmissivity.class, "Konzelmann 1994"));
	}

	@Test
	public void rainSnowSplitConservesTheTotal() {
		PrecipitationPartition.GeotopLinear g = PrecipitationPartition.GeotopLinear.geotop();
		assertEquals(10.0, g.split(10.0, T0 - 1.0).snow(), 0.0);
		assertEquals(10.0, g.split(10.0, T0 + 3.0).rain(), 0.0);
		PrecipitationPartition.Split s = g.split(10.0, T0 + 1.0);
		assertEquals(5.0, s.rain(), 1e-12);
		assertEquals(5.0, s.snow(), 1e-12);
		PrecipitationPartition.Kavetski k = new PrecipitationPartition.Kavetski(T0, 1.0, 1.0, 1.0);
		PrecipitationPartition.Split half = k.split(4.0, T0);
		assertEquals(2.0, half.snow(), 1e-12);
		assertEquals(4.0, half.rain() + half.snow(), 1e-12);
	}
}
