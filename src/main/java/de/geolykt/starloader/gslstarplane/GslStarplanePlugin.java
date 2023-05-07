package de.geolykt.starloader.gslstarplane;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.UnknownDomainObjectException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.json.JSONArray;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import de.geolykt.starplane.Autodeobf;
import de.geolykt.starplane.JarStripper;
import de.geolykt.starplane.JarStripper.MavenId;
import de.geolykt.starplane.ObfuscationHandler;
import de.geolykt.starplane.Utils;
import de.geolykt.starplane.sourcegen.EnhancedJarSaver;
import de.geolykt.starplane.sourcegen.FernflowerLoggerAdapter;

public class GslStarplanePlugin implements Plugin<Project> {

    public static final String TASK_GROUP = "GslStarplane";
    public static final String GALIM_DEPS_CONFIGURATION_NAME = "galimulatorDependencies";
    public static final String DEV_RUNTIME_CONFIGURATION_NAME = "devRuntime";
    static final WeakHashMap<Project, ObfuscationHandler> OBF_HANDLERS = new WeakHashMap<>();
    static final WeakHashMap<Project, JavaExec> RUN_TASKS = new WeakHashMap<>();

    public void apply(Project project) {
        project.getExtensions().create(GslExtension.class, "starplane", GslExtension.class);
        project.afterEvaluate(GslStarplanePlugin::runDeobf);
        project.getTasks().create("remap", GslRemapTask.class, (task) -> {
            task.setDescription("Remap deobfuscated jars to use obfuscated mappings.");
            task.setGroup(TASK_GROUP);
        });
        project.getConfigurations().register(DEV_RUNTIME_CONFIGURATION_NAME).configure(configuration -> {
            configuration.setVisible(false);
            configuration.setCanBeResolved(true);
            configuration.setDescription("Dependencies included in the development runtime.");
            SourceSetContainer sourceSets = (SourceSetContainer) Objects.requireNonNull(project.getProperties().get("sourceSets"));
            configuration.extendsFrom(project.getConfigurations().getByName(sourceSets.getByName("main").getRuntimeClasspathConfigurationName()));
        });
        project.getTasks().create("deployMods", GslDeployModsTask.class, (task) -> {
            task.setDescription("Deploy mods to the extension directory of the development environment.");
            task.setGroup(TASK_GROUP);
        });
        RUN_TASKS.put(project, project.getTasks().create("runMods", GslRunModsTask.class));
        project.getTasks().create("genEclipseRuns", GslGenEclipseRunsTask.class, (task) -> {
            task.setDescription("Generate eclipse *.launch files");
            task.setGroup(TASK_GROUP);
        });
    }

    private static void runDeobf(Project project) {
        if (OBF_HANDLERS.containsKey(project)) {
            return;
        }
        Path altCache = project.getBuildDir().toPath().resolve("gsl-starplane");

        ObfuscationHandler oHandler = new ObfuscationHandler(altCache, project.getProjectDir().toPath(), project.getResources().getText().fromFile(project.getExtensions().findByType(GslExtension.class).accesswidener, StandardCharsets.UTF_8.name()).asString());
        OBF_HANDLERS.put(project, oHandler);
        resolve(project, oHandler);
        JavaExec runTask = RUN_TASKS.get(project);
        if (runTask != null) {
            runTask.systemProperty("de.geolykt.starloader.launcher.CLILauncher.mainClass", "com.example.Main");
            //  + oHandler.getTransformedGalimulatorJar().toAbsolutePath().resolveSibling("galimulator-remapped-rt.jar").toString()
            Path modsDir = project.getExtensions().getByType(GslExtension.class).modDirectory;
            if (modsDir == null) {
                modsDir = runTask.getWorkingDir().toPath().resolve("mods");
            }
            runTask.systemProperty("de.geolykt.starloader.launcher.IDELauncher.modDirectory", modsDir.toAbsolutePath().toString());
        }
    }

