/*
 * Copyright 2022 the original author or authors.
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

import com.google.common.base.Objects;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.CompatibilityCheckResult;
import org.gradle.api.internal.attributes.CompatibilityRule;
import org.gradle.api.internal.attributes.DisambiguationRule;
import org.gradle.api.internal.attributes.EmptySchema;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.util.internal.GUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DefaultAttributeSelectionSchema implements AttributeSelectionSchema {
    private final AttributesSchemaInternal consumerSchema;
    private final AttributesSchemaInternal producerSchema;

    private final Map<ExtraAttributesEntry, Attribute<?>[]> extraAttributesCache = new HashMap<>();

    public DefaultAttributeSelectionSchema(AttributesSchemaInternal schema) {
        this(schema, EmptySchema.INSTANCE);
    }

    public DefaultAttributeSelectionSchema(AttributesSchemaInternal consumerSchema, AttributesSchemaInternal producerSchema) {
        this.consumerSchema = consumerSchema;
        this.producerSchema = producerSchema;
    }

    @Override
    public boolean hasAttribute(Attribute<?> attribute) {
        return consumerSchema.getAttributes().contains(attribute) || producerSchema.getAttributes().contains(attribute);
    }

    @Override
    public Set<Object> disambiguate(Attribute<?> attribute, @Nullable Object requested, Set<Object> candidates) {
        DefaultMultipleCandidateResult<Object> result = null;

        DisambiguationRule<Object> rules = consumerSchema.disambiguationRules(attribute);
        if (rules.doesSomething()) {
            result = new DefaultMultipleCandidateResult<>(requested, candidates);
            rules.execute(result);
            if (result.hasResult()) {
                return result.getMatches();
            }
        }

        rules = producerSchema.disambiguationRules(attribute);
        if (rules.doesSomething()) {
            if (result == null) {
                result = new DefaultMultipleCandidateResult<>(requested, candidates);
            }
            rules.execute(result);
            if (result.hasResult()) {
                return result.getMatches();
            }
        }

        if (requested != null && candidates.contains(requested)) {
            return Collections.singleton(requested);
        }

        return candidates;
    }

    @Override
    public boolean matchValue(Attribute<?> attribute, Object requested, Object candidate) {
        if (requested.equals(candidate)) {
            return true;
        }

        CompatibilityCheckResult<Object> result = null;

        CompatibilityRule<Object> rules = consumerSchema.compatibilityRules(attribute);
        if (rules.doesSomething()) {
            result = new DefaultCompatibilityCheckResult<>(requested, candidate);
            rules.execute(result);
            if (result.hasResult()) {
                return result.isCompatible();
            }
        }

        rules = producerSchema.compatibilityRules(attribute);
        if (rules.doesSomething()) {
            if (result == null) {
                result = new DefaultCompatibilityCheckResult<>(requested, candidate);
            }
            rules.execute(result);
            if (result.hasResult()) {
                return result.isCompatible();
            }
        }

        return false;
    }

    @Override
    public Attribute<?> getAttribute(String name) {
        Attribute<?> attribute = consumerSchema.getAttributeByName(name);
        if (attribute != null) {
            return attribute;
        }
        return producerSchema.getAttributeByName(name);
    }

    @Override
    public Attribute<?>[] collectExtraAttributes(ImmutableAttributes[] candidateAttributeSets, ImmutableAttributes requested) {
        // It's almost always the same attribute sets which are compared, so in order to avoid a lot of memory allocation
        // during computation of the intersection, we cache the result here.
        ExtraAttributesEntry entry = new ExtraAttributesEntry(candidateAttributeSets, requested);
        return extraAttributesCache.computeIfAbsent(entry, key -> {
            Set<String> requestedNames = requested.keySet().stream().map(Attribute::getName).collect(Collectors.toSet());
            return Arrays.stream(candidateAttributeSets)
                .flatMap(it -> it.keySet().stream())
                .distinct()
                .filter(it -> !requestedNames.contains(it.getName()))
                // Some of these attributes might be weakly typed, e.g. coming as Strings from an
                // artifact repository. We always check whether the schema has a more strongly typed
                // version of an attribute and use that one instead to apply its disambiguation rules.
                .map(it -> GUtil.elvis(this.getAttribute(it.getName()), it))
                .toArray(Attribute<?>[]::new);
        });
    }

    @Override
    public PrecedenceResult orderByPrecedence(ImmutableAttributes requested) {
        if (consumerSchema.getAttributeDisambiguationPrecedence().isEmpty() && producerSchema.getAttributeDisambiguationPrecedence().isEmpty()) {
            // If no attribute precedence has been set anywhere, we can just iterate in order
            return new PrecedenceResult(IntStream.range(0, requested.keySet().size()).boxed().collect(Collectors.toList()));
        } else {
            // Populate requested attribute -> position in requested attribute list
            final Map<String, Integer> remaining = new LinkedHashMap<>();
            int position = 0;
            for (Attribute<?> requestedAttribute : requested.keySet()) {
                remaining.put(requestedAttribute.getName(), position++);
            }
            List<Integer> sorted = new ArrayList<>(remaining.size());

            // Add attribute index to sorted in the order of precedence by the consumer
            for (Attribute<?> preferredAttribute : consumerSchema.getAttributeDisambiguationPrecedence()) {
                if (requested.contains(preferredAttribute)) {
                    sorted.add(remaining.remove(preferredAttribute.getName()));
                }
            }
            // Add attribute index to sorted in the order of precedence by the producer
            for (Attribute<?> preferredAttribute : producerSchema.getAttributeDisambiguationPrecedence()) {
                if (remaining.containsKey(preferredAttribute.getName()) && requested.contains(preferredAttribute)) {
                    sorted.add(remaining.remove(preferredAttribute.getName()));
                }
            }
            // If nothing was sorted, there were no attributes in the request that matched any attribute precedences
            if (sorted.isEmpty()) {
                // Iterate in order
                return new PrecedenceResult(remaining.values());
            } else {
                // sorted now contains any requested attribute indices in the order they appear in
                // the consumer and producer's attribute precedences
                return new PrecedenceResult(sorted, remaining.values());
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultAttributeSelectionSchema that = (DefaultAttributeSelectionSchema) o;
        return consumerSchema.equals(that.consumerSchema) && producerSchema.equals(that.producerSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(consumerSchema, producerSchema);
    }

    /**
     * A cache entry key, leveraging _identity_ as the key, because we do interning.
     * This is a performance optimization.
     */
    private static class ExtraAttributesEntry {
        private final ImmutableAttributes[] candidateAttributeSets;
        private final ImmutableAttributes requestedAttributes;
        private final int hashCode;

        private ExtraAttributesEntry(ImmutableAttributes[] candidateAttributeSets, ImmutableAttributes requestedAttributes) {
            this.candidateAttributeSets = candidateAttributeSets;
            this.requestedAttributes = requestedAttributes;
            int hash = Arrays.hashCode(candidateAttributeSets);
            hash = 31 * hash + requestedAttributes.hashCode();
            this.hashCode = hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ExtraAttributesEntry that = (ExtraAttributesEntry) o;
            if (requestedAttributes != that.requestedAttributes) {
                return false;
            }
            if (candidateAttributeSets.length != that.candidateAttributeSets.length) {
                return false;
            }
            for (int i = 0; i < candidateAttributeSets.length; i++) {
                if (candidateAttributeSets[i] != that.candidateAttributeSets[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
