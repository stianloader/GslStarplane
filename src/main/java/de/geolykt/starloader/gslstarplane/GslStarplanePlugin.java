package de.geolykt.starloader.gslstarplane;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.WeakHashMap;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

import de.geolykt.starplane.JarStripper;
import de.geolykt.starplane.JarStripper.MavenId;
import de.geolykt.starplane.ObfuscationHandler;
import de.geolykt.starplane.Utils;

public class GslStarplanePlugin implements Plugin<Project> {

    static final WeakHashMap<Project, ObfuscationHandler> OBF_HANDLERS = new WeakHashMap<>();
    static final WeakHashMap<Project, JavaExec> RUN_TASKS = new WeakHashMap<>();

    public void apply(Project project) {
        project.getExtensions().create(GslExtension.class, "starplane", GslExtension.class);
        project.afterEvaluate(GslStarplanePlugin::runDeobf);
        project.getTasks().create("remap", GslRemapTask.class);
        RUN_TASKS.put(project, project.getTasks().create("runMod", JavaExec.class, (task) -> {
            task.dependsOn(project.getTasks().create("deployMods", GslDeployModsTask.class, (arg10001) -> {
                arg10001.dependsOn("jar");
            }));
            SourceSetContainer sourceSets = (SourceSetContainer) project.getProperties().get("sourceSets");
            if (sourceSets == null) {
                throw new IllegalStateException("Unable to find the source set container");
            }
            SourceSet set = sourceSets.getByName("main");
            task.classpath(set.getRuntimeClasspath().minus(set.getOutput()));
            task.classpath(project.file(Utils.getSourceJar(GslLaunchEntrypoint.class).toAbsolutePath()));
            task.getMainClass().set("de.geolykt.starloader.gslstarplane.GslLaunchEntrypoint");
            task.setIgnoreExitValue(true);
            task.doFirst((ignore) -> {
                // resolve data folder
                Path dataFolder = task.getWorkingDir().toPath().resolve("data");
                if (Files.notExists(dataFolder)) {
                    File gameFolder = Utils.getGameDir(Utils.STEAM_GALIMULATOR_APPNAME);
                    Path galimDataFolder;
                    if (gameFolder == null || Files.notExists(galimDataFolder = gameFolder.toPath().resolve("data"))) {
                        task.getLogger().warn("Couldn't locate data folder. You might need to copy the data folder manually in order to be able to run this task");
                    } else {
                        try {
                            Files.createSymbolicLink(dataFolder, galimDataFolder);
                        } catch (IOException e) {
                            task.getLogger().warn("Cannot link data folder. You might need to copy the data folder manually in order to be able to run this task", e);
                        }
                    }
                }
            });
        }));
    }

    private static void runDeobf(Project project) {
        if (OBF_HANDLERS.containsKey(project)) {
            return;
        }
        Path altCache = project.getBuildDir().toPath().resolve("gsl-starplane");
        ObfuscationHandler oHandler = new ObfuscationHandler(altCache, project.getProjectDir().toPath(), project.getResources().getText().fromFile(project.getExtensions().findByType(GslExtension.class).accesswidener, StandardCharsets.UTF_8.name()).asString());
        OBF_HANDLERS.put(project, oHandler);
        Path strippedPath = altCache.resolve("galimulator-stripped.jar");
        try {
            JarStripper stripper = new JarStripper();
            Set<MavenId> deps;
            // We could probably make that step quicker (through caching) but whatever - this works (TM)
            try (InputStream is = Files.newInputStream(oHandler.getTransformedGalimulatorJar())) {
                deps = stripper.getShadedDependencies(is);
            }
            deps.forEach((dep) -> project.getDependencies().add("compileOnlyApi", dep.toGAVNotation()));
            deps.forEach((dep) -> project.getDependencies().add("runtimeOnly", dep.toGAVNotation()));
            if (oHandler.didRefresh || !Files.exists(strippedPath)) {
                stripper.createStrippedJar(oHandler.getTransformedGalimulatorJar(), strippedPath, stripper.aggregate(altCache, deps));
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to generate stripped galimulator jar!", e);
        }
        // FIXME Decompile!
        project.getDependencies().add("compileOnly", project.files(strippedPath));

        JavaExec runTask = RUN_TASKS.get(project);
        if (runTask != null) {
            runTask.classpath(project.file(oHandler.getTransformedGalimulatorJar().resolveSibling("galimulator-remapped-rt.jar")));
            runTask.jvmArgs("-Dgslstarplane.galimulator=" + oHandler.getTransformedGalimulatorJar().toAbsolutePath().resolveSibling("galimulator-remapped-rt.jar").toString());
            Path extensionDir = project.getExtensions().getByType(GslExtension.class).extensionDirectory;
            if (extensionDir == null) {
                extensionDir = runTask.getWorkingDir().toPath().resolve("extensions");
            }
            runTask.jvmArgs("-Dgslstarplane.extensiondir=" + extensionDir.toAbsolutePath().toString());
        }
    }
}
