package de.geolykt.starloader.gslstarplane;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency;

import de.geolykt.starplane.Autodeobf;
import de.geolykt.starplane.ObfuscationHandler;

public class ModularGalimDependency extends AbstractModuleDependency {

    private final String configuration;
    private final Project project;
    private final ObfuscationHandler obfHandler;

    protected ModularGalimDependency(String configuration, Project p, ObfuscationHandler o) {
        super(configuration);
        this.configuration = configuration;
        this.project = p;
        this.obfHandler = o;
    }

    @Override
    public ModuleDependency copy() {
        return new ModularGalimDependency(this.configuration, this.project, this.obfHandler);
    }

    @Override
    public String getGroup() {
        return "snoddasmannen";
    }

    @Override
    public String getName() {
        return "galimulator";
    }

    @Override
    public String getVersion() {
        return Autodeobf.getVersion();
    }

    @Override
    public boolean contentEquals(Dependency dependency) {
        if (dependency instanceof ModularGalimDependency) {
            ModularGalimDependency other = (ModularGalimDependency) dependency;
            return this.configuration.equals(other.configuration)
                    && this.project.equals(other.project)
                    && this.obfHandler.equals(other.obfHandler);
        }
        return false;
    }
}
