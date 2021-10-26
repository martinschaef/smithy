package software.amazon.smithy.model.knowledge;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.ReferencesTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.validators.TraitValueValidator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TextIndex implements KnowledgeIndex {
    private List<TextInstance> textInstanceList;

    public TextIndex(Model model) {
        textInstanceList = new ArrayList<>();
        Set<String> visitedNamespaces = new HashSet<>();
        Node validatePreludeNode = model.getMetadata().get(TraitValueValidator.VALIDATE_PRELUDE);
        boolean validatePrelude = validatePreludeNode != null
                ? validatePreludeNode.expectBooleanNode().getValue()
                : false;

        model.shapes().filter(shape -> validatePrelude || !Prelude.isPreludeShape(shape)).forEach(shape -> {
            visitedNamespaces.add(shape.getId().getNamespace());
            getTextInstances(shape, textInstanceList, model);
        });

        for (String namespace : visitedNamespaces) {
            textInstanceList.add(TextInstance.builder()
                    .locationType(TextInstance.TextLocation.NAMESPACE)
                    .text(namespace)
                    .build());
        }

        //no point in allowing the list to change
        textInstanceList = Collections.unmodifiableList(textInstanceList);
    }

    public static TextIndex of(Model model) {
        return model.getKnowledge(TextIndex.class, TextIndex::new);
    }

    public Collection<TextInstance> getTextInstances() {
        return textInstanceList;
    }

    private static void getTextInstances(Shape shape,
                                           Collection<TextInstance> textInstances,
                                           Model model) {
        TextInstance.Builder builder = TextInstance.builder()
                .locationType(TextInstance.TextLocation.SHAPE)
                .shape(shape);
        if (shape.isMemberShape()) {
            builder.text(((MemberShape) shape).getMemberName());
        } else {
            builder.text(shape.getId().getName());
        }
        textInstances.add(builder.build());

        for (Trait trait : shape.getAllTraits().values()) {
            Shape traitShape = model.expectShape(trait.toShapeId());
            getTextInstancesForAppliedTrait(trait.toNode(), trait, shape, textInstances,
                    new ArrayDeque<>(), model, traitShape);
        }
    }

    private static void getTextInstancesForAppliedTrait(Node node,
                                                          Trait trait,
                                                          Shape parentShape,
                                                          Collection<TextInstance> TextInstances,
                                                          Deque<String> propertyPath,
                                                          Model model,
                                                          Shape currentTraitPropertyShape) {
        if (trait.toShapeId().equals(ReferencesTrait.ID)) {
            //Skip ReferenceTrait because it is referring to other shape names already being checked
        } else if (node.isStringNode()) {
            TextInstances.add(TextInstance.builder()
                    .locationType(TextInstance.TextLocation.APPLIED_TRAIT)
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
                    //This means the "property" key value isn't modeled in the trait's structure/shape definition
                    //and this text instance is unique
                    TextInstances.add(TextInstance.builder()
                            .locationType(TextInstance.TextLocation.APPLIED_TRAIT)
                            .shape(parentShape)
                            .trait(trait)
                            .text(memberEntry.getKey().getValue())
                            .traitPropertyPath(propertyPath)
                            .build());
                }
                getTextInstancesForAppliedTrait(memberEntry.getValue(), trait, parentShape, TextInstances,
                        propertyPath, model, memberTypeShape);
                propertyPath.removeLast();
            });
        } else if (node.isArrayNode()) {
            int index = 0;
            for (Node nodeElement : node.expectArrayNode().getElements()) {
                propertyPath.offerLast("[" + index + "]");
                Shape memberTypeShape = getChildMemberShapeType(null,
                        model, currentTraitPropertyShape);
                getTextInstancesForAppliedTrait(nodeElement, trait, parentShape, TextInstances,
                        propertyPath, model, memberTypeShape);
                propertyPath.removeLast();
                ++index;
            }
        }
    }

    private static Shape getChildMemberShapeType(String memberKey, Model model, Shape fromShape) {
        if (fromShape != null) {
            for (MemberShape member : fromShape.members()) {
                if (member.getMemberName().equals(memberKey)) {
                    return model.getShape(member.getTarget()).get();
                }
            }
        }
        return null;
    }
}
