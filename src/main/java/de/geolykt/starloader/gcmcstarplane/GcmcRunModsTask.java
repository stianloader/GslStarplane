package de.geolykt.starloader.gcmcstarplane;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.gradle.api.Task;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.internal.component.UsageContext;
import org.gradle.api.plugins.internal.DefaultAdhocSoftwareComponent;
import org.gradle.api.tasks.JavaExec;
import org.gradle.jvm.tasks.Jar;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;

import de.geolykt.starplane.Utils;

@DisableCachingByDefault(because = "Does not make sense")
public class GcmcRunModsTask extends JavaExec {
    @NotNull
    private final List<Object> extraMods = new ArrayList<>();

    private final GcmcExtension extension = getProject().getExtensions().getByType(GcmcExtension.class);

    public GcmcRunModsTask() {
        super.dependsOn("deployMods");
        super.dependsOn("jar");
        super.setDescription("Run the development environment.");
        super.setGroup(GcmcStarplanePlugin.TASK_GROUP);
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
        super.jvmArgs(GcmcStarplanePlugin.getBootPath(super.getProject()));
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
        super.classpath(super.getProject().getConfigurations().getByName(GcmcStarplanePlugin.DEV_RUNTIME_CONFIGURATION_NAME).resolve());
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

    private void linkNecesseFiles() {
        Path cacheDir = GcmcStarplanePlugin.OBF_HANDLERS.get(super.getProject()).getTransformedGalimulatorJar().toAbsolutePath().getParent();
        if (cacheDir == null) {
            throw new NullPointerException("cacheDir is null for whatever reason");
        }
        try {
            cacheDir = cacheDir.toRealPath();
        } catch (IOException e) {
            cacheDir = cacheDir.toAbsolutePath();
        }
        Path localeDir = cacheDir.resolve("locale");
        Path resourcesFile = cacheDir.resolve("res.data");

        File gameFileDir = Utils.getGameDir(Utils.STEAM_NECESSE_APPNAME);
        if (gameFileDir == null) {
            super.getLogger().warn("Cannot find game folder for game '" + Utils.STEAM_NECESSE_APPNAME + "' with appid " + Utils.STEAM_NECESSE_APPID + ".");
            return;
        }
        Path gameFolder = gameFileDir.toPath();

        if (Files.notExists(localeDir)) {
            Path internalLocaleFolder;
            if (Files.notExists(internalLocaleFolder = gameFolder.resolve("locale"))) {
                super.getLogger().warn("Couldn't locate folder 'locale'. You might need to copy the 'locale' folder manually in order to be able to run this task");
            } else {
                try {
                    Files.createSymbolicLink(localeDir, internalLocaleFolder);
                } catch (IOException e) {
                    super.getLogger().warn("Cannot link folder 'locale'. You might need to copy the 'locale' folder manually in order to be able to run this task", e);
                }
            }
        }
        if (Files.notExists(resourcesFile)) {
            Path internalResourceFile;
            if (Files.notExists(internalResourceFile = gameFolder.resolve("res.data"))) {
                super.getLogger().warn("Couldn't locate file 'res.data'. You might need to copy the file 'res.data' manually in order to be able to run this task");
            } else {
                try {
                    Files.createSymbolicLink(resourcesFile, internalResourceFile);
                } catch (IOException e) {
                    super.getLogger().warn("Cannot link file 'res.data'. You might need to copy the file 'res.data' manually in order to be able to run this task", e);
                }
            }
        }
    }

    @Override
    public void exec() {
        this.resolveClasspath();
        if (this.extension.steamAppId == Utils.STEAM_GALIMULATOR_APPID) {
            this.linkDataFolder();
        } else if (this.extension.steamAppId == Utils.STEAM_NECESSE_APPID) {
            this.linkNecesseFiles();
        }
        this.setupLauncherProperties();
        super.exec();
    }
}
