package de.geolykt.starloader.gslstarplane;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.ConventionTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

@DisableCachingByDefault(because = "No outputs")
public abstract class GslVerifyRemapperJarTask extends ConventionTask {

    private static enum InheritanceVariant {
        PROVIDED,
        REQUIRE;
    }

    private static class MethodId {
        private final String descriptor;
        private final String name;

        public MethodId(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof MethodId) {
                return ((MethodId) obj).name.equals(this.name)
                        && ((MethodId) obj).descriptor.equals(this.descriptor);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.name, this.descriptor);
        }

        @Override
        public String toString() {
            return "'" + this.name + this.descriptor + "'";
        }
    }

    private static class MethodNamespace {
        private final boolean isAbstract;
        private final Map<MethodId, InheritanceVariant> methods;
        private final List<String> namespaceInterfaces;
        private final String namespaceName;
        private final String namespaceSuper;

        public MethodNamespace(String name, String superName, List<String> itf, Map<MethodId, InheritanceVariant> methods, boolean isAbstract) {
            this.namespaceName = name;
            this.namespaceSuper = superName;
            this.namespaceInterfaces = itf;
            this.methods = methods;
            this.isAbstract = isAbstract;
        }
    }

    private static void pushJarContents(@NotNull Path p, Map<String, MethodNamespace> out) throws IOException {
        try (InputStream is = Files.newInputStream(p);
                ZipInputStream zipIn = new ZipInputStream(is, StandardCharsets.UTF_8)) {
            for (ZipEntry zipE = zipIn.getNextEntry(); zipE != null; zipE = zipIn.getNextEntry()) {
                if (!zipE.getName().endsWith(".class")) {
                    continue;
                }

                if (zipE.getName().contains("META-INF/versions/")) {
                    continue;
                }

                ClassReader reader = new ClassReader(zipIn);
                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                Map<MethodId, InheritanceVariant> methods = new HashMap<>();

                for (MethodNode method : node.methods) {
                    methods.put(new MethodId(method.name, method.desc), (method.access & Opcodes.ACC_ABSTRACT) == 0 ? InheritanceVariant.PROVIDED : InheritanceVariant.REQUIRE);
                }

                out.put(node.name, new MethodNamespace(node.name, node.superName, node.interfaces, methods, (node.access & Opcodes.ACC_ABSTRACT) != 0));
            }
        }
    }
    protected void collectMethods(@Nullable MethodNamespace clazz, Map<String, MethodNamespace> classpath, InheritanceVariant criterion, Set<MethodId> out) {
        if (clazz == null) {
            return;
        }

        for (Map.Entry<MethodId, InheritanceVariant> entry : clazz.methods.entrySet()) {
            if (entry.getValue() == criterion) {
                out.add(entry.getKey());
            }
        }

        this.collectMethods(classpath.get(clazz.namespaceSuper), classpath, criterion, out);
        for (String itf : clazz.namespaceInterfaces) {
            this.collectMethods(classpath.get(itf), classpath, criterion, out);
        }
    }

    @InputFiles
    @Optional
    public abstract Property<Object> getClasspath();

    @Override
    public String getGroup() {
        return GslStarplanePlugin.TASK_GROUP;
    }

    @Input
    @Optional
    public abstract Property<Boolean> getIncludingGalimulatorJar();

    @InputFile
    public abstract Property<Object> getValidationJar();

    protected void verifyClass(MethodNamespace clazz, Map<String, MethodNamespace> classpath) {
        if (clazz.isAbstract) {
            return;
        }

        Set<MethodId> providedMethods = new HashSet<>();
        Set<MethodId> requiredMethods = new HashSet<>();

        this.collectMethods(clazz, classpath, InheritanceVariant.PROVIDED, providedMethods);
        this.collectMethods(clazz, classpath, InheritanceVariant.REQUIRE, requiredMethods);

        if (!providedMethods.containsAll(requiredMethods)) {
            requiredMethods.removeAll(providedMethods);
            this.getLogger().error("Class {} may contain mapping tears. Following methods are not properly implemented: {}", clazz.namespaceName, requiredMethods);
        }
    }

    @TaskAction
    public void verifyJar() throws IOException {
        Path p = this.getProject().file(this.getValidationJar().get()).toPath();

        FileCollection cp = null;
        {
            Object cpObject = this.getClasspath().getOrNull();
            if (cpObject != null) {
                cp = this.getProject().files(cpObject);
            }

            if (this.getIncludingGalimulatorJar().getOrElse(Boolean.TRUE)) {
                FileCollection galim = this.getProject().files(GslStarplanePlugin.OBF_HANDLERS.get(this.getProject()).getOriginalGalimulatorJar());
                if (cp != null) {
                    cp = cp.plus(galim);
                } else {
                    cp = galim;
                }
            }

            if (cp == null) {
                this.getLogger().warn("Verification classpath is empty");
                cp = this.getProject().files();
            }
        }

        if (Files.notExists(p)) {
            this.getLogger().warn("Task '" + this.getPath() + ":" + this.getName() + "' did no work as the input jar does not exist.");
            return;
        }

        Map<String, MethodNamespace> verifyClasses = new HashMap<>();
        GslVerifyRemapperJarTask.pushJarContents(p, verifyClasses);

        Map<String, MethodNamespace> cpClasses = new HashMap<>();

        for (File f : cp) {
            Path extraPath = f.toPath();
            if (Files.notExists(extraPath) || Files.isDirectory(extraPath)) {
                this.getLogger().info("Skipping file {} as it either does not exist or is a directory.", extraPath);
                continue;
            }

            if (extraPath.getFileName().toString().endsWith(".class")) {
                try (InputStream is = Files.newInputStream(extraPath)) {
                    ClassReader reader = new ClassReader(is);
                    ClassNode node = new ClassNode();
                    reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                    Map<MethodId, InheritanceVariant> methods = new HashMap<>();

                    for (MethodNode method : node.methods) {
                        methods.put(new MethodId(method.name, method.desc), (method.access & Opcodes.ACC_ABSTRACT) == 0 ? InheritanceVariant.PROVIDED : InheritanceVariant.REQUIRE);
                    }

                    verifyClasses.put(node.name, new MethodNamespace(node.name, node.superName, node.interfaces, methods, (node.access & Opcodes.ACC_ABSTRACT) != 0));
                }
            } else {
                GslVerifyRemapperJarTask.pushJarContents(extraPath, cpClasses);
            }
        }

        cpClasses.putAll(verifyClasses);
        this.getLogger().debug("Verifying classes");
        long timestamp = System.currentTimeMillis();

        verifyClasses.values().forEach((ns) -> {
            this.verifyClass(ns, cpClasses);
        });

        this.getLogger().debug("Classes verified ({} ms)", System.currentTimeMillis() - timestamp);
    }
}
