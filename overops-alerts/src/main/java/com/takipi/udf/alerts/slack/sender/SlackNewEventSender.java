package com.takipi.udf.alerts.slack.sender;

import com.takipi.api.client.result.event.EventResult;
import com.takipi.udf.ContextArgs;
import com.takipi.udf.alerts.slack.SlackFunction.SlackInput;
import com.takipi.udf.alerts.util.AlertUtil;

public class SlackNewEventSender extends SlackEventSender {
	private static final String SUBHEADING_PLAIN_FORMAT = "UDF: A new %s has been detected in %s (alert added by %s)";
	private static final String SUBHEADING_RICH_FORMAT = "UDF: A new *%s* has been detected in *%s* (alert added by *%s*)";

	private SlackNewEventSender(SlackInput input, EventResult event, ContextArgs contextArgs) {
		super(input, event, contextArgs);
	}

	@Override
	protected String getSubheadingPlainFormat() {
		return SUBHEADING_PLAIN_FORMAT;
	}

	@Override
	protected String getSubheadingRichFormat() {
		return SUBHEADING_RICH_FORMAT;
	}

	@Override
	protected String getInternalDescription() {
		return "New request: " + event.id;
	}

	@Override
	protected String createSubheading(String serviceName, String format) {
		return String.format(format, event.summary, serviceName, contextArgs.data("added_by_user", "Unknown"));
	}

	public static SlackSender create(SlackInput input, ContextArgs contextArgs) {
		EventResult event = AlertUtil.getEvent(contextArgs);

		if (event == null) {
			// Logging happens inside getEvent if needed.
			//
			return null;
		}

		return new SlackNewEventSender(input, event, contextArgs);
	}
}
