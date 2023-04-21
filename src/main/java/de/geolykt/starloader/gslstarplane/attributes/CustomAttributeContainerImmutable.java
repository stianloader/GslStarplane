package de.geolykt.starloader.gslstarplane.attributes;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.attributes.AttributeValue;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.provider.Provider;
import org.gradle.internal.impldep.com.google.common.collect.ImmutableSet;

public class CustomAttributeContainerImmutable implements ImmutableAttributes {
    private final Map<Attribute<?>, Supplier<?>> attributes;

    public CustomAttributeContainerImmutable(Map<Attribute<?>, Supplier<?>> attributes) {
        this.attributes = new HashMap<>(attributes);
    }

    @Override
    public ImmutableAttributes asImmutable() {
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<Attribute<?>, ?> asMap() {
        @SuppressWarnings("rawtypes") // Doesn't want to compile otherwise
        Map map = new HashMap<>(this.attributes.size());
        this.attributes.forEach((attr, supplier) -> {
            map.put(attr, supplier.get());
        });
        return map;
    }

    @Override
    public <T> AttributeContainer attribute(Attribute<T> key, T value) {
        throw new UnsupportedOperationException("Immutable");
    }

    @Override
    public <T> AttributeContainer attributeProvider(Attribute<T> key, Provider<? extends T> provider) {
        throw new UnsupportedOperationException("Immutable");
    }

    @SuppressWarnings({ "unchecked", "null" })
    @Override
    public <T> T getAttribute(Attribute<T> key) {
        Supplier<?> supplier = this.attributes.get(key);
        if (supplier == null) {
            return null;
        }
        return (T) supplier.get();
    }

    @Override
    public boolean isEmpty() {
        return this.attributes.isEmpty();
    }

    @Override
    public boolean contains(Attribute<?> key) {
        return this.attributes.containsKey(key);
    }

    @Override
    public AttributeContainer getAttributes() {
        return this;
    }

    @Override
    public <T> AttributeValue<T> findEntry(Attribute<T> key) {
        return new LazyAttributeValue<>(this, key);
    }

    @Override
    public AttributeValue<?> findEntry(String key) {
        return new LazyAttributeValueString<>(this, key);
    }

    @Override
    public ImmutableSet<Attribute<?>> keySet() {
        return ImmutableSet.copyOf(this.attributes.keySet());
    }
}
