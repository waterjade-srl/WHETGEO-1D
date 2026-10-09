package org.geoframe.whetgeo1d.core.snowsoil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.geoframe.whetgeo1d.core.snowsoil.verification.DiurnalCycleExperiment;
import org.geoframe.whetgeo1d.core.snowsoil.verification.DiurnalCycleExperiment.Errors;
import org.geoframe.whetgeo1d.core.snowsoil.verification.DiurnalCycleExperiment.Forcing;
import org.geoframe.whetgeo1d.core.snowsoil.verification.DiurnalCycleExperiment.Series;
import org.geoframe.whetgeo1d.snow.MassCriterionRestratification;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.TopCellThicknessCap;
import org.junit.Test;

/**
 * Fine-grid reference test of the diurnal cycle (A17): GEOtop's mass
 * criterion with and without the 2 cm top-cell cap against 5 mm cells. The
 * numbers are in document 007; here the qualitative findings are pinned.
 */
public class DiurnalCycleExperimentTest {

	private static void check(Forcing forcing) {
		DiurnalCycleExperiment experiment = DiurnalCycleExperiment.standard(forcing);
		SnowEquationOfState eos = experiment.equationOfState();
		MassCriterionRestratification geotop = new MassCriterionRestratification(
				MassCriterionRestratification.Parameters.geotopDefaults(), eos);
		TopCellThicknessCap capped = new TopCellThicknessCap(geotop, TopCellThicknessCap.DEFAULT_MAX_THICKNESS, eos);

		List<SnowCell> mass = experiment.stratigraphy(geotop);
		List<SnowCell> cap = experiment.stratigraphy(capped);
		assertEquals(10, mass.size());
		assertEquals(11, cap.size());
		assertEquals(experiment.snowMass(), SnowColumn.totalMass(mass), 1e-9);
		assertEquals(experiment.snowDepth(), SnowColumn.depth(cap), 1e-12);
		assertEquals(0.02, cap.get(cap.size() - 1).thickness(), 1e-12);

		Series reference = experiment.run(experiment.uniformGrid(0.005));
		Series check = experiment.run(experiment.uniformGrid(0.0025));
		Series coarse = experiment.run(mass);
		Series capRun = experiment.run(cap);
		for (Series run : List.of(reference, check, coarse, capRun)) {
			assertTrue(run.allConverged());
			assertTrue("closure " + run.maxClosureError(), run.maxClosureError() < 1e-3);
		}
		Errors referenceError = DiurnalCycleExperiment.compare(reference, check);
		Errors massError = DiurnalCycleExperiment.compare(coarse, reference);
		Errors capError = DiurnalCycleExperiment.compare(capRun, reference);

		// the reference is far more accurate than what it judges
		assertTrue(referenceError.surfaceFluxRms() < 0.2 * capError.surfaceFluxRms());
		assertTrue(referenceError.profileRms() < 0.15 * capError.profileRms());
		// the 2 cm cap halves the error at the surface ...
		assertTrue(capError.surfaceFluxRms() < 0.55 * massError.surfaceFluxRms());
		// ... but not inside the pack, where the thick elastic cells dominate
		assertTrue(capError.profileRms() > 0.5 * massError.profileRms());
		// deep in the pack the diurnal wave is gone: the interface flux hardly depends on the grid
		assertTrue(massError.interfaceFluxRms() < 0.02);
	}

	@Test
	public void imposedSurfaceTemperature() {
		check(Forcing.SURFACE_TEMPERATURE);
	}

	@Test
	public void linearisedSurfaceEnergyBalance() {
		check(Forcing.ROBIN);
	}
}
