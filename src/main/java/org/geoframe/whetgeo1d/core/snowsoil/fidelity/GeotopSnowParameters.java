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
package org.geoframe.whetgeo1d.core.snowsoil.fidelity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.geoframe.whetgeo1d.core.snowsoil.SnowProcesses;
import org.geoframe.whetgeo1d.core.snowsoil.ThinSnow;
import org.geoframe.whetgeo1d.snow.GeotopCompaction;
import org.geoframe.whetgeo1d.snow.LiquidWaterPercolation;
import org.geoframe.whetgeo1d.snow.MassCriterionRestratification;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.surface.MoninObukhov;
import org.geoframe.whetgeo1d.snow.surface.SnowAlbedo;
import org.geoframe.whetgeo1d.snow.surface.SurfaceEnergyBalance;

/**
 * <b>Temporary: to be evaluated for the GEOframe components.</b> The snow
 * keywords of a GEOtop 3.0 {@code geotop.inpts} mapped onto the fidelity
 * configuration ({@link SnowProcesses#geotop}), so that a fidelity run takes
 * a GEOtop case's own snow parameters, in GEOtop's units (mm where GEOtop uses
 * mm). Written as {@code Keyword=value} pairs separated by commas or
 * semicolons; a keyword not listed here is an error, so that nothing is
 * silently ignored.
 *
 * <table>
 * <caption>Mapped keywords</caption>
 * <tr><td>FreshSnowReflVis, FreshSnowReflNIR, SnowAgingCoeffVis,
 * SnowAgingCoeffNIR</td><td>{@link SnowAlbedo.Parameters}</td></tr>
 * <tr><td>AlbExtParSnow [mm]</td><td>extinction depth of the ground albedo</td></tr>
 * <tr><td>IrriducibleWatSatSnow</td><td>S<sub>r</sub> of the percolation</td></tr>
 * <tr><td>SnowEmissiv</td><td>ε of the surface balance over snow</td></tr>
 * <tr><td>SnowRoughness [mm]</td><td>z<sub>0</sub> of Monin–Obukhov over snow</td></tr>
 * <tr><td>MaxSnowPorosity, DrySnowDefRate, SnowDensityCutoff, WetSnowDefRate,
 * SnowViscosity</td><td>{@link GeotopCompaction.Parameters}</td></tr>
 * <tr><td>MaxWaterEqSnowLayerContent, SWEbottom, SWEtop,
 * MaxSnowLayersMiddle</td><td>{@link MassCriterionRestratification.Parameters};
 * the thin-cell thresholds of compaction and percolation, 0.1 W<sub>c</sub>,
 * follow W<sub>c</sub></td></tr>
 * <tr><td>PointSlope [deg]</td><td>slope of the snowpack processes</td></tr>
 * <tr><td>SoilRoughness, ThresSnowSoilRough [mm], SoilEmissiv</td><td>{@link ThinSnow}:
 * roughness and emissivity of the surface under a thin pack;
 * {@code ThresSnowSoilRough} defaults to {@code SoilRoughness}, as in GEOtop
 * ({@code parameters.cc:1246-1248})</td></tr>
 * </table>
 */
public final class GeotopSnowParameters {

	private static final Set<String> KEYWORDS = Set.of("FreshSnowReflVis", "FreshSnowReflNIR", "SnowAgingCoeffVis",
			"SnowAgingCoeffNIR", "AlbExtParSnow", "IrriducibleWatSatSnow", "SnowEmissiv", "SnowRoughness",
			"MaxSnowPorosity", "DrySnowDefRate", "SnowDensityCutoff", "WetSnowDefRate", "SnowViscosity",
			"MaxWaterEqSnowLayerContent", "SWEbottom", "SWEtop", "MaxSnowLayersMiddle", "PointSlope",
			"SoilRoughness", "ThresSnowSoilRough", "SoilEmissiv");
	/** GEOtop's {@code simpl_snow}: cells below this fraction of W_c are thin. */
	private static final double THIN_FRACTION = 0.1;

	private final Map<String, Double> values;

	private GeotopSnowParameters(Map<String, Double> values) {
		this.values = values;
	}

