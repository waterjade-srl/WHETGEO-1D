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

import java.util.List;

import org.geoframe.whetgeo1d.snow.SnowColumn;

/**
 * Mass and energy balance of the whole column, snow and soil, against the
 * external fluxes the step actually applied (VAL-7, diagnostic 3), with every
 * numerical correction reported on its own and never subtracted from a
 * closure. Masses in kg m-2, energies in J m-2, over one step, or summed over
 * steps ({@link #plus}); energies of the snow with the reference of
 * {@link org.geoframe.whetgeo1d.snow.SnowCell#internalEnergy()}, of the soil
 * with the reference of its closures (both: liquid water at T has
 * L<sub>f</sub> + c<sub>w</sub> (T - 273.15 K) per kg).
 *
 * <pre>
 * Δ SWE + Δ W_soil = snowfall + rainfall - vapour - evapotranspiration - drainage - runoff
 *                    - unmodelledSoilInput                                         + massClosure
 * Δ U_snow + Δ U_soil = surfaceEnergy + linearisationEnergy + pendingApplied + bottomEnergy
 *                    + snowfallEnergy + rainEnergy - vapourEnergy - evapotranspirationEnergy
 *                    - drainageEnergy - runoffEnergy - unmodelledSoilInputEnergy   + energyClosure
 * </pre>
 *
 * <b>Storages</b> are read from the state before and after the step: SWE and U
 * of the cells, the soil water of {@link SnowSoilSolver#soilWaterStorage()}
 * (liquid and ice, storage above saturation included) and the soil energy of
 * {@link SnowSoilSolver#soilInternalEnergy()} (the water above saturation
 * included). <b>External fluxes</b> are what crosses the boundary of the column:
 * precipitation and its energy, the vapour exchanged by the pack and the energy
 * it carries (its latent heat is in the surface flux), evapotranspiration,
 * drainage at the bottom, runoff, the conductive flux at the bottom and the
 * flux at the top. The top flux the heat step applied is split into the
 * surface balance at the final surface temperature EB(T<sub>s</sub>) Δt
 * ({@code surfaceEnergy}), the linearisation error of SOL-2/DN-09
 * ({@code linearisationEnergy}) and the deferred surface energy entering in
 * the step ({@code pendingApplied}, SURF-4); all three are external to the
 * column and enter the closure. Without snow the top flux is the bare-soil
 * condition of the forcing, all in {@code surfaceEnergy}. Without soil water the
 * water handed to the soil leaves the column: {@code unmodelledSoilInput}.
 *
 * <p>
 * <b>Closures and corrections.</b> The closures check the step end to end. They
 * are expected to be, to round-off,
 *
 * <pre>
 * massClosure   ≈ snowMassClosure + richardsBalanceError + soilInputMismatchMass
 * energyClosure ≈ heatNewtonClosure + soilStorageHeat + snowEnergyClosure + soilInputMismatchEnergy
 * </pre>
 *
 * ({@link #explainedMassClosure()}, {@link #explainedEnergyClosure()}), plus,
 * for the energy, the error of the inversion of the soil closures in the
 * transport of the soil water. These terms and the other numerical
 * corrections (linearisation, phase mismatch of the vapour, unmet vapour) are
 * reported as fields of their own: VAL-7 asks that none be subtracted
 * silently.
 *
 * @param snowMassChange            Δ SWE
 * @param soilWaterChange           Δ water in the soil, liquid and ice; zero
 *                                  without soil water
 * @param snowfall                  snow reaching the ground
 * @param rainfall                  rain reaching the ground, on snow or bare
 *                                  soil
 * @param vapour                    vapour exchanged by the pack, positive
 *                                  removed
 * @param evapotranspiration        water taken from the soil by
 *                                  evapotranspiration, as applied
 * @param drainage                  water leaving the bottom of the soil
 * @param runoff                    water the soil surface could not take
 * @param unmodelledSoilInput       water handed to a soil without Richards
 *                                  (fixed suction), which leaves the column;
 *                                  zero with soil water
 * @param massClosure               see above
 * @param snowEnergyChange          Δ U of the pack
 * @param soilEnergyChange          Δ U of the soil
 * @param surfaceEnergy             EB(T<sub>s</sub>) Δt over snow, the bare-soil
 *                                  top flux without snow: the physical surface
 *                                  input
 * @param linearisationEnergy       Δt (G<sub>lin</sub>(T<sub>s</sub>) -
 *                                  EB(T<sub>s</sub>)), {@link SnowSoilStepResult#linearisationEnergy()}
 * @param pendingApplied            deferred surface energy that entered in the
 *                                  step
 * @param bottomEnergy              energy entered through the bottom face
 * @param snowfallEnergy            U of the fresh snow
 * @param rainEnergy                U of the rain, on snow and on bare soil
 * @param vapourEnergy              U leaving with the vapour
 * @param evapotranspirationEnergy  U leaving with evapotranspiration
 * @param drainageEnergy            U leaving with the drainage
 * @param runoffEnergy              U leaving with the runoff, at the energy per
 *                                  kg of the water handed to the soil
 * @param unmodelledSoilInputEnergy U of {@code unmodelledSoilInput}
 * @param energyClosure             see above
 * @param phaseMismatchEnergy       phase mismatch of the vapour created in the
 *                                  step (document 008, SOL-2): deferred to a
 *                                  later step, it is in the closure only when it
 *                                  enters as {@code pendingApplied}
 * @param heatNewtonClosure         sum of the Newton residuals of the heat step,
 *                                  snow and soil ({@link EnergyBudget#closureError()})
 * @param soilEnergyClosure         soil energy closure of the step
 *                                  ({@link SnowSoilStepResult#soilEnergyClosure()}):
 *                                  the residuals of the soil cells, already in
 *                                  {@code heatNewtonClosure}; zero without soil
 *                                  water
 * @param soilStorageHeat           energy created by the water above saturation
 *                                  of a freezing soil (document 012); zero
 *                                  without soil water
 * @param snowMassClosure           mass closure of the snowpack operators
 *                                  ({@link SnowBudget})
 * @param snowEnergyClosure         energy closure of the snowpack operators
 * @param richardsBalanceError      Richards balance error, the sum of WHETGEO's
 *                                  {@code errorVolume}; zero without soil water
 * @param soilInputMismatchMass     infiltration + runoff - water handed to the
 *                                  soil: water created at the hand-over; zero
 *                                  without soil water
 * @param soilInputMismatchEnergy   infiltration energy + runoff energy - energy
 *                                  handed to the soil: energy created at the
 *                                  hand-over; zero without soil water
 * @param unmetVapour               vapour the pack could not supply or take,
 *                                  whose latent heat is in the surface flux
 */
