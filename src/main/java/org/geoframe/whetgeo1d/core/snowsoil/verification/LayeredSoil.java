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
package org.geoframe.whetgeo1d.core.snowsoil.verification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.geoframe.closureequation.closureequation.Parameters;
import org.geoframe.whetgeo1d.core.snowsoil.SoilHeatSetup;

/**
 * A layered soil column read from a GEOtop soil file ({@code SoilParFile}, one
 * row per layer from the top), for the comparisons with GEOtop runs. Layers
 * with the same parameters share one parameter id. The freezing setup is
 * WHETGEO's Dall'Amico curve and {@code FreezingSoilInternalEnergy} with
 * Cosenza conductivity, which is GEOtop's quadratic parallel mean
 * ({@code k_thermal}, {@code energy.balance.cc:2743}), with GEOtop's
 * conductivity of water.
 *
 * <p>
 * Units of the file (GEOtop): Dz [mm], K [mm s-1], α [mm-1], SS [mm-1],
 * thermcond [W m-1 K-1] of the solids, thermcap [J m-3 K-1] of the solids.
 * The volumetric capacity becomes a particle density of thermcap/1000 kg m-3
 * with a specific heat of 1000 J kg-1 K-1.
 *
 * @param thickness  layer thicknesses from the top [m]
 * @param layerIds   parameter id of each layer, from the top
 * @param types      parameters of each id
 * @param suction    uniform initial ψ (of the total water) [m]
 * @param temperature uniform initial temperature [K]
 */