	/** Parses {@code Keyword=value} pairs; empty or null: no change. */
	public static GeotopSnowParameters parse(String text) {
		Map<String, Double> values = new LinkedHashMap<>();
		if (text != null) {
			for (String pair : text.split("[,;]")) {
				if (pair.isBlank()) {
					continue;
				}
				String[] kv = pair.split("=");
				String key = kv[0].trim();
				if (kv.length != 2 || !KEYWORDS.contains(key)) {
					throw new IllegalArgumentException("unknown or malformed GEOtop snow keyword: " + pair.trim()
							+ "; known: " + KEYWORDS);
				}
				values.put(key, Double.parseDouble(kv[1].trim()));
			}
		}
		return new GeotopSnowParameters(values);
	}

	/** The keywords given, in order. */
	public Map<String, Double> values() {
		return Map.copyOf(values);
	}

	private double get(String key, double fallback) {
		return values.getOrDefault(key, fallback);
	}

	/** The processes with the keywords given replacing the defaults. */
	public SnowProcesses apply(SnowProcesses p) {
		if (values.isEmpty()) {
			return p;
		}
		SnowEquationOfState eos = p.equationOfState();
		SnowAlbedo.Parameters a = p.albedo().parameters();
		SnowAlbedo albedo = new SnowAlbedo(new SnowAlbedo.Parameters(get("FreshSnowReflVis", a.freshVisible()),
				get("FreshSnowReflNIR", a.freshNearInfrared()), get("SnowAgingCoeffVis", a.ageingVisible()),
				get("SnowAgingCoeffNIR", a.ageingNearInfrared()), 1e-3 * get("AlbExtParSnow", 1e3 * a.extinctionDepth()),
				a.thinSnowDecay()));
		SurfaceEnergyBalance.Parameters b = p.surfaceBalance();
		SurfaceEnergyBalance.Parameters balance = new SurfaceEnergyBalance.Parameters(
				get("SnowEmissiv", b.emissivity()), b.latentHeat(), b.saturation(), b.freezeAirProperties());
		MoninObukhov.Parameters t = p.turbulence().parameters();
		MoninObukhov turbulence = new MoninObukhov(new MoninObukhov.Parameters(t.windHeight(), t.temperatureHeight(),
				1e-3 * get("SnowRoughness", 1e3 * t.roughnessLength()), t.displacementHeight(), t.stability(),
				t.scalarRoughness(), t.roughnessRatio(), t.maxIterations(), t.minimumWindSpeed(), t.maximumResistance(),
				t.capVapourResistance()));

		MassCriterionRestratification.Parameters m = MassCriterionRestratification.Parameters.geotopDefaults();
		double cellIce = get("MaxWaterEqSnowLayerContent", m.targetCellIce());
		MassCriterionRestratification restratification = new MassCriterionRestratification(
				new MassCriterionRestratification.Parameters(cellIce, get("SWEbottom", m.bottomIce()),
						get("SWEtop", m.topIce()), (int) get("MaxSnowLayersMiddle", m.elasticCells())),
				eos);
		GeotopCompaction.Parameters c = GeotopCompaction.Parameters.geotopDefaults();
		GeotopCompaction compaction = new GeotopCompaction(new GeotopCompaction.Parameters(
				get("MaxSnowPorosity", c.maxIceVolumeFraction()), get("DrySnowDefRate", c.dryDeformationRate()),
				get("SnowDensityCutoff", c.densityCutoff()), get("WetSnowDefRate", c.wetDeformationRate()),
				get("SnowViscosity", c.viscosity()), THIN_FRACTION * cellIce), eos);
		LiquidWaterPercolation.Parameters w = LiquidWaterPercolation.Parameters.geotop();
		LiquidWaterPercolation percolation = new LiquidWaterPercolation(new LiquidWaterPercolation.Parameters(
				get("IrriducibleWatSatSnow", w.irreducibleSaturation()), w.rateLimited(), THIN_FRACTION * cellIce),
				eos);
		double slope = values.containsKey("PointSlope") ? Math.toRadians(values.get("PointSlope")) : p.slopeAngle();
		ThinSnow thin = p.thinSnow();
		double soilRoughness = values.containsKey("SoilRoughness") ? 1e-3 * values.get("SoilRoughness")
				: thin.soilRoughness();
		double threshold = values.containsKey("ThresSnowSoilRough") ? 1e-3 * values.get("ThresSnowSoilRough")
				: values.containsKey("SoilRoughness") ? soilRoughness : thin.roughnessThreshold();
		thin = thin.with(soilRoughness, threshold, get("SoilEmissiv", thin.soilEmissivity()));
		return new SnowProcesses(eos, balance, turbulence, albedo, p.restoringSnowfall(), compaction, slope,
				percolation, p.sublimationThickness(), restratification, p.precipitation(), p.surfaceCoupling(), thin);
	}
}
