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

import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import cucumber.api.PickleStepTestStep;
import cucumber.api.TestCase;
import cucumber.api.TestStep;
import cucumber.api.event.TestSourceRead;
import gherkin.AstBuilder;
import gherkin.Parser;
import gherkin.ParserException;
import gherkin.TokenMatcher;
import gherkin.ast.Background;
import gherkin.ast.Examples;
import gherkin.ast.Feature;
import gherkin.ast.GherkinDocument;
import gherkin.ast.ScenarioDefinition;
import gherkin.ast.ScenarioOutline;
import gherkin.ast.Step;
import gherkin.ast.TableRow;
import gherkin.pickles.PickleTag;
import io.reactivex.Maybe;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static com.epam.reportportal.cucumber.Utils.extractPickleTags;
import static com.epam.reportportal.cucumber.Utils.extractAttributes;


/**
 * Running context that contains mostly manipulations with Gherkin objects.
 * Keeps necessary information regarding current Feature, Scenario and Step
 *
 * @author Serhii Zharskyi
 * @author Vitaliy Tsvihun
 */
public class RunningContext {

    public static class FeatureContext {

        private static Map<String, TestSourceRead> pathToReadEventMap = new HashMap<String, TestSourceRead>();

        private String currentFeatureUri;
        private Maybe<String> currentFeatureId;
        private Feature currentFeature;
        private Set<ItemAttributesRQ> attributes;

        FeatureContext() {
            attributes = new HashSet<ItemAttributesRQ>();
        }

        static void addTestSourceReadEvent(String path, TestSourceRead event) {
            pathToReadEventMap.put(path, event);
        }

        ScenarioContext getScenarioContext(TestCase testCase) {
            ScenarioDefinition scenario = getScenario(testCase);
            ScenarioContext context = new ScenarioContext();
            context.processScenario(scenario);
            context.setTestCase(testCase);
            context.processBackground(getBackground());
            context.processScenarioOutline(scenario);
            context.processTags(testCase.getTags());
            return context;
        }


        FeatureContext processTestSourceReadEvent(TestCase testCase) {
            TestSourceRead event = pathToReadEventMap.get(testCase.getUri());
            currentFeature = getFeature(event.source);
            currentFeatureUri = event.uri;
            attributes = extractAttributes(currentFeature.getTags());
            return this;
        }

        Feature getFeature(String source) {
            Parser<GherkinDocument> parser = new Parser<GherkinDocument>(new AstBuilder());
            TokenMatcher matcher = new TokenMatcher();
            GherkinDocument gherkinDocument;
            try {
                gherkinDocument = parser.parse(source, matcher);
            } catch (ParserException e) {
                // Ignore exceptions
                return null;
            }
            return gherkinDocument.getFeature();
        }

        Background getBackground() {
            ScenarioDefinition background = getFeature().getChildren().get(0);
            if (background instanceof Background) {
                return (Background) background;
            } else {
                return null;
            }
        }

        Feature getFeature() {
            return currentFeature;
        }

        Set<ItemAttributesRQ> getAttributes() {
            return attributes;
        }

        String getUri() {
            return currentFeatureUri;
        }

        Maybe<String> getFeatureId() {
            return currentFeatureId;
        }

        void setFeatureId(Maybe<String> featureId) {
            this.currentFeatureId = featureId;
        }

        <T extends ScenarioDefinition> T getScenario(TestCase testCase) {
            List<ScenarioDefinition> featureScenarios = getFeature().getChildren();
            for (ScenarioDefinition scenario : featureScenarios) {
                if (scenario instanceof Background) {
                    continue;
                }
                if (testCase.getLine() == scenario.getLocation().getLine() && testCase.getName().equals(scenario.getName())) {
                    return (T) scenario;
                } else {
                    if (scenario instanceof ScenarioOutline) {
                        for (Examples example : ((ScenarioOutline) scenario).getExamples()) {
                            for (TableRow tableRow : example.getTableBody()) {
                                if (tableRow.getLocation().getLine() == testCase.getLine()) {
                                    return (T) scenario;
                                }
                            }
                        }
                    }
                }
            }
            throw new IllegalStateException("Scenario can't be null!");
        }
    }


