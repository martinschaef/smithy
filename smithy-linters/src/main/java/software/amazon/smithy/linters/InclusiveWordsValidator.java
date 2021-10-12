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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.model.validation.ValidatorService;

/**
 * <p>Validates that all shape names, and values do not contain non-inclusive words.
 *
 * <p>Does a full scan of the model and gathers all text along with location data
 * and uses a generic function to decide what to do with each text occurrence. The
 * full text search traversal has no knowledge of what it is looking for and descends
 * fully into the structure of all traits.
 *
 * <p>Prelude shape definitions are not examined, however all values
 */
public class InclusiveWordsValidator extends AbstractModelTextValidator {
    /** Emitted when a model contains a non-inclusive term. */
    static final Set<String> NONINCLUSIVE_TERMS = new HashSet<>(Arrays.asList(
            "master",
            "slave",
            "blacklist",
            "whitelist"
    ));

    public static final class Provider extends ValidatorService.Provider {
        public Provider() {
            super(InclusiveWordsValidator.class, InclusiveWordsValidator::new);
        }
    }

    @Override
    protected void getValidationEvents(TextOccurrence occurrence,
                                       Consumer<ValidationEvent> validationEventConsumer) {
        List<String> words = new ArrayList<>();
        for (String term:NONINCLUSIVE_TERMS) {
            //lower casing the term will be more necessary when the terms are from config
            if (occurrence.text.toLowerCase().contains(term.toLowerCase())) {
                words.add(term);
            }
        }

        if (!words.isEmpty()) {
            if (occurrence.trait.isPresent()) {
                validationEventConsumer.accept(warning(occurrence.shape, occurrence.trait.get().getSourceLocation(),
                        formatInclusiveWordsValidationMessage(words, occurrence)));
            } else {
                validationEventConsumer.accept(warning(occurrence.shape, occurrence.shape.getSourceLocation(),
                        formatInclusiveWordsValidationMessage(words, occurrence)));
            }
        }
    }

    private static String formatInclusiveWordsValidationMessage(Collection<String> terms, TextOccurrence occurrence) {
        String words = terms.size() > 1
                ? String.format("words {%s}", terms.stream().collect(Collectors.joining(", ")))
                : String.format("word {%s}", terms.stream().findFirst().get());
        if (occurrence.trait.isPresent()) {
            String keyOrValue = occurrence.isTraitKeyName
                    ? "key"
                    : "value";
            String propertyPathFormatted = formatPropertyPath(occurrence.isTraitKeyName, occurrence.traitPropertyPath);
            if (occurrence.traitPropertyPath.isEmpty()) {
                return String.format("Non-inclusive %s found on `%s` trait %s",
                        words, Trait.getIdiomaticTraitName(occurrence.trait.get()), keyOrValue, propertyPathFormatted);
            } else {
                return String.format("Non-inclusive %s found on `%s` trait %s at {%s}",
                        words, Trait.getIdiomaticTraitName(occurrence.trait.get()), keyOrValue, propertyPathFormatted);
            }
        } else {
            return String.format("Non-inclusive %s on %s shape",
                    words, occurrence.shape.getType().toString());
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
