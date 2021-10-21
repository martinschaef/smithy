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
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.node.NodeMapper;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.model.validation.ValidationUtils;
import software.amazon.smithy.model.validation.ValidatorService;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.StringUtils;

/**
 * <p>Validates that all shape names, and values do not contain non-inclusive words.
 * *
 * <p>See AbstractModelTextValidator for scan implementation details.
 */
public final class InclusiveWordsValidator extends AbstractModelTextValidator {
    static final Map<String, List<String>> BUILT_IN_NONINCLUSIVE_TERMS = new HashMap<String, List<String>>() {
        {
            put("master", ListUtils.of("primary", "parent"));
            put("slave", ListUtils.of("secondary", "replica", "clone", "child"));
            put("blacklist", ListUtils.of("disallowlist"));
            put("whitelist", ListUtils.of("allowlist"));
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

        termsMap.putAll(config.getAppendNonInclusiveWords());
    }

    @Override
    protected void getValidationEvents(TextOccurrence occurrence,
                                       Consumer<ValidationEvent> validationEventConsumer) {
        for (Map.Entry<String, List<String>> termEntry: termsMap.entrySet()) {
            //lower casing the term will be more necessary when the terms are from config
            if (containsTerm(occurrence.text, termEntry.getKey().toLowerCase())) {
                switch (occurrence.locationType) {
                    case NAMESPACE:
                        validationEventConsumer.accept(ValidationEvent.builder()
                                .sourceLocation(SourceLocation.none())
                                .id(this.getClass().getSimpleName().replaceFirst("Validator$", ""))
                                .severity(Severity.WARNING)
                                .message(formatNonInclusiveWordsValidationMessage(termEntry, occurrence))
                                .build());
                        break;
                    case TRAIT_VALUE:
                    case TRAIT_KEY:
                        validationEventConsumer.accept(warning(occurrence.shape,
                                occurrence.trait.get().getSourceLocation(),
                                formatNonInclusiveWordsValidationMessage(termEntry, occurrence)));
                        break;
                    case SHAPE:
                    default:
                        validationEventConsumer.accept(warning(occurrence.shape, occurrence.shape.getSourceLocation(),
                                formatNonInclusiveWordsValidationMessage(termEntry, occurrence)));
                }
            }
        }
    }

    private static boolean containsTerm(String text, String term) {
        return text.toLowerCase().contains(term);
    }

    private static String formatNonInclusiveWordsValidationMessage(Map.Entry<String, List<String>> termEntry,
                                                                   TextOccurrence occurrence) {
        String replacementAddendum = termEntry.getValue().size() > 0
                ? String.format(" Consider using one of the following words instead: %s",
                    ValidationUtils.tickedList(termEntry.getValue()))
                : "";
        switch (occurrence.locationType) {
            case SHAPE:
                return String.format("%s shape uses a non-inclusive word `%s`.%s",
                        StringUtils.capitalize(occurrence.shape.getType().toString()),
                        termEntry.getKey(), replacementAddendum);
            case NAMESPACE:
                return String.format("%s namespace uses a non-inclusive word `%s`.%s",
                        occurrence.text, termEntry.getKey(), replacementAddendum);
            case TRAIT_KEY:
                String keyPropertyPathFormatted = formatPropertyPath(occurrence.traitPropertyPath);
                return String.format("`%s` trait has key {%s} that uses a non-inclusive word `%s`.%s",
                        Trait.getIdiomaticTraitName(occurrence.trait.get()), keyPropertyPathFormatted,
                        termEntry.getKey(), replacementAddendum);
            case TRAIT_VALUE:
                String valuePropertyPathFormatted = formatPropertyPath(occurrence.traitPropertyPath);
                if (occurrence.traitPropertyPath.isEmpty()) {
                    return String.format("'%s' trait has a value that contains a non-inclusive word `%s`.%s",
                            Trait.getIdiomaticTraitName(occurrence.trait.get()), termEntry.getKey(),
                            replacementAddendum);
                } else {
                    return String.format("'%s' trait value at path {%s} contains a non-inclusive word `%s`.%s",
                            Trait.getIdiomaticTraitName(occurrence.trait.get()), valuePropertyPathFormatted,
                            termEntry.getKey(), replacementAddendum);
                }
            default:
                throw new IllegalStateException();
        }
    }

    private static String formatPropertyPath(List<String> propertyPath) {
        return propertyPath.stream().collect(Collectors.joining(""));
    }
}