    static String getBootPath(Project p) {
        JSONArray bootPath = new JSONArray();
        try {
            bootPath.put(OBF_HANDLERS.get(p).getTransformedGalimulatorJar().toAbsolutePath().resolveSibling("galimulator-remapped-rt.jar").toUri().toURL().toExternalForm());
            for (File f : p.getConfigurations().getByName(GALIM_DEPS_CONFIGURATION_NAME).resolve()) {
                bootPath.put(f.toURI().toURL().toExternalForm());
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return "-Dde.geolykt.starloader.launcher.IDELauncher.bootURLs=" +  bootPath.toString();
    }

    public static void resolve(Project project, ObfuscationHandler obfHandler) {
        // compileLarge = complete galimulator jar with compile access (intermediary product)
        // compileStripped = stripped galimulator jar with compile access
        // compileStrippedSource = decompiled stripped galimulator jar with compile-time accesss
        // runtimeLarge = complete galimulator jar with runtime access
        Path compileLarge = obfHandler.getTransformedGalimulatorJar();
        Path compileStripped = compileLarge.resolveSibling("galimulator-remapped-stripped-" + Autodeobf.getVersion() + ".jar");
        Path compileStrippedSource = compileLarge.resolveSibling("galimulator-remapped-stripped-" + Autodeobf.getVersion() + "-sources.jar");
        Path runtimeLarge = compileLarge.resolveSibling("galimulator-remapped-rt.jar");

        JarStripper stripper = new JarStripper();
        NamedDomainObjectProvider<Configuration> galimDepsConfig = null;
        try {
            galimDepsConfig = project.getConfigurations().named(GALIM_DEPS_CONFIGURATION_NAME);
        } catch (UnknownDomainObjectException e) {
            galimDepsConfig = project.getConfigurations().register(GALIM_DEPS_CONFIGURATION_NAME);
        }

        Set<MavenId> deps;
        // We could probably make that step quicker (through caching) but whatever - this works (TM)
        try (InputStream is = Files.newInputStream(compileLarge)) {
            deps = stripper.getShadedDependencies(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (MavenId dep : deps) {
            project.getDependencies().add(GALIM_DEPS_CONFIGURATION_NAME, dep.toGAVNotation());
        }

        Set<File> transitiveDeps = galimDepsConfig.get().resolve();

        if (obfHandler.didRefresh || Files.notExists(compileStripped)) {
            try {
                Set<String> removePaths = new HashSet<>();
                removePaths.remove("META-INF/MANIFEST.MF");
                for (File transitiveDep : transitiveDeps) {
                    try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(transitiveDep.toPath()))) {
                        for (ZipEntry entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
                            String name = entry.getName();
                            if (name.codePointAt(0) == '/') {
                                name = name.substring(0);
                            }
                            removePaths.add(name);
                        }
                    }
                }
                stripper.createStrippedJar(compileLarge, compileStripped, removePaths);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        if (obfHandler.didRefresh || Files.notExists(compileStrippedSource)) {
            try {
                decompile(project, runtimeLarge, compileStripped, compileStrippedSource, transitiveDeps);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        project.getRepositories().flatDir((repo) -> {
            repo.dir(compileLarge.getParent());
            repo.setName("generated-galimulator-remapped");
        });

        project.getDependencies().add("compileOnly", ":galimulator-remapped-stripped:" + Autodeobf.getVersion());

        obfHandler.didRefresh = false; // Everything else was reset so we can dare to reset that flag should this method be called multiple times
    }

    private static void decompile(Project project, @NotNull Path runtimeLarge, @NotNull Path compileStripped, @NotNull Path compileStrippedSource, Set<File> transitiveDeps) throws IOException {
        project.getLogger().info("Decompiling galimulator");

        // Time to decompile that stripped jar
        Map<String, Object> args = new HashMap<>();
        args.put(IFernflowerPreferences.INDENT_STRING, "    "); // Default is 3 Spaces, which is nonsense
        args.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1"); // Default is false, which is nonsense
        args.put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "1");
        args.put(IFernflowerPreferences.LOG_LEVEL, "WARN");
        args.put(IFernflowerPreferences.VERIFY_ANONYMOUS_CLASSES, "1");
        args.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1");
        args.put(IFernflowerPreferences.DUMP_CODE_LINES, "1");
        args.put(IFernflowerPreferences.DUMP_ORIGINAL_LINES, "1");
        args.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "0"); // While it is a nice tool to see how good our deobfuscator is, sometimes it isn't that good

        Map<String, int[]> lineMappings = new HashMap<>();
        try (EnhancedJarSaver jarSaver = new EnhancedJarSaver(compileStrippedSource.toFile(), lineMappings)) {
            Fernflower qf = new Fernflower(jarSaver, args, new FernflowerLoggerAdapter(Severity.WARN));
            qf.addSource(compileStripped.toFile());
            for (File transitiveDep : transitiveDeps) {
                qf.addLibrary(transitiveDep);
            }
            qf.decompileContext();
        }

        project.getLogger().info("Galimulator decompiled");
        project.getLogger().info("Replacing line mappings");

        replaceLineNumbers(compileStripped, lineMappings);
        replaceLineNumbers(runtimeLarge, lineMappings);

        project.getLogger().info("Line mappings replaced");
    }

    private static void replaceLineNumbers(@NotNull Path lineReplaceTarget, Map<String, int[]> lineMappings) throws IOException {
        Map<ZipEntry, byte[]> resources = new LinkedHashMap<>();
        Map<String, ClassNode> nameToNode = new HashMap<>();
        Map<String, ClassReader> readers = new HashMap<>();
        SortedSet<ClassNode> nodesSorted = new TreeSet<>((n1, n2) -> n1.name.compareTo(n2.name));

        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(lineReplaceTarget))) {
            for (ZipEntry entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
                if (!entry.getName().endsWith(".class")) {
                    resources.put(entry, Utils.readAllBytes(zipIn));
                    continue;
                }
                ClassNode node = new ClassNode();
                ClassReader reader = new ClassReader(zipIn);
                reader.accept(node, 0);
                readers.put(node.name, reader);
                nameToNode.put(node.name, node);
                nodesSorted.add(node);
            }
        }

        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(lineReplaceTarget), StandardCharsets.UTF_8)) {
            for (Map.Entry<ZipEntry, byte[]> resource : resources.entrySet()) {
                zipOut.putNextEntry(resource.getKey());
                zipOut.write(resource.getValue());
            }
            resources = null; // Free up memory

            for (ClassNode node : nodesSorted) {

                ClassNode outermostClassnode = node;
                outermostNodeFinderLoop:
                while (true) {
                    if (outermostClassnode.outerClass != null) {
                        outermostClassnode = nameToNode.get(outermostClassnode.outerClass);
                        continue;
                    }
                    for (InnerClassNode icn : outermostClassnode.innerClasses) {
                        if (icn.name.equals(outermostClassnode.name) && icn.outerName != null) {
                            outermostClassnode = nameToNode.get(icn.outerName);
                            continue outermostNodeFinderLoop;
                        }
                    }
                    break;
                }

                if (node.sourceFile.equals("SourceFile")) {
                    int startName = outermostClassnode.name.lastIndexOf('/') + 1;
                    int innerSeperator = outermostClassnode.name.indexOf('$');
                    String baseName;
                    if (innerSeperator == -1) {
                        baseName = outermostClassnode.name.substring(startName);
                    } else {
                        baseName = outermostClassnode.name.substring(startName, innerSeperator);
                    }
                    node.sourceFile = baseName + ".java";
                }

                // mapping[i * 2] -> original line number; mapping[i * 2 + 1] -> new line number
                int[] mapping = lineMappings.get(outermostClassnode.name);

                if (mapping != null) {
                    Map<Integer, Integer> lineNumberConversion = new HashMap<>();
                    for (int i = 0; i < mapping.length;) {
                        lineNumberConversion.put(mapping[i++], mapping[i++]);
                    }
                    for (MethodNode method : node.methods) {
                        if (method.instructions == null) {
                            continue;
                        }
                        for (AbstractInsnNode insn : method.instructions) {
                            if (insn instanceof LineNumberNode) {
                                int old = ((LineNumberNode)insn).line;
                                Integer newLineNumber = lineNumberConversion.get(old);
                                if (newLineNumber != null) {
                                    ((LineNumberNode)insn).line = newLineNumber;
                                }
                            }
                        }
                    }
                }

                ClassReader reader = readers.get(node.name);
                ClassWriter writer = new ClassWriter(reader, 0);
                node.accept(writer);
                zipOut.putNextEntry(new ZipEntry(node.name + ".class"));
                zipOut.write(writer.toByteArray());
            }
        }
    }
}
