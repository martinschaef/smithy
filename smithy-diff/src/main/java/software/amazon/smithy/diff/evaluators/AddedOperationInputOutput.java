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

package software.amazon.smithy.diff.evaluators;

import java.util.Collections;
import java.util.List;
import software.amazon.smithy.diff.DiffEvaluator;
import software.amazon.smithy.diff.Differences;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * This validator is now deprecated because operations always default
 * to smithy.api#Unit when they have no input or output.
 */
@Deprecated
public final class AddedOperationInputOutput implements DiffEvaluator {
    @Override
    public List<ValidationEvent> evaluate(Differences differences) {
        return Collections.emptyList();
    }
}
