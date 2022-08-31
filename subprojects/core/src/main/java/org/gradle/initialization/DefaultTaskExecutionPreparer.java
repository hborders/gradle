/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.initialization;

import org.gradle.api.internal.GradleInternal;
import org.gradle.execution.BuildConfigurationAction;
import org.gradle.execution.EntryTaskSelector;
import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.internal.buildtree.BuildModelParameters;
import org.gradle.internal.operations.BuildOperationExecutor;

import javax.annotation.Nullable;

public class DefaultTaskExecutionPreparer implements TaskExecutionPreparer {
    private final BuildOperationExecutor buildOperationExecutor;
    private final BuildConfigurationAction buildConfigurationAction;
    private final BuildModelParameters buildModelParameters;

    public DefaultTaskExecutionPreparer(
        BuildConfigurationAction buildConfigurationAction,
        BuildOperationExecutor buildOperationExecutor,
        BuildModelParameters buildModelParameters
    ) {
        this.buildConfigurationAction = buildConfigurationAction;
        this.buildOperationExecutor = buildOperationExecutor;
        this.buildModelParameters = buildModelParameters;
    }

    @Override
    public void scheduleRequestedTasks(GradleInternal gradle, @Nullable EntryTaskSelector selector, ExecutionPlan plan) {
        gradle.getOwner().getProjects().withMutableStateOfAllProjects(() -> {
            buildConfigurationAction.scheduleRequestedTasks(gradle, selector, plan);

            if (buildModelParameters.isConfigureOnDemand() && gradle.isRootBuild()) {
                new ProjectsEvaluatedNotifier(buildOperationExecutor).notify(gradle);
            }
        });
    }
}
