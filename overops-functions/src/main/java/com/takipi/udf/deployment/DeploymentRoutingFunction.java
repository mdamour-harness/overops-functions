package com.takipi.udf.deployment;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.joda.time.format.ISODateTimeFormat;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.category.Category;
import com.takipi.api.client.data.deployment.SummarizedDeployment;
import com.takipi.api.client.data.view.SummarizedView;
import com.takipi.api.client.data.view.ViewFilters;
import com.takipi.api.client.data.view.ViewInfo;
import com.takipi.api.client.util.category.CategoryUtil;
import com.takipi.api.client.util.client.ClientUtil;
import com.takipi.api.client.util.view.ViewUtil;
import com.takipi.common.util.CollectionUtil;
import com.takipi.udf.ContextArgs;
import com.takipi.udf.input.Input;
import com.takipi.udf.util.TestUtil;

public class DeploymentRoutingFunction {
	private static final boolean SHARED = true;
	private static final boolean IMMUTABLE_VIEWS = true;

	public static String validateInput(String rawInput) {
		return parseDeploymentRoutingInput(rawInput).toString();
	}

	static DeploymentRoutingInput parseDeploymentRoutingInput(String rawInput) {
		System.out.println("validateInput rawInput: " + rawInput);

		if (Strings.isNullOrEmpty(rawInput)) {
			throw new IllegalArgumentException("Input is empty");
		}

		DeploymentRoutingInput input;

		try {
			input = DeploymentRoutingInput.of(rawInput);
		} catch (Exception e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}

		if (input.category_name == null) {
			throw new IllegalArgumentException("'category_name' must exist");
		}

		if (input.max_views <= 0) {
			throw new IllegalArgumentException("'max_views' must be greater than zero");
		}

		return input;
	}

	public static void execute(String rawContextArgs, String rawInput) {
		System.out.println("execute: " + rawContextArgs);

		ContextArgs args = (new Gson()).fromJson(rawContextArgs, ContextArgs.class);

		if (!args.viewValidate()) {
			throw new IllegalArgumentException("Invalid context args - " + rawContextArgs);
		}

		DeploymentRoutingInput input = parseDeploymentRoutingInput(rawInput);

		buildDeploymentRoutingViews(args, input);
	}

	private static String cleanPrefix(DeploymentRoutingInput input) {
		return input.prefix.replace("'", "");
	}

	private static void buildDeploymentRoutingViews(ContextArgs args, DeploymentRoutingInput input) {
		ApiClient apiClient = args.apiClient();
		String serviceId = args.serviceId;
		int maxViews = input.max_views;

		Collection<SummarizedDeployment> deployments = ClientUtil.getSummarizedDeployments(apiClient, serviceId, false);

		if (CollectionUtil.safeIsEmpty(deployments)) {
			System.err.println("Could not acquire all deployments of service " + serviceId);
			return;
		}

		Map<String, SummarizedView> views = ViewUtil.getServiceViewsByName(apiClient, serviceId);

		if (CollectionUtil.safeIsEmpty(views)) {
			System.err.println("Could not acquire views of service " + serviceId);
			return;
		}

		// sort deployments by descending last_seen
		List<SummarizedDeployment> sortedDeployments = deployments.parallelStream()
				.sorted((dep1, dep2) -> ISODateTimeFormat.dateTimeParser().parseDateTime(dep2.last_seen)
						.compareTo(ISODateTimeFormat.dateTimeParser().parseDateTime(dep1.last_seen)))
				.collect(Collectors.toList());

		maxViews = Math.min(maxViews, sortedDeployments.size());

		// remove existing views that exceed maxViews
		for (SummarizedDeployment deployment : sortedDeployments.subList(maxViews, sortedDeployments.size())) {
			SummarizedView deploymentView = views.get(toViewName(input, deployment.name));

			if (deploymentView != null) {
				System.out.println("Removing view " + deploymentView.id + " for deployment " + deployment.name);
				ViewUtil.removeView(apiClient, serviceId, deploymentView.id);
			}
		}

		// create missing views up to maxViews
		List<ViewInfo> newDeploymentViewsInfo = Lists.newArrayList();

		for (SummarizedDeployment deployment : sortedDeployments.subList(0, maxViews)) {
			String deploymentName = deployment.name;
			SummarizedView deploymentView = views.get(toViewName(input, deployment.name));

			if (deploymentView != null) {
				System.out.println("View " + deploymentView.id + " already exists for deployment " + deploymentName);
				continue;
			}

			System.out.println("Queueing view creation for deployment " + deploymentName);

			ViewInfo viewInfo = new ViewInfo();

			viewInfo.name = toViewName(input, deploymentName);
			viewInfo.filters = new ViewFilters();
			viewInfo.filters.introduced_by = Collections.singletonList(deploymentName);
			viewInfo.shared = SHARED;
			viewInfo.immutable = IMMUTABLE_VIEWS;

			newDeploymentViewsInfo.add(viewInfo);
		}

		if (CollectionUtil.safeIsEmpty(newDeploymentViewsInfo)) {
			return;
		}

		String categoryId = createDepCategory(apiClient, serviceId, input);

		ViewUtil.createFilteredViews(apiClient, serviceId, newDeploymentViewsInfo, views, categoryId);
	}

	private static String toViewName(DeploymentRoutingInput input, String dep) {
		String result;

		if (input.prefix != null) {
			result = cleanPrefix(input) + dep;
		} else {
			result = dep;
		}

		return result;
	}

	private static String createDepCategory(ApiClient apiClient, String serviceId, DeploymentRoutingInput input) {
		Category category = CategoryUtil.getServiceCategoryByName(apiClient, serviceId, input.category_name);

		String result;

		if (category == null) {
			result = CategoryUtil.createCategory(input.category_name, serviceId, apiClient, SHARED);

			System.out.println("Created category " + result + " for " + input.category_name);
		} else {
			result = category.id;
		}

		return result;
	}

	static class DeploymentRoutingInput extends Input {
		public String category_name; // The category in which to place views
		public int max_views; // Max number of views to create in the category
		public String prefix; // An optional prefix to add to the view name (e.g. 'New in')

		private DeploymentRoutingInput(String raw) {
			super(raw);
		}

		static DeploymentRoutingInput of(String raw) {
			return new DeploymentRoutingInput(raw);
		}

		@Override
		public String toString() {
			return "DeploymentRouting: " + category_name + " " + max_views;
		}
	}

	// A sample program on how to programmatically activate
	// DeploymentRoutingFunction
	public static void main(String[] args) {
		String rawContextArgs = TestUtil.getViewContextArgs(args, "All Events");

		// some test values
		String[] sampleValues = new String[] { "category_name=CI / CD", "prefix='New'' in '", "max_views=4" };

		DeploymentRoutingFunction.execute(rawContextArgs, String.join("\n", sampleValues));
	}
}
