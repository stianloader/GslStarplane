package de.geolykt.starloader.gslstarplane;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.resources.TextResource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import groovy.lang.Closure;

public class GslExtension {

    public enum ModType {
        EXTERNAL,
        INTERNAL;
    }

    @Nullable
    public Object eclipseEEA;
    @NotNull
    public final List<Object> externalMods = new ArrayList<>();
    @Nullable
    public List<Object> internalMods;
    @Nullable
    public Path modDirectory;
    @Nullable
    public Object reversibleAccessSetter;
    @NotNull
    public final List<Object> softmapMappings = new ArrayList<>();
    List<BiConsumer<ModType, Object>> updateHooks = new ArrayList<>();

    public void externalMod(Object... notations) {
        for (Object o : notations) {
            this.externalMod(o);
        }
    }

    public void externalMod(Object notation) {
        this.externalMods.add(notation);
        for (BiConsumer<ModType, Object> hook : this.updateHooks) {
            hook.accept(ModType.EXTERNAL, notation);
        }
    }

    @Nullable
    public String getRASContents(@NotNull Project project) {
        if (this.reversibleAccessSetter == null) {
            return null;
        }
        return project.getResources().getText().fromFile(this.reversibleAccessSetter, StandardCharsets.UTF_8.name()).asString();
    }

    public void internalMod(Object... notations) {
        for (Object o : notations) {
            this.internalMod(o);
        }
    }

    public void internalMod(Object notation) {
        List<Object> internalMods = this.internalMods;
        if (internalMods == null) {
            this.internalMods = internalMods = new ArrayList<>();
        }
        internalMods.add(notation);
        for (BiConsumer<ModType, Object> hook : this.updateHooks) {
            hook.accept(ModType.INTERNAL, notation);
        }
    }

    public void softmapFile(@NotNull Object notation) {
        if (notation instanceof Configuration
                || notation instanceof CharSequence
                || notation instanceof File
                || notation instanceof Path
                || notation instanceof URI || notation instanceof URL
                || notation instanceof Directory || notation instanceof RegularFile
                || notation instanceof Provider<?>
                || notation instanceof TextResource
                || notation instanceof Closure<?>
                || notation instanceof Callable<?>) {
            this.softmapMappings.add(notation);
        } else if (Objects.isNull(notation)) {
            throw new IllegalArgumentException("Notation may not be null");
        } else {
            throw new IllegalArgumentException("Notation not of supported type. Please consult the gslStarplane README for further details. Notation is an instance of " + notation.getClass().getName());
        }
    }

    public void withRAS(Object notation) {
        this.reversibleAccessSetter = notation;
    }
}
