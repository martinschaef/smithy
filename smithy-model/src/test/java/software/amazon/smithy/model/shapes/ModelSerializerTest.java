/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.model.shapes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodePointer;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.SensitiveTrait;
import software.amazon.smithy.model.traits.synthetic.OriginalShapeIdTrait;

public class ModelSerializerTest {
    @Test
    public void serializesModels() {
        Model model = Model.assembler()
                .addImport(getClass().getResource("test-model.json"))
                .assemble()
                .unwrap();
        ModelSerializer serializer = ModelSerializer.builder().build();
        ObjectNode serialized = serializer.serialize(model);
        String serializedString = Node.prettyPrintJson(serialized);

        Model other = Model.assembler()
                .addUnparsedModel("N/A", serializedString)
                .assemble()
                .unwrap();

        String serializedString2 = Node.prettyPrintJson(serializer.serialize(other));
        assertThat(serialized.expectMember("smithy").expectStringNode(), equalTo(Node.from(Model.MODEL_VERSION)));
        assertThat(serializedString, equalTo(serializedString2));
        assertThat(model, equalTo(other));
    }

    @Test
    public void filtersMetadata() {
        ModelSerializer serializer = ModelSerializer.builder()
                .metadataFilter(k -> k.equals("foo"))
                .build();
        Model model = Model.builder()
                .putMetadataProperty("foo", Node.from("baz"))
                .putMetadataProperty("bar", Node.from("qux"))
                .build();
        ObjectNode result = serializer.serialize(model);

        assertThat(result.getMember("metadata"), not(Optional.empty()));
        assertThat(result.getMember("metadata").get().expectObjectNode().getMember("foo"),
                   equalTo(Optional.of(Node.from("baz"))));
        assertThat(result.getMember("metadata").get().expectObjectNode().getMember("bar"), is(Optional.empty()));
    }

    @Test
    public void filtersShapes() {
        ModelSerializer serializer = ModelSerializer.builder()
                .shapeFilter(shape -> shape.getId().getName().equals("foo"))
                .build();
        Model model = Model.builder()
                .addShape(StringShape.builder().id("ns.foo#foo").build())
                .addShape(StringShape.builder().id("ns.foo#baz").build())
                .build();
        ObjectNode result = serializer.serialize(model);

        ObjectNode shapes = result.expectObjectMember("shapes");
        assertThat(shapes.getMember("ns.foo#foo"), not(Optional.empty()));
        assertThat(shapes.getMember("ns.foo#baz"), is(Optional.empty()));
        assertThat(result.getMember("ns.foo#metadata"), is(Optional.empty()));
    }

    @Test
    public void canFilterTraits() {
        Shape shape = StringShape.builder()
                .id("ns.foo#baz")
                .addTrait(new SensitiveTrait())
                .addTrait(new DocumentationTrait("docs", SourceLocation.NONE))
                .build();
        Model model = Model.assembler().addShape(shape).assemble().unwrap();
        ModelSerializer serializer = ModelSerializer.builder()
                .traitFilter(trait -> trait.toShapeId().toString().equals("smithy.api#documentation"))
                .build();

        ObjectNode obj = serializer.serialize(model)
                .expectObjectMember("shapes")
                .expectObjectMember("ns.foo#baz");
        obj.expectStringMember("type");
        ObjectNode traits = obj.expectObjectMember("traits");
        assertThat(traits.expectStringMember("smithy.api#documentation"), equalTo(Node.from("docs")));
        assertThat(traits.getMember("smithy.api#sensitive"), is(Optional.empty()));
    }

    @Test
    public void serializesAliasedPreludeTraitsUsingFullyQualifiedFormWhenNecessary() {
        Model model = Model.assembler()
                .addImport(getClass().getResource("prelude-trait-alias.smithy"))
                .assemble()
                .unwrap();
        ModelSerializer serializer = ModelSerializer.builder().build();
        ObjectNode serialized = serializer.serialize(model);
        String result = Node.prettyPrintJson(serialized);

        // Make sure that we can serialize and deserialize the original model.
        Model roundTrip = Model.assembler()
                .addUnparsedModel("foo.json", result)
                .assemble()
                .unwrap();

        assertThat(model, equalTo(roundTrip));
        assertThat(result, containsString("\"ns.foo#sensitive\""));
        assertThat(result, containsString("\"smithy.api#sensitive\""));
        assertThat(result, containsString("\"smithy.api#deprecated\""));
    }

    @Test
    public void doesNotSerializePreludeTraitsOrShapes() {
        Model model = Model.assembler()
                .addImport(getClass().getResource("test-model.json"))
                .assemble()
                .unwrap();
        ModelSerializer serializer = ModelSerializer.builder().build();
        ObjectNode serialized = serializer.serialize(model);

        ObjectNode shapes = serialized.expectObjectMember("shapes");
        shapes.getMembers().forEach((key, value) -> {
            assertThat(key.getValue(), not(startsWith("smithy.api#")));
        });
    }

    @Test
    public void allowsDisablingPreludeFilter() {
        Model model = Model.assembler().assemble().unwrap();
        ModelSerializer serializer = ModelSerializer.builder().includePrelude(true).build();
        ObjectNode serialized = serializer.serialize(model);

        ObjectNode shapes = serialized.expectObjectMember("shapes");
        assertTrue(shapes.getMembers().size() > 1);
        shapes.getMembers().forEach((key, value) -> {
            assertThat(key.getValue(), startsWith("smithy.api#"));
        });
    }

    @Test
    public void doesNotSerializeEmptyServiceVersions() {
        ServiceShape service = ServiceShape.builder()
                .id("com.foo#Example")
                .build();
        Model model = Model.builder().addShape(service).build();
        ModelSerializer serializer = ModelSerializer.builder().build();
        ObjectNode result = serializer.serialize(model);

        assertThat(NodePointer.parse("/shapes/com.foo#Example")
                           .getValue(result)
                           .expectObjectNode()
                           .getStringMap(),
                   not(hasKey("version")));
    }

    @Test
    public void transientTraitsAreNotSerialized() {
        ShapeId originalId = ShapeId.from("com.foo.nested#Str");
        StringShape stringShape = StringShape.builder()
                .id("com.foo#Str")
                .addTrait(new OriginalShapeIdTrait(originalId))
                .build();
        Model model = Model.builder()
                .addShape(stringShape)
                .build();

        ModelSerializer serializer = ModelSerializer.builder().build();
        ObjectNode result = serializer.serialize(model);

        assertThat(NodePointer.parse("/shapes/com.foo#Str/traits")
                           .getValue(result)
                           .expectObjectNode()
                           .getStringMap(),
                   not(hasKey(OriginalShapeIdTrait.ID.toShapeId())));
    }
}
