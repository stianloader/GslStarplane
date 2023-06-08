package de.geolykt.starloader.gcmcstarplane;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.internal.ConventionTask;
import org.gradle.api.internal.component.UsageContext;
import org.gradle.api.plugins.internal.DefaultAdhocSoftwareComponent;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import de.geolykt.starplane.Utils;

@DisableCachingByDefault(because = "Not worth it")
public class GcmcDeployModsTask extends ConventionTask {

    @NotNull
    private final List<Object> modJars = new ArrayList<>();

    public void from(Object notation) {
        if (notation instanceof Task) {
            super.dependsOn(notation);
        }
        this.modJars.add(notation);
    }

    public void from(Object... notation) {
        for (Object o : notation) {
            this.from(o);
        }
    }

    @NotNull
    @Internal
    public List<@NotNull Path> getModPaths() {
        Set<@NotNull Path> out = new LinkedHashSet<>();
        for (Object modJar : this.modJars) {
            if (modJar instanceof SoftwareComponent) {
                if (!(modJar instanceof DefaultAdhocSoftwareComponent)) {
                    throw new IllegalStateException("Only implementations of SoftwareComponent that are an instance of DefaultAdhocSoftwareComponent can be used as a mod jar.");
                }
                for (UsageContext usageCtx : ((DefaultAdhocSoftwareComponent) modJar).getUsages()) {
                    if (usageCtx == null) {
                        continue; // Better safe than sorry
                    }
                    for (PublishArtifact artifact : usageCtx.getArtifacts()) {
                        if (artifact == null) {
                            continue;
                        }
                        out.add(artifact.getFile().toPath());
                    }
                }
            } else if (modJar instanceof PublishArtifact) {
                out.add(((PublishArtifact) modJar).getFile().toPath());
            } else if (modJar instanceof AbstractArchiveTask) {
                out.add(((AbstractArchiveTask) modJar).getArchiveFile().get().getAsFile().toPath());
            } else if (modJar instanceof Configuration) {
                for (File resolvedEntry : ((Configuration) modJar).resolve()) {
                    out.add(resolvedEntry.toPath());
                }
            } else {
                out.add(super.getProject().file(modJar).toPath());
            }
        }
        return new ArrayList<>(out);
    }

    public static Optional<String> getExtensionName(@NotNull Path in) throws IOException {
        try (InputStream rawIn = Files.newInputStream(in);
                ZipInputStream zipIn = new ZipInputStream(rawIn)) {
            for (ZipEntry entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
                if (!entry.getName().equals("extension.json")) {
                    continue;
                }
                JSONObject extension = new JSONObject(new String(Utils.readAllBytes(zipIn), StandardCharsets.UTF_8));
                return Optional.of(extension.getString("name"));
            }
        }
        return Optional.empty();
    }

    @TaskAction
    void deployMods() {
        Set<String> extensionNames = new HashSet<>();
        List<Path> mods = new ArrayList<>();

        for (Path modPath : this.getModPaths()) {
            // Only add valid mods
            if (Files.notExists(modPath)) {
                continue;
            }
            try {
                Optional<String> name = getExtensionName(modPath);
                if (name.isPresent()) {
                    mods.add(modPath);
                    extensionNames.add(name.get());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Remove any older copies of the mod
        Path modDirectory = super.getProject().getExtensions().getByType(GcmcExtension.class).modDirectory;
        if (modDirectory == null) {
            JavaExec exec = GcmcStarplanePlugin.RUN_TASKS.get(super.getProject());
            if (exec == null) {
                // TODO make this more configurable. This task may have other reasons to exist too!
                throw new IllegalStateException("Unable to resolve the extension directory.");
            }
            modDirectory = exec.getWorkingDir().toPath().resolve("mods");
        }

        if (Files.notExists(modDirectory)) {
            try {
                Files.createDirectories(modDirectory);
            } catch (IOException x) {
            }
        }

        File[] children = modDirectory.toFile().listFiles();
        if (children == null) {
            children = new File[0];
        }
        for (File f : children) {
            if (f.isDirectory() || !f.getName().endsWith(".jar")) {
                continue;
            }
            try {
                Optional<String> name = getExtensionName(f.toPath());
                if (name.isPresent() && extensionNames.contains(name.get())) {
                    f.delete();
                    continue;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        for (Path mod : mods) {
            try {
                Path target = mod.getFileName();
                if (target == null) {
                    target = modDirectory.resolve("extension.jar");
                } else {
                    target = modDirectory.resolve(target);
                }
                Files.move(mod, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
