package de.geolykt.starloader.gslstarplane;

import org.gradle.api.Action;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.HasConfigurableAttributes;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.jetbrains.annotations.NotNull;

import de.geolykt.starloader.gslstarplane.attributes.CustomAttributeContainer;

public class EnhancedFileDependency extends DefaultSelfResolvingDependency implements HasConfigurableAttributes<EnhancedFileDependency> {

    @NotNull
    private final ModuleComponentIdentifier identifier;
    @NotNull
    private final AttributeContainer attributes = new CustomAttributeContainer();

    public EnhancedFileDependency(@NotNull ModuleComponentIdentifier targetComponentId, FileCollection source) {
        super(targetComponentId, (FileCollectionInternal) source);
        this.identifier = targetComponentId;
    }

    @Override
    public String getGroup() {
        return this.identifier.getGroup();
    }

    @Override
    public String getName() {
        return this.identifier.getModule();
    }

    @Override
    public String getVersion() {
        return this.identifier.getVersion();
    }

    @Override
    public DefaultSelfResolvingDependency copy() {
        return new EnhancedFileDependency(this.identifier, super.getFiles());
    }

    @Override
    public AttributeContainer getAttributes() {
        return this.attributes;
    }

    @Override
    public EnhancedFileDependency attributes(Action<? super AttributeContainer> action) {
        action.execute(this.attributes);
        return this;
    }
}