public record ColumnBalance(double snowMassChange, double soilWaterChange, double snowfall, double rainfall,
		double vapour, double evapotranspiration, double drainage, double runoff, double unmodelledSoilInput,
		double massClosure, double snowEnergyChange, double soilEnergyChange, double surfaceEnergy,
		double linearisationEnergy, double pendingApplied, double bottomEnergy, double snowfallEnergy,
		double rainEnergy, double vapourEnergy, double evapotranspirationEnergy, double drainageEnergy,
		double runoffEnergy, double unmodelledSoilInputEnergy, double energyClosure, double phaseMismatchEnergy,
		double heatNewtonClosure, double soilEnergyClosure, double soilStorageHeat, double snowMassClosure,
		double snowEnergyClosure, double richardsBalanceError, double soilInputMismatchMass,
		double soilInputMismatchEnergy, double unmetVapour) {

	/** Names of the components, in order, e.g. for the columns of an output. */
	public static final List<String> NAMES = List.of("snowMassChange", "soilWaterChange", "snowfall", "rainfall",
			"vapour", "evapotranspiration", "drainage", "runoff", "unmodelledSoilInput", "massClosure",
			"snowEnergyChange", "soilEnergyChange", "surfaceEnergy", "linearisationEnergy", "pendingApplied",
			"bottomEnergy", "snowfallEnergy", "rainEnergy", "vapourEnergy", "evapotranspirationEnergy",
			"drainageEnergy", "runoffEnergy", "unmodelledSoilInputEnergy", "energyClosure", "phaseMismatchEnergy",
			"heatNewtonClosure", "soilEnergyClosure", "soilStorageHeat", "snowMassClosure", "snowEnergyClosure",
			"richardsBalanceError", "soilInputMismatchMass", "soilInputMismatchEnergy", "unmetVapour");

	/** All terms zero: the start of a sum. */
	public static final ColumnBalance ZERO = fromArray(new double[NAMES.size()]);

	/**
	 * Storages of the column, read from the state of the solver.
	 *
	 * @param snowMass   SWE [kg m-2]
	 * @param snowEnergy U of the pack [J m-2]
	 * @param soilWater  water in the soil [kg m-2], zero without soil water
	 * @param soilEnergy U of the soil [J m-2]
	 */
	public record Storage(double snowMass, double snowEnergy, double soilWater, double soilEnergy) {

		/** The storages of the solver now. */
		public static Storage of(SnowSoilSolver solver) {
			return new Storage(SnowColumn.totalMass(solver.snow()), SnowColumn.internalEnergy(solver.snow()),
					solver.hasSoilWater() ? solver.soilWaterStorage() : 0.0, solver.soilInternalEnergy());
		}
	}

	/**
	 * Balance of one step.
	 *
	 * @param before  storages at the start of the step
	 * @param after   storages at the end of the step
	 * @param forcing the forcing of the step
	 * @param result  its result
	 * @param terms   {@link SnowSoilSolver#lastStepTerms()} after the step
	 */
	public static ColumnBalance of(Storage before, Storage after, StepForcing forcing, SnowSoilStepResult result,
			ColumnStepTerms terms) {
		SnowBudget budget = result.budget();
		EnergyBudget heat = result.heat().energy();
		SoilWaterStepResult water = result.soilWater();
		boolean withWater = water != null;

		double evapotranspiration = withWater ? water.evapotranspiration() : 0.0;
		double drainage = withWater ? water.drainage() : 0.0;
		double runoff = withWater ? water.runoff() : 0.0;
		double unmodelled = withWater ? 0.0 : result.soilInputMass();
		double snowMassChange = after.snowMass() - before.snowMass();
		double soilWaterChange = after.soilWater() - before.soilWater();
		double massClosure = snowMassChange + soilWaterChange - (forcing.snowfall() + forcing.rainfall()
				- budget.vapour() - evapotranspiration - drainage - runoff - unmodelled);

		double rainEnergy = budget.rainEnergy() + terms.rainOnBareSoilEnergy();
		double evapotranspirationEnergy = withWater ? terms.soilSinkEnergy() : 0.0;
		double drainageEnergy = withWater ? result.soilDrainageEnergy() : 0.0;
		double runoffEnergy = runoff > 0.0 ? runoff * (result.soilInputEnergy() / result.soilInputMass()) : 0.0;
		double unmodelledEnergy = withWater ? 0.0 : result.soilInputEnergy();
		double surfaceEnergy = heat.topInput() - result.linearisationEnergy() - terms.pendingApplied();
		double snowEnergyChange = after.snowEnergy() - before.snowEnergy();
		double soilEnergyChange = after.soilEnergy() - before.soilEnergy();
		double energyClosure = snowEnergyChange + soilEnergyChange - (heat.topInput() + heat.bottomInput()
				+ budget.snowfallEnergy() + rainEnergy - budget.vapourEnergy() - evapotranspirationEnergy
				- drainageEnergy - runoffEnergy - unmodelledEnergy);

		double mismatchMass = withWater ? water.infiltration() + runoff - result.soilInputMass() : 0.0;
		double mismatchEnergy = withWater ? terms.infiltrationEnergy() + runoffEnergy - result.soilInputEnergy()
				: 0.0;
		return new ColumnBalance(snowMassChange, soilWaterChange, forcing.snowfall(), forcing.rainfall(),
				budget.vapour(), evapotranspiration, drainage, runoff, unmodelled, massClosure, snowEnergyChange,
				soilEnergyChange, surfaceEnergy, result.linearisationEnergy(), terms.pendingApplied(),
				heat.bottomInput(), budget.snowfallEnergy(), rainEnergy, budget.vapourEnergy(),
				evapotranspirationEnergy, drainageEnergy, runoffEnergy, unmodelledEnergy, energyClosure,
				terms.phaseMismatchEnergy(), heat.closureError(), withWater ? result.soilEnergyClosure() : 0.0,
				withWater ? result.soilStorageHeat() : 0.0, budget.massClosure(), budget.energyClosure(),
				withWater ? water.balanceError() : 0.0, mismatchMass, mismatchEnergy, result.unmetVapour());
	}

	/** What the mass closure should be: the operator closure, the Richards error and the hand-over [kg m-2]. */
	public double explainedMassClosure() {
		return snowMassClosure + richardsBalanceError + soilInputMismatchMass;
	}

	/**
	 * What the energy closure should be: the Newton residuals of the heat step,
	 * the storage heat of the frozen soil, the operator closure and the
	 * hand-over [J m-2].
	 */
	public double explainedEnergyClosure() {
		return heatNewtonClosure + soilStorageHeat + snowEnergyClosure + soilInputMismatchEnergy;
	}

	/** Term-by-term sum, e.g. the balance of an interval or of the run. */
	public ColumnBalance plus(ColumnBalance other) {
		double[] a = toArray();
		double[] b = other.toArray();
		for (int i = 0; i < a.length; i++) {
			a[i] += b[i];
		}
		return fromArray(a);
	}

	/** Term by term, the larger of the magnitudes: the maxima over steps. */
	public ColumnBalance maxAbs(ColumnBalance other) {
		double[] a = toArray();
		double[] b = other.toArray();
		for (int i = 0; i < a.length; i++) {
			a[i] = Math.max(Math.abs(a[i]), Math.abs(b[i]));
		}
		return fromArray(a);
	}

	/** The components in the order of {@link #NAMES}. */
	public double[] toArray() {
		return new double[] { snowMassChange, soilWaterChange, snowfall, rainfall, vapour, evapotranspiration,
				drainage, runoff, unmodelledSoilInput, massClosure, snowEnergyChange, soilEnergyChange, surfaceEnergy,
				linearisationEnergy, pendingApplied, bottomEnergy, snowfallEnergy, rainEnergy, vapourEnergy,
				evapotranspirationEnergy, drainageEnergy, runoffEnergy, unmodelledSoilInputEnergy, energyClosure,
				phaseMismatchEnergy, heatNewtonClosure, soilEnergyClosure, soilStorageHeat, snowMassClosure,
				snowEnergyClosure, richardsBalanceError, soilInputMismatchMass, soilInputMismatchEnergy,
				unmetVapour };
	}

	/** The inverse of {@link #toArray()}. */
	public static ColumnBalance fromArray(double[] v) {
		if (v.length != NAMES.size()) {
			throw new IllegalArgumentException("expected " + NAMES.size() + " terms, got " + v.length);
		}
		return new ColumnBalance(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12],
				v[13], v[14], v[15], v[16], v[17], v[18], v[19], v[20], v[21], v[22], v[23], v[24], v[25], v[26],
				v[27], v[28], v[29], v[30], v[31], v[32], v[33]);
	}
}
