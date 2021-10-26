package software.amazon.smithy.model.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.ListUtils;

public final class TextInstance {
    public enum TextLocation {
        SHAPE,
        APPLIED_TRAIT,
        NAMESPACE
    };

    private final TextLocation locationType;
    private final String text;
    private final Shape shape;
    private final Trait trait;
    private final List<String> traitPropertyPath;

    private TextInstance(Builder builder) {
        Objects.requireNonNull(builder.locationType, "LocationType must be specified");
        if (builder.locationType != TextLocation.NAMESPACE && builder.shape == null) {
            throw new IllegalStateException("Shape must be specified if locationType is not namespace");
        }
        Objects.requireNonNull(builder.text, "Text must be specified");
        if (builder.locationType == TextLocation.APPLIED_TRAIT) {
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

    public TextLocation getLocationType() {
        return locationType;
    }

    public String getText() {
        return text;
    }

    public Shape getShape() {
        return shape;
    }

    public Trait getTrait() {
        return trait;
    }

    public List<String> getTraitPropertyPath() {
        return traitPropertyPath;
    }

    public static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private TextLocation locationType;
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

        public Builder locationType(TextLocation locationType) {
            this.locationType = locationType;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public TextInstance build() {
            return new TextInstance(this);
        }
    }
}
