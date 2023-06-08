package de.geolykt.starloader.gcmcstarplane;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.gradle.api.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GcmcExtension {

    public enum ModType {
        EXTERNAL,
        INTERNAL;
    }

    @Nullable
    public Object reversibleAccessSetter;
    @Nullable
    public Object eclipseEEA;
    @Nullable
    public Path modDirectory;
    @NotNull
    public final List<Object> externalMods = new ArrayList<>();
    @Nullable
    public List<Object> internalMods;
    List<BiConsumer<ModType, Object>> updateHooks = new ArrayList<>();

    public int steamAppId;
    public String gameName;
    public String jarPath;
    public String mainClass;
    @NotNull
    public final List<@NotNull String> jarIncludes = new ArrayList<>();

    public void game(int id, String name, String path, @NotNull String... includes) {
        this.steamAppId = id;
        this.gameName = name;
        this.jarPath = path;
        for (String s : includes) {
            this.jarIncludes.add(s);
        }
    }

    public void jarInclude(@NotNull String... includes) {
        for (String s : includes) {
            this.jarIncludes.add(s);
        }
    }

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

    @Nullable
    public String getRASContents(@NotNull Project project) {
        if (this.reversibleAccessSetter == null) {
            return null;
        }
        return project.getResources().getText().fromFile(this.reversibleAccessSetter, StandardCharsets.UTF_8.name()).asString();
    }

    public void withRAS(Object notation) {
        this.reversibleAccessSetter = notation;
    }
}