public record LayeredSoil(double[] thickness, int[] layerIds, List<Layer> types, double suction,
		double temperature) {

	/**
	 * Parameters of one soil type, SI.
	 *
	 * @param ks                 saturated conductivity, normal [m s-1]
	 * @param thetaR             [-]
	 * @param thetaS             [-]
	 * @param alpha              Van Genuchten α [m-1]
	 * @param n                  Van Genuchten n [-]
	 * @param specificStorage    S_s [m-1]
	 * @param solidConductivity  [W m-1 K-1]
	 * @param solidHeatCapacity  volumetric, of the solids [J m-3 K-1]
	 */
	public record Layer(double ks, double thetaR, double thetaS, double alpha, double n, double specificStorage,
			double solidConductivity, double solidHeatCapacity) {
	}

	/** Reads a GEOtop soil file (comma separated, header with GEOtop's default names). */
	public static LayeredSoil readGeotop(Path file, double suction, double temperature) throws IOException {
		List<String> lines = Files.readAllLines(file);
		List<String> header = Arrays.stream(lines.get(0).split(",")).map(String::trim).toList();
		int dz = header.indexOf("Dz");
		int kv = header.indexOf("Kv");
		int thr = header.indexOf("vwc_r");
		int ths = header.indexOf("vwc_s");
		int alpha = header.indexOf("VG_alpha");
		int n = header.indexOf("VG_n");
		int ss = header.indexOf("SS");
		int kt = header.indexOf("thermcond");
		int ct = header.indexOf("thermcap");
		Map<Layer, Integer> ids = new LinkedHashMap<>();
		List<Double> thickness = new ArrayList<>();
		List<Integer> layerIds = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			if (line.isBlank()) {
				continue;
			}
			String[] f = line.split(",");
			Layer layer = new Layer(1e-3 * value(f, kv), value(f, thr), value(f, ths), 1e3 * value(f, alpha),
					value(f, n), 1e3 * value(f, ss), value(f, kt), value(f, ct));
			ids.putIfAbsent(layer, ids.size());
			thickness.add(1e-3 * value(f, dz));
			layerIds.add(ids.get(layer));
		}
		return new LayeredSoil(thickness.stream().mapToDouble(Double::doubleValue).toArray(),
				layerIds.stream().mapToInt(Integer::intValue).toArray(), List.copyOf(ids.keySet()), suction,
				temperature);
	}

	private static double value(String[] fields, int column) {
		return Double.parseDouble(fields[column].trim());
	}

	public int cells() {
		return thickness.length;
	}

	public double depth() {
		return Arrays.stream(thickness).sum();
	}

	/** Thickness of cell i, 0 at the bottom [m]. */
	public double cellSize(int i) {
		return thickness[cells() - 1 - i];
	}

	/** Depth of the centre of cell i below the surface, 0 at the bottom [m]. */
	public double centreDepth(int i) {
		double above = 0.0;
		for (int k = cells() - 1; k > i; k--) {
			above += cellSize(k);
		}
		return above + 0.5 * cellSize(i);
	}

	/** The WHETGEO closure parameters, one entry per soil type. */
	public Parameters parameters() {
		int m = types.size();
		double[] thetaS = new double[m];
		double[] thetaR = new double[m];
		double[] density = new double[m];
		double[] heat = new double[m];
		double[] conductivity = new double[m];
		double[] melting = new double[m];
		double[] n = new double[m];
		double[] alpha = new double[m];
		double[] ks = new double[m];
		double[] alphaSS = new double[m];
		for (int k = 0; k < m; k++) {
			Layer t = types.get(k);
			thetaS[k] = t.thetaS();
			thetaR[k] = t.thetaR();
			density[k] = t.solidHeatCapacity() / 1000.0;
			heat[k] = 1000.0;
			conductivity[k] = t.solidConductivity();
			melting[k] = 273.15;
			n[k] = t.n();
			alpha[k] = t.alpha();
			ks[k] = t.ks();
			alphaSS[k] = t.specificStorage() / (1000.0 * 9.81);
		}
		// GEOtop's k_liq (constants.h), 0.567 instead of WHETGEO's 0.6
		return new Parameters(1000.0, 920.0, 4188.0, 2117.0, 0.567, 2.29, 333700, 273.15, 278.15, -766.45, thetaS,
				thetaR, density, heat, conductivity, melting, n, alpha, new double[m], new double[m], new double[m],
				ks, alphaSS, new double[m]);
	}

	/** Cell centres from the bottom [m]. */
	public double[] z() {
		double[] z = new double[cells()];
		double base = 0.0;
		for (int i = 0; i < cells(); i++) {
			z[i] = base + 0.5 * cellSize(i);
			base += cellSize(i);
		}
		return z;
	}

	/** Node distances: half cells at the two faces, centre to centre inside. */
	public double[] spaceDeltaZ() {
		int c = cells();
		double[] d = new double[c + 1];
		d[0] = 0.5 * cellSize(0);
		for (int i = 1; i < c; i++) {
			d[i] = 0.5 * (cellSize(i - 1) + cellSize(i));
		}
		d[c] = 0.5 * cellSize(c - 1);
		return d;
	}

	public double[] controlVolume() {
		double[] v = new double[cells()];
		for (int i = 0; i < cells(); i++) {
			v[i] = cellSize(i);
		}
		return v;
	}

	/** Parameter id of each cell, 0 at the bottom. */
	public int[] parameterIds() {
		int[] ids = new int[cells()];
		for (int i = 0; i < cells(); i++) {
			ids[i] = layerIds[cells() - 1 - i];
		}
		return ids;
	}

	/** The setup with WHETGEO's freezing soil (document 012). */
	public SoilHeatSetup freezingSetup() {
		double[] psi = new double[cells()];
		Arrays.fill(psi, suction);
		double[] t = new double[cells()];
		Arrays.fill(t, temperature);
		return new SoilHeatSetup(z(), spaceDeltaZ(), controlVolume(), psi, t, new int[cells()], parameterIds(),
				new String[] { "Van Genuchten Dall Amico" }, new String[] { "FreezingSoilInternalEnergy" },
				new String[] { "Cosenza" }, "Weighted Harmonic Mean", parameters());
	}
}
