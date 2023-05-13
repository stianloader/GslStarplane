package de.geolykt.starloader.gslstarplane;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.gradle.api.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GslExtension {

    public enum ModType {
        EXTERNAL,
        INTERNAL;
    }

    @Nullable
    public Object accesswidener;
    @Nullable
    public Path modDirectory;
    @NotNull
    public final List<Object> externalMods = new ArrayList<>();
    @Nullable
    public List<Object> internalMods;
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
    public String getAcessWidenerContents(@NotNull Project project) {
        if (this.accesswidener == null) {
            return null;
        }
        return project.getResources().getText().fromFile(this.accesswidener, StandardCharsets.UTF_8.name()).asString();
    }
}
