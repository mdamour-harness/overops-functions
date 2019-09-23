package com.takipi.udf.quality.gate;

import com.takipi.api.client.functions.input.RegressionsInput;
import com.takipi.api.client.functions.input.ReliabilityReportInput;
import com.takipi.api.client.functions.output.RegressionRow;
import com.takipi.api.client.functions.output.Series;
import com.takipi.api.client.functions.output.SeriesRow;
import com.takipi.udf.quality.QualityGateType;

public class NewEventsGate extends QualityGate {

	private final boolean criticalOnly;

	public NewEventsGate(boolean criticalOnly) {
		this.criticalOnly = criticalOnly;
	}

	@Override
	public QualityGateType getType() {
		return QualityGateType.NEW_EVENTS;
	}

	@Override
	public String getDesc() {
		return "Increasing Errors(" + (criticalOnly ? "critical" : "all") + ")";
	}

	@Override
	protected String getRelevantSeriesType() {
		return ReliabilityReportInput.REGRESSION_SERIES;
	}

	@Override
	protected boolean isBreached(Series series) {
		for (SeriesRow row : series) {
			RegressionRow regressionRow = (RegressionRow) row;

			if (isNewEvent(regressionRow)) {
				return true;
			}
		}

		return false;
	}

	private boolean isNewEvent(RegressionRow regressionRow) {
		return ((RegressionsInput.SEVERE_NEW_ISSUE_REGRESSIONS.equals(regressionRow.regression_type))
				|| ((!criticalOnly) && (RegressionsInput.NEW_ISSUE_REGRESSIONS.equals(regressionRow.regression_type))));
	}
}
