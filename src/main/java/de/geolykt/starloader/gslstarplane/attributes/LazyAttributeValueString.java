package de.geolykt.starloader.gslstarplane.attributes;

import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.attributes.AttributeValue;

public class LazyAttributeValueString<T> implements AttributeValue<T> {

    private final String attribute;
    private final AttributeContainer container;

    public LazyAttributeValueString(AttributeContainer container, String attr) {
        this.container = container;
        this.attribute = attr;
    }

    @Override
    public boolean isPresent() {
        for (Attribute<?> attr : this.container.keySet()) {
            if (attr.getName().equals(this.attribute)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T get() {
        for (Attribute<?> attr : this.container.keySet()) {
            if (attr.getName().equals(this.attribute)) {
                return (T) this.container.getAttribute(attr);
            }
        }
        throw new IllegalStateException("Attribute \"" + this.attribute + "\" is missing.");
    }

    @SuppressWarnings("unchecked")
    @Override
    public <S> S coerce(Attribute<S> otherAttribute) {
        return (S) get();
    }
}
