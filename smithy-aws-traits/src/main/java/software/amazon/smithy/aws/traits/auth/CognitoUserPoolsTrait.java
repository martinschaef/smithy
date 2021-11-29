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

package software.amazon.smithy.aws.traits.auth;

import java.util.List;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.AbstractTrait;
import software.amazon.smithy.model.traits.AbstractTraitBuilder;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.BuilderRef;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Configures an Amazon Cognito User Pools auth scheme.
 */
public final class CognitoUserPoolsTrait extends AbstractTrait implements ToSmithyBuilder<CognitoUserPoolsTrait> {

    public static final ShapeId ID = ShapeId.from("aws.auth#cognitoUserPools");
    private static final String PROVIDER_ARNS = "providerArns";

    private final List<String> providerArns;

    private CognitoUserPoolsTrait(Builder builder) {
        super(ID, builder.getSourceLocation());
        this.providerArns = builder.providerArns.copy();
    }

    public static final class Provider extends AbstractTrait.Provider {
        public Provider() {
            super(ID);
        }

        @Override
        public Trait createTrait(ShapeId target, Node value) {
            ObjectNode objectNode = value.expectObjectNode();
            return builder()
                    .sourceLocation(value)
                    .providerArns(objectNode.expectArrayMember(PROVIDER_ARNS).getElementsAs(StringNode::getValue))
                    .build();
        }
    }

    /**
     * @return Creates a builder used to build a {@link CognitoUserPoolsTrait}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the list of provider ARNs.
     *
     * @return Returns the ARNs.
     */
    public List<String> getProviderArns() {
        return providerArns;
    }

    @Override
    public Builder toBuilder() {
        return builder().sourceLocation(getSourceLocation()).providerArns(providerArns);
    }

    @Override
    protected Node createNode() {
        return Node.objectNodeBuilder()
                .sourceLocation(getSourceLocation())
                .withMember(PROVIDER_ARNS, providerArns.stream().map(Node::from).collect(ArrayNode.collect()))
                .build();
    }

    /** Builder for {@link CognitoUserPoolsTrait}. */
    public static final class Builder extends AbstractTraitBuilder<CognitoUserPoolsTrait, Builder> {
        private final BuilderRef<List<String>> providerArns = BuilderRef.forList();

        private Builder() {}

        @Override
        public CognitoUserPoolsTrait build() {
            return new CognitoUserPoolsTrait(this);
        }

        /**
         * Sets the provider ARNs.
         *
         * @param providerArns ARNS to set.
         * @return Returns the builder.
         */
        public Builder providerArns(List<String> providerArns) {
            clearProviderArns();
            this.providerArns.get().addAll(providerArns);
            return this;
        }

        /**
         * Adds a provider ARN.
         *
         * @param arn ARN to add.
         * @return Returns the builder.
         */
        public Builder addProviderArn(String arn) {
            providerArns.get().add(arn);
            return this;
        }

        /**
         * Clears all provider ARNs from the builder.
         *
         * @return Returns the builder.
         */
        public Builder clearProviderArns() {
            providerArns.clear();
            return this;
        }
    }
}