    public static class ScenarioContext {

        private static Map<Integer, ArrayDeque<String>> outlineIterationsMap = new HashMap<Integer, ArrayDeque<String>>();

        private Maybe<String> id = null;
        private Background background;
        private ScenarioDefinition scenario;
        private Queue<Step> backgroundSteps;
        private Map<Integer, Step> scenarioLocationMap;
        private Set<ItemAttributesRQ> attributes;
        private TestCase testCase;
        private boolean hasBackground = false;

        ScenarioContext() {
            backgroundSteps = new ArrayDeque<Step>();
            scenarioLocationMap = new HashMap<Integer, Step>();
            attributes = new HashSet<ItemAttributesRQ>();
        }

        ScenarioContext processScenario(ScenarioDefinition scenario) {
            this.scenario = scenario;
            for (Step step : scenario.getSteps()) {
                scenarioLocationMap.put(step.getLocation().getLine(), step);
            }
            return this;
        }

        void processBackground(Background background) {
            if (background != null) {
                this.background = background;
                hasBackground = true;
                backgroundSteps.addAll(background.getSteps());
                mapBackgroundSteps(background);
            }
        }

        void processScenarioOutline(ScenarioDefinition scenarioOutline) {
            if (isScenarioOutline(scenarioOutline)) {
                if (!hasOutlineSteps()) {
                    int num = 0;
                    outlineIterationsMap.put(scenario.getLocation().getLine(), new ArrayDeque<String>());
                    for (Examples example : ((ScenarioOutline) scenarioOutline).getExamples()) {
                        num += example.getTableBody().size();
                    }
                    for (int i = 1; i <= num; i++) {
                        outlineIterationsMap.get(scenario.getLocation().getLine()).add(" [" + i + "]");
                    }
                }
            }
        }

        void processTags(List<PickleTag> pickleTags) {
            attributes = extractPickleTags(pickleTags);
        }

        void mapBackgroundSteps(Background background) {
            for (Step step : background.getSteps()) {
                scenarioLocationMap.put(step.getLocation().getLine(), step);
            }
        }

        String getName() {
            return scenario.getName();
        }

        String getKeyword() {
            return scenario.getKeyword();
        }

        int getLine() {
            if (isScenarioOutline(scenario)) {
                return testCase.getLine();
            }
            return scenario.getLocation().getLine();
        }

        Set<ItemAttributesRQ> getAttributes() {
            return attributes;
        }

        String getStepPrefix() {
            if (hasBackground() && withBackground()) {
                return background.getKeyword().toUpperCase() + AbstractReporter.COLON_INFIX;
            } else {
                return "";
            }
        }

        Step getStep(TestStep testStep) {
            PickleStepTestStep pickleStepTestStep = (PickleStepTestStep) testStep;
            Step step = scenarioLocationMap.get(pickleStepTestStep.getStepLine());
            if (step != null) {
                return step;
            }
            throw new IllegalStateException(String.format("Trying to get step for unknown line in feature. Scenario: %s, line: %s", scenario.getName(), getLine()));
        }

        Maybe<String> getId() {
            return id;
        }

        void setId(Maybe<String> newId) {
            if (id != null) {
                throw new IllegalStateException("Attempting re-set scenario ID for unfinished scenario.");
            }
            id = newId;
        }

        void setTestCase(TestCase testCase) {
            this.testCase = testCase;
        }

        void nextBackgroundStep() {
            backgroundSteps.poll();
        }

        boolean isScenarioOutline(ScenarioDefinition scenario) {
            if (scenario != null) {
                return scenario instanceof ScenarioOutline;
            }
            return false;
        }

        boolean withBackground() {
            return !backgroundSteps.isEmpty();
        }

        boolean hasBackground() {
            return hasBackground && background != null;
        }

        boolean hasOutlineSteps() {
            return outlineIterationsMap.get(scenario.getLocation().getLine()) != null && !outlineIterationsMap.get(scenario.getLocation().getLine()).isEmpty();
        }

        String getOutlineIteration() {
            if (hasOutlineSteps()) {
                return outlineIterationsMap.get(scenario.getLocation().getLine()).poll();
            }
            return null;
        }
    }
}
