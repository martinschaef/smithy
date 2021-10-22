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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ReferencesTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.model.validation.validators.TraitValueValidator;
import software.amazon.smithy.utils.ListUtils;

/**
 * <p>Base class for any validator that wants to perform a full text search on a Model.
 *
 * <p>Does a full scan of the model and gathers all text along with location data
 * and uses a generic function to decide what to do with each text occurrence. The
 * full text search traversal has no knowledge of what it is looking for and descends
 * fully into the structure of all traits.
 *
 * <p>Prelude shape definitions are not examined, however all values
 */
abstract class AbstractModelTextValidator extends AbstractValidator {
    private static final Map<Model, List<TextOccurrence>> MODEL_TO_TEXT_MAP = new ConcurrentHashMap<>();

    /**
     * Sub-classes must implement this method to perform the following:
     *   1) Decide if the text occurrence is at a relevant location to validate.
     *   2) Analyze the text for whatever validation event it may or may not publish.
     *   3) Produce a validation event, if necessary, and push it to the ValidationEvent consumer
     *
     * @param occurrence text occurrence found in the body of the model
     * @param validationEventConsumer consumer to push ValidationEvents into
     */
    protected abstract void getValidationEvents(TextOccurrence occurrence,
                                                Consumer<ValidationEvent> validationEventConsumer);

    /**
     * Runs a full text scan on a given model and stores the resulting TextOccurrences objects.
     *
     * Namespaces are checked against a global set per model.
     *
     * @param model Model to validate.
     * @return a list of ValidationEvents found by the implementer of getValidationEvents per the
     *          TextOccurrences provided by this traversal.
     */
    @Override
    public List<ValidationEvent> validate(Model model) {
        Set<String> visitedNamespaces = new HashSet<>();
        Node validatePreludeNode = model.getMetadata().get(TraitValueValidator.VALIDATE_PRELUDE);
        boolean validatePrelude = validatePreludeNode != null
                ? validatePreludeNode.expectBooleanNode().getValue()
                : false;
        List<TextOccurrence> textOccurrences = MODEL_TO_TEXT_MAP.computeIfAbsent(model, keyModel -> {
            List<TextOccurrence> texts = new ArrayList<>();
            model.shapes().filter(shape -> validatePrelude || !Prelude.isPreludeShape(shape)).forEach(shape -> {
                visitedNamespaces.add(shape.getId().getNamespace());
                getTextOccurrences(shape, texts, model);
            });
            return texts;
        });

        for (String namespace : visitedNamespaces) {
            textOccurrences.add(TextOccurrence.builder()
                    .locationType(TextLocationType.NAMESPACE)
                    .text(namespace)
                    .build());
        }

        List<ValidationEvent> validationEvents = new ArrayList<>();
        for (TextOccurrence text : textOccurrences) {
            getValidationEvents(text, validationEvent -> {
                validationEvents.add(validationEvent);
            });
        }
        return validationEvents;
    }

    private static void getTextOccurrences(Shape shape,
                                           Collection<TextOccurrence> textOccurrences,
                                           Model model) {
        if (shape.isMemberShape()) {
            textOccurrences.add(TextOccurrence.builder()
                    .locationType(TextLocationType.SHAPE)
                    .shape(shape)
                    .text(((MemberShape) shape).getMemberName())
                    .build());
        } else {
            textOccurrences.add(TextOccurrence.builder()
                    .locationType(TextLocationType.SHAPE)
                    .shape(shape)
                    .text(shape.getId().getName())
                    .build());
        }

        for (Trait trait : shape.getAllTraits().values()) {
            Shape traitShape = model.expectShape(trait.toShapeId());
            getTextOccurrencesForAppliedTrait(trait.toNode(), trait, shape, textOccurrences,
                    new ArrayDeque<>(), model, traitShape);
        }
    }

