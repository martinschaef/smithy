/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.linters;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import software.amazon.smithy.model.node.NodeMapper;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.model.validation.ValidatorService;

/**
 * <p>Validates that all shape names, and values do not contain non-inclusive words.
 * *
 * <p>See AbstractModelTextValidator for scan implementation details.
 */
public final class InclusiveWordsValidator extends AbstractModelTextValidator {
    static final Map<String, List<String>> BUILT_IN_NONINCLUSIVE_TERMS = new HashMap<String, List<String>>() {
        {
            put("master", List.of("primary", "parent"));
            put("slave", List.of("secondary", "replica", "clone", "child"));
            put("blacklist", List.of("disallowlist"));
            put("whitelist", List.of("allowlist"));
        }};

    public static final class Provider extends ValidatorService.Provider {
        public Provider() {
            super(InclusiveWordsValidator.class, node -> {
                NodeMapper mapper = new NodeMapper();
                return new InclusiveWordsValidator(mapper.deserialize(node, InclusiveWordsValidator.Config.class));
            });
        }
    }

    /**
     * InclusiveWordsValidator validator configuration.
     */
    public static final class Config {
        private Map<String, List<String>> appendNonInclusiveWords = Collections.emptyMap();
        private Map<String, List<String>> overrideNonInclusiveWords = Collections.emptyMap();

        public Map<String, List<String>> getAppendNonInclusiveWords() {
            return appendNonInclusiveWords;
        }

        public Map<String, List<String>> getOverrideNonInclusiveWords() {
            return overrideNonInclusiveWords;
        }

        public void setAppendNonInclusiveWords(Map<String, List<String>> words) {
            this.appendNonInclusiveWords = words;
        }

        public void setOverrideNonInclusiveWords(Map<String, List<String>> words) {
            this.overrideNonInclusiveWords = words;
        }
    }

    final Map<String, List<String>> termsMap;

    private InclusiveWordsValidator(Config config) {
        termsMap = config.getOverrideNonInclusiveWords() == null || config.getOverrideNonInclusiveWords().isEmpty()
                ? new HashMap<>(BUILT_IN_NONINCLUSIVE_TERMS)
                : new HashMap<>(config.getOverrideNonInclusiveWords());

        if (config.getAppendNonInclusiveWords() != null) {
            for (Map.Entry<String, List<String>> termEntry:config.getAppendNonInclusiveWords().entrySet()) {
                termsMap.putIfAbsent(termEntry.getKey(), termEntry.getValue());
            }
        }
    }

    @Override
    protected void getValidationEvents(TextOccurrence occurrence,
                                       Consumer<ValidationEvent> validationEventConsumer) {
        for (Map.Entry<String, List<String>> termEntry: termsMap.entrySet()) {
            //lower casing the term will be more necessary when the terms are from config
            if (occurrence.text.toLowerCase().contains(termEntry.getKey().toLowerCase())) {
                if (occurrence.trait.isPresent()) {
                    validationEventConsumer.accept(warning(occurrence.shape, occurrence.trait.get().getSourceLocation(),
                            formatNonInclusiveWordsValidationMessage(termEntry, occurrence)));
                } else {
                    validationEventConsumer.accept(warning(occurrence.shape, occurrence.shape.getSourceLocation(),
                            formatNonInclusiveWordsValidationMessage(termEntry, occurrence)));
                }
            }
        }
    }

    private static String formatNonInclusiveWordsValidationMessage(Map.Entry<String, List<String>> termEntry,
                                                                   TextOccurrence occurrence) {
        String replacementAddendum = termEntry.getValue().size() > 0
                ? String.format(" Replacement suggestions: [%s]",
                    termEntry.getValue().stream().collect(Collectors.joining(", ")))
                : "";
        if (occurrence.trait.isPresent()) {
            String keyOrValue = occurrence.isTraitKeyName
                    ? "key"
                    : "value";
            String propertyPathFormatted = formatPropertyPath(occurrence.isTraitKeyName, occurrence.traitPropertyPath);
            if (occurrence.traitPropertyPath.isEmpty()) {
                return String.format("Non-inclusive word '%s' found on `%s` trait %s.%s",
                        termEntry.getKey(), Trait.getIdiomaticTraitName(occurrence.trait.get()),
                        keyOrValue, propertyPathFormatted, replacementAddendum);
            } else {
                return String.format("Non-inclusive word '%s' found on `%s` trait %s at {%s}.%s",
                        termEntry.getKey(), Trait.getIdiomaticTraitName(occurrence.trait.get()),
                        keyOrValue, propertyPathFormatted, replacementAddendum);
            }
        } else {
            return String.format("Non-inclusive word '%s' found on %s shape.%s",
                    termEntry.getKey(), occurrence.shape.getType().toString(), replacementAddendum);
        }
    }

    private static String formatPropertyPath(boolean isKeyName, List<String> propertyPath) {
        if (isKeyName) {
            return propertyPath.stream().limit(propertyPath.size() - 1).collect(Collectors.joining(""));
        } else {
            return propertyPath.stream().collect(Collectors.joining(""));
        }
    }
}
