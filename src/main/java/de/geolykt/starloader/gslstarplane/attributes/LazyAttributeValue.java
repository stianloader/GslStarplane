package de.geolykt.starloader.gslstarplane.attributes;

import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.attributes.AttributeValue;

public class LazyAttributeValue<T> implements AttributeValue<T> {

    private final Attribute<T> attribute;
    private final AttributeContainer container;

    public LazyAttributeValue(AttributeContainer container, Attribute<T> attr) {
        this.container = container;
        this.attribute = attr;
    }

    @Override
    public boolean isPresent() {
        return this.container.contains(this.attribute);
    }

    @Override
    public T get() {
        T value = this.container.getAttribute(this.attribute);
        if (value == null) {
            throw new IllegalStateException("Attribute " + this.attribute + " is missing or null.");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <S> S coerce(Attribute<S> otherAttribute) {
        return (S) get();
    }
}