    private static void getTextOccurrencesForAppliedTrait(Node node,
                                                          Trait trait,
                                                          Shape parentShape,
                                                          Collection<TextOccurrence> textOccurrences,
                                                          Deque<String> propertyPath,
                                                          Model model,
                                                          Shape currentTraitPropertyShape) {
        if (trait.toShapeId().equals(ReferencesTrait.ID)) {
            //Skip ReferenceTrait because it is referring to other shape names already being checked
        } else if (node.isStringNode()) {
            textOccurrences.add(TextOccurrence.builder()
                    .locationType(TextLocationType.TRAIT_VALUE)
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
                propertyPath.offerLast(pathPrefix + memberEntry.getKey().getValue());
                Shape memberTypeShape = getChildMemberShapeType(memberEntry.getKey().getValue(),
                        model, currentTraitPropertyShape);
                if (memberTypeShape == null) {
                    //This means the trait key value isn't modeled in the trait's structure/shape definition
                    //and thus this occurrence is unique and not checked anywhere else.
                    textOccurrences.add(TextOccurrence.builder()
                            .locationType(TextLocationType.TRAIT_KEY)
                            .shape(parentShape)
                            .trait(trait)
                            .text(memberEntry.getKey().getValue())
                            .traitPropertyPath(propertyPath)
                            .build());
                }
                getTextOccurrencesForAppliedTrait(memberEntry.getValue(), trait, parentShape, textOccurrences,
                        propertyPath, model, memberTypeShape);
                propertyPath.removeLast();
            });
        } else if (node.isArrayNode()) {
            int index = 0;
            for (Node nodeElement : node.expectArrayNode().getElements()) {
                propertyPath.offerLast("[" + index + "]");
                Shape memberTypeShape = getChildMemberShapeType(null,
                        model, currentTraitPropertyShape);
                getTextOccurrencesForAppliedTrait(nodeElement, trait, parentShape, textOccurrences,
                        propertyPath, model, memberTypeShape);
                propertyPath.removeLast();
                ++index;
            }
        }
    }

    private static Shape getChildMemberShapeType(String memberKey, Model model, Shape fromShape) {
        if (fromShape != null) {
            Shape childShape = null;
            if (fromShape instanceof StructureShape) {
                StructureShape structureShape = (StructureShape) fromShape;
                childShape = model.getShape(structureShape.getMember(memberKey).get().getTarget()).get();
            } else if (fromShape instanceof CollectionShape) {
                CollectionShape collectionShape = (CollectionShape) fromShape;
                childShape = model.getShape(collectionShape.getMember().getTarget()).get();
            }
            return childShape;
        }
        return null;
    }

    protected enum TextLocationType {
        SHAPE,
        TRAIT_VALUE,
        TRAIT_KEY,
        NAMESPACE
    }

    protected static final class TextOccurrence {
        final TextLocationType locationType;
        final String text;
        final Shape shape;
        final Trait trait;
        final List<String> traitPropertyPath;

        private TextOccurrence(Builder builder) {
            Objects.requireNonNull(builder.locationType, "LocationType must be specified");
            if (builder.locationType != TextLocationType.NAMESPACE && builder.shape == null) {
                throw new IllegalStateException("Shape must be specified if locationType is not namespace");
            }
            Objects.requireNonNull(builder.text, "Text must be specified");
            if (builder.locationType == TextLocationType.TRAIT_KEY
                    || builder.locationType == TextLocationType.TRAIT_VALUE) {
                if (builder.trait == null) {
                    throw new IllegalStateException("Trait must be specified for locationType="
                            + builder.locationType.name());
                } else if (builder.traitPropertyPath == null) {
                    throw new IllegalStateException("PropertyPath must be specified for locationType="
                            + builder.locationType.name());
                }
            }

            this.locationType = builder.locationType;
            this.text = builder.text;
            this.shape = builder.shape;
            this.trait = builder.trait;
            this.traitPropertyPath = builder.traitPropertyPath;
        }

        public static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private TextLocationType locationType;
            private String text;
            private Shape shape;
            private Trait trait;
            private List<String> traitPropertyPath = new ArrayList<>();

            private Builder() { }

            public Builder shape(Shape shape) {
                this.shape = shape;
                return this;
            }

            public Builder trait(Trait trait) {
                this.trait = trait;
                return this;
            }

            public Builder traitPropertyPath(Deque<String> traitPropertyPath) {
                this.traitPropertyPath = traitPropertyPath != null
                    ? traitPropertyPath.stream().collect(ListUtils.toUnmodifiableList())
                    : Collections.emptyList();
                return this;
            }

            public Builder locationType(TextLocationType locationType) {
                this.locationType = locationType;
                return this;
            }

            public Builder text(String text) {
                this.text = text;
                return this;
            }

            public TextOccurrence build() {
                return new TextOccurrence(this);
            }
        }
    }
}
