package de.geolykt.starloader.gslstarplane.attributes;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.internal.attributes.AbstractAttributeContainer;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.NotNull;

public class CustomAttributeContainer extends AbstractAttributeContainer {

    @NotNull
    final Map<Attribute<?>, Supplier<?>> attributes = new HashMap<>();

    @Override
    public ImmutableAttributes asImmutable() {
        return new CustomAttributeContainerImmutable(this.attributes);
    }

    @Override
    public Set<Attribute<?>> keySet() {
        return this.attributes.keySet();
    }

    @Override
    public <T> AttributeContainer attribute(Attribute<T> key, T value) {
        this.attributes.put(key, () -> Objects.requireNonNull(value));
        return this;
    }

    @Override
    public <T> AttributeContainer attributeProvider(Attribute<T> key, Provider<? extends T> provider) {
        if (provider == null) {
            throw new IllegalArgumentException();
        }
        this.attributes.put(key, provider::get);
        return this;
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
    public boolean equals(Object o) {
        if (o instanceof AttributeContainer) {
            AttributeContainer other = (AttributeContainer) o;
            if (!this.keySet().equals(other.keySet())) {
                return false;
            }
            for (Attribute<?> attr : this.keySet()) {
                if (!Objects.equals(this.getAttribute(attr), other.getAttribute(attr))) {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("null")
    @Override
    public int hashCode() {
        int x = 0;
        // XORing due to order issues
        for (Map.Entry<Attribute<?>, Supplier<?>> entry : this.attributes.entrySet()) {
            x ^= entry.getKey().hashCode() ^ entry.getValue().get().hashCode();
        }
        return x;
    }
}

