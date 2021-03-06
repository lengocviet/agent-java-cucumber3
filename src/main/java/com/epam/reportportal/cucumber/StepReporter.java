/*
 * Copyright 2019 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.reportportal.cucumber;

import com.epam.reportportal.listeners.Statuses;
import com.epam.reportportal.service.item.TestCaseIdEntry;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import cucumber.api.HookType;
import cucumber.api.Result;
import cucumber.api.TestStep;
import gherkin.ast.Step;
import io.reactivex.Maybe;

import java.util.Calendar;

/**
 * Cucumber reporter for ReportPortal that reports individual steps as test
 * methods.
 * <p>
 * Mapping between Cucumber and ReportPortal is as follows:
 * <ul>
 * <li>feature - SUITE</li>
 * <li>scenario - TEST</li>
 * <li>step - STEP</li>
 * </ul>
 * Background steps are reported as part of corresponding scenarios. Outline
 * example rows are reported as individual scenarios with [ROW NUMBER] after the
 * name. Hooks are reported as BEFORE/AFTER_METHOD items (NOTE: all screenshots
 * created in hooks will be attached to these, and not to the actual failing
 * steps!)
 *
 * @author Sergey_Gvozdyukevich
 * @author Serhii Zharskyi
 * @author Vitaliy Tsvihun
 */
public class StepReporter extends AbstractReporter {

	protected Maybe<String> currentStepId;
	protected Maybe<String> hookStepId;
	protected String hookStatus;

	public StepReporter() {
		super();
		currentStepId = null;
		hookStepId = null;
		hookStatus = null;
	}

	@Override
	protected Maybe<String> getRootItemId() {
		return null;
	}

	@Override
	protected void beforeStep(TestStep testStep) {
		Step step = currentScenarioContext.getStep(testStep);
		StartTestItemRQ rq = new StartTestItemRQ();
		rq.setName(Utils.buildNodeName(currentScenarioContext.getStepPrefix(), step.getKeyword(), Utils.getStepName(testStep), " "));
		rq.setDescription(Utils.buildMultilineArgument(testStep));
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType("STEP");
		String codeRef = Utils.getCodeRef(testStep);
		rq.setCodeRef(codeRef);
		TestCaseIdEntry testCaseIdEntry = Utils.getTestCaseId(testStep, codeRef);
		if (testCaseIdEntry != null) {
			rq.setTestCaseId(testCaseIdEntry.getId());
			rq.setTestCaseHash(testCaseIdEntry.getHash());
		}
		rq.setAttributes(Utils.getAttributes(testStep));
		currentStepId = RP.get().startTestItem(currentScenarioContext.getId(), rq);
	}

	@Override
	protected void afterStep(Result result) {
		reportResult(result, null);
		Utils.finishTestItem(RP.get(), currentStepId, result.getStatus().toString().toUpperCase());
		currentStepId = null;
	}

	@Override
	protected void beforeHooks(HookType hookType) {
		StartTestItemRQ rq = new StartTestItemRQ();
		String name = null;
		String type = null;
		switch (hookType) {
			case Before:
				name = "Before hooks";
				type = "BEFORE_TEST";
				break;
			case After:
				name = "After hooks";
				type = "AFTER_TEST";
				break;
			case AfterStep:
				name = "After step";
				type = "AFTER_METHOD";
				break;
			case BeforeStep:
				name = "Before step";
				type = "BEFORE_METHOD";
				break;
		}
		rq.setName(name);
		rq.setStartTime(Calendar.getInstance().getTime());
		rq.setType(type);

		hookStepId = RP.get().startTestItem(currentScenarioContext.getId(), rq);
		hookStatus = Statuses.PASSED;
	}

	@Override
	protected void afterHooks(Boolean isBefore) {
		Utils.finishTestItem(RP.get(), hookStepId, hookStatus);
		hookStepId = null;
	}

	@Override
	protected void hookFinished(TestStep step, Result result, Boolean isBefore) {
		reportResult(result, (isBefore ? "Before" : "After") + " hook: " + step.getCodeLocation());
		hookStatus = result.getStatus().toString();
	}

	@Override
	protected String getFeatureTestItemType() {
		return "SUITE";
	}

	@Override
	protected String getScenarioTestItemType() {
		return "SCENARIO";
	}
}
