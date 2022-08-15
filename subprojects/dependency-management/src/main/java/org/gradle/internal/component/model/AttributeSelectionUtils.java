/*
 * Copyright 2018 the original author or authors.
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
package org.gradle.internal.component.model;

import org.gradle.api.attributes.Attribute;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.util.internal.GUtil;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class AttributeSelectionUtils {
    public static Attribute<?>[] collectExtraAttributes(AttributeSelectionSchema schema, ImmutableAttributes[] candidateAttributeSets, ImmutableAttributes requested) {
        Set<String> requestedNames = requested.keySet().stream().map(Attribute::getName).collect(Collectors.toSet());

        return Arrays.stream(candidateAttributeSets)
            .flatMap(it -> it.keySet().stream())
            .distinct()
            .filter(it -> !requestedNames.contains(it.getName()))
            .map(it -> GUtil.elvis(schema.getAttribute(it.getName()), it))
            .toArray(Attribute<?>[]::new);
    }
}
