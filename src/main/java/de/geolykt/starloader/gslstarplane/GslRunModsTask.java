package de.geolykt.starloader.gslstarplane;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.gradle.api.Task;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.internal.component.UsageContext;
import org.gradle.api.tasks.JavaExec;
import org.gradle.jvm.tasks.Jar;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;

import de.geolykt.starplane.Utils;

@DisableCachingByDefault(because = "Does not make sense")
public abstract class GslRunModsTask extends JavaExec {
    @NotNull
    private final List<Object> extraMods = new ArrayList<>();

    public GslRunModsTask() {
        super.dependsOn("deployMods");
        super.dependsOn("jar");
        super.setDescription("Run the development environment.");
        super.setGroup(GslStarplanePlugin.TASK_GROUP);
        super.getMainClass().set("de.geolykt.starloader.launcher.IDELauncher");
        super.setIgnoreExitValue(true);
        super.systemProperty("de.geolykt.starloader.launcher.IDELauncher.inlineStarplaneAnnotations", true);
    }

    public void from(Object notation) {
        if (notation instanceof Task) {
            super.dependsOn(notation);
        }
        this.extraMods.add(notation);
    }

    public void from(Object... notation) {
        for (Object o : notation) {
            this.from(o);
        }
    }

    @NotNull
    private List<@NotNull Path> getModPaths() {
        Set<@NotNull Path> out = new LinkedHashSet<>();
        for (Object modJar : this.extraMods) {
            getLogger().info("Looking at " + modJar);
            if (modJar instanceof SoftwareComponent) {
                for (UsageContext usageCtx : GradleInteropUtil.getUsageContexts((SoftwareComponent) modJar)) {
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
            } else if (modJar instanceof Jar) {
                out.add(((Jar) modJar).getArchiveFile().get().getAsFile().toPath());
            } else {
                out.add(super.getProject().file(modJar).toPath());
            }
        }
        getLogger().info("Potential mods path: " + out);
        return new ArrayList<>(out);
    }

    private void setupLauncherProperties() {
        super.jvmArgs(GslStarplanePlugin.getBootPath(super.getProject()));
        JSONArray modURLs = new JSONArray();
        for (Path p : getModPaths()) {
            JSONArray mod = new JSONArray();
            try {
                mod.put(p.toUri().toURL().toExternalForm());
            } catch (MalformedURLException e) {
                throw new UncheckedIOException(e);
            }
            modURLs.put(mod);
        }
        super.systemProperty("de.geolykt.starloader.launcher.IDELauncher.modURLs", modURLs.toString());
    }

    private void resolveClasspath() {
        super.classpath(super.getProject().getConfigurations().getByName(GslStarplanePlugin.DEV_RUNTIME_CONFIGURATION_NAME).resolve());
    }

    private void linkDataFolder() {
        Path dataFolder = super.getWorkingDir().toPath().resolve("data");
        if (Files.notExists(dataFolder)) {
            File gameFolder = Utils.getGameDir(Utils.STEAM_GALIMULATOR_APPNAME);
            Path galimDataFolder;
            if (gameFolder == null || Files.notExists(galimDataFolder = gameFolder.toPath().resolve("data"))) {
                super.getLogger().warn("Couldn't locate data folder. You might need to copy the data folder manually in order to be able to run this task");
            } else {
                try {
                    Files.createSymbolicLink(dataFolder, galimDataFolder);
                } catch (IOException e) {
                    super.getLogger().warn("Cannot link data folder. You might need to copy the data folder manually in order to be able to run this task", e);
                }
            }
        }
    }

    @Override
    public void exec() {
        this.resolveClasspath();
        this.linkDataFolder();
        this.setupLauncherProperties();
        super.exec();
    }
}
