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
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.model.validation.ValidatorService;

/**
 * Scans the "full text" of a Smithy model for non-inclusive words
 * and emits validation events for them.
 */

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
public class InclusiveWordsValidator extends AbstractValidator {
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
    public List<ValidationEvent> validate(Model model) {
        List<ValidationEvent> events = new LinkedList<>();
        Consumer<TextOccurrence> consumer = occurrence -> {
            Set<String> nonInclusiveWordInText = NONINCLUSIVE_TERMS.stream()
                    .filter(term -> occurrence.text.toLowerCase().contains(term)).collect(Collectors.toSet());
            if (!nonInclusiveWordInText.isEmpty()) {
                SourceLocation sl = occurrence.trait.isPresent()
                        ? occurrence.trait.get().getSourceLocation()
                        : occurrence.shape.getSourceLocation();
                events.add(warning(occurrence.shape, sl,
                        formatInclusiveWordsValidationMessage(nonInclusiveWordInText, occurrence)));
            }
        };

        FullTextShapeVisitor visitor = new FullTextShapeVisitor(consumer);
        model.shapes().filter(shape -> !Prelude.isPreludeShape(shape)).forEach(shape -> {
            shape.accept(visitor);
        });
        return events;
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
                return String.format("Non-inclusive %s found on `%s` trait %s%n",
                        words, Trait.getIdiomaticTraitName(occurrence.trait.get()), keyOrValue, propertyPathFormatted);
            } else {
                return String.format("Non-inclusive %s found on `%s` trait %s at {%s}%n",
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

    static final class TextOccurrence {
        final String text;                       //the actual text value, or name, with the problem text
        final Shape shape;                       //If the problem is with the Shape itself, it's always the name
        final Optional<Trait> trait;             //If present, the problem text is in the applied trait
        final List<String> traitPropertyPath;    //Gives the property path in the trait value with the problem text
        final boolean isTraitKeyName;            //If set to true, the problem text is the last key in the
                                                 // property path, not the value

        private TextOccurrence(String text, Shape shape, Optional<Trait> trait, List<String> propertyPath,
                               boolean isTraitKeyName) {
            if (propertyPath == null) {
                throw new RuntimeException();
            }
            this.text = text;
            this.shape = shape;
            this.trait = trait;
            this.traitPropertyPath = propertyPath;
            this.isTraitKeyName = isTraitKeyName;
        }

        public static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private String text;
            private Shape shape;
            private Trait trait;
            private List<String> traitPropertyPath = new ArrayList<>();
            private boolean isTraitKeyName;

            private Builder() { }

            public Builder shape(Shape shape) {
                this.shape = shape;
                return this;
            }

            public Builder trait(Trait trait) {
                this.trait = trait;
                return this;
            }

            public Builder traitPropertyPath(List<String> traitPropertyPath) {
                this.traitPropertyPath = traitPropertyPath != null
                    ? new LinkedList(traitPropertyPath)
                    : new LinkedList<>();
                return this;
            }

            public Builder isTraitKeyName(boolean isTraitKeyName) {
                this.isTraitKeyName = isTraitKeyName;
                return this;
            }

            public Builder text(String text) {
                this.text = text;
                return this;
            }

            public TextOccurrence build() {
                if (shape == null) {
                    throw new IllegalStateException("Shape must be specified");
                }
                if (text == null) {
                    throw new IllegalStateException("Text must be specified");
                }

                Optional<Trait> traitArg = trait != null
                        ? Optional.of(trait)
                        : Optional.empty();
                return new TextOccurrence(text, shape, traitArg, traitPropertyPath, isTraitKeyName);
            }
        }
    }

    static class FullTextShapeVisitor extends ShapeVisitor.Default<Void> {
        private final Consumer<TextOccurrence> textConsumer;
        private final Set<String> visited;

        FullTextShapeVisitor(Consumer<TextOccurrence> textConsumer) {
            this.textConsumer = textConsumer;
            this.visited = new HashSet<>();
        }

        @Override
        protected Void getDefault(Shape shape) {
            if (visited.contains(shape.getId().toString())) {
                return null;
            }
            visited.add(shape.getId().toString());
            //first add the name of the current shape, different accessor if it's a member shape
            if (!shape.isMemberShape()) {
                textConsumer.accept(TextOccurrence.builder()
                        .shape(shape)
                        .text(shape.getId().getName())
                                .build());
            } else {
                textConsumer.accept(TextOccurrence.builder()
                                .shape(shape)
                                .text(shape.getId().getMember().get())
                        .build());
            }

            //iterate over the text contained in trait property values
            shape.getAllTraits().entrySet().forEach(traitEntry -> {
                getTextElementsForTrait(traitEntry.getValue().toNode(), traitEntry.getValue(), shape, textConsumer,
                        new Stack<String>());
            });
            //then iterate over all of it's members
            shape.members().forEach(memberShape -> {
                memberShape.accept(this);
            });
            return null;
        }

        private void getTextElementsForTrait(Node node, Trait trait,
                                              Shape parentShape,
                                              Consumer<TextOccurrence> textConsumer,
                                              Stack<String> propertyPath) {
            if (node.isStringNode()) {
                textConsumer.accept(TextOccurrence.builder()
                                .shape(parentShape)
                                .trait(trait)
                                .traitPropertyPath(propertyPath)
                                .text(node.expectStringNode().getValue())
                        .build());
            } else if (node.isObjectNode()) {
                ObjectNode objectNode = node.expectObjectNode();
                objectNode.getMembers().entrySet().forEach(memberEntry -> {
                    String pathPrefix = propertyPath.isEmpty()
                            ? ""
                            : ".";
                    propertyPath.push(pathPrefix + memberEntry.getKey().getValue());
                    //Test the key. TODO: If possible, we do not need to test Trait modeled keys
                    //but within "document" type values in a trait
                    textConsumer.accept(TextOccurrence.builder()
                            .shape(parentShape)
                            .trait(trait)
                            .text(memberEntry.getKey().getValue())
                            .traitPropertyPath(propertyPath)
                            .isTraitKeyName(true)
                            .build());
                    if (memberEntry.getValue().isStringNode()) {
                        textConsumer.accept(TextOccurrence.builder()
                                        .shape(parentShape)
                                        .trait(trait)
                                        .text(memberEntry.getValue().asStringNode().get().getValue())
                                        .traitPropertyPath(propertyPath)
                                .build());
                    } else {
                        getTextElementsForTrait(memberEntry.getValue(), trait, parentShape, textConsumer, propertyPath);
                    }
                    propertyPath.pop();
                });
            } else if (node.isArrayNode()) {
                ArrayNode arrayNode = node.expectArrayNode();
                final int index[] = {0};
                arrayNode.getElements().forEach(nodeElement -> {
                    propertyPath.push("[" + index[0] + "]");
                    getTextElementsForTrait(nodeElement, trait, parentShape, textConsumer, propertyPath);
                    propertyPath.pop();
                    ++index[0];
                });
            }
            //by now it's not a string value to look at, or any further structure to descend so there's nothing to do
        }
    }
}
