package de.geolykt.starplane;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.gradle.internal.impldep.com.google.common.base.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

import de.geolykt.starloader.deobf.ClassWrapper;
import de.geolykt.starloader.deobf.IntermediaryGenerator;
import de.geolykt.starloader.deobf.MethodReference;
import de.geolykt.starloader.deobf.Oaktree;
import de.geolykt.starloader.deobf.access.AccessTransformInfo;
import de.geolykt.starloader.deobf.access.AccessWidenerReader;
import de.geolykt.starloader.deobf.remapper.Remapper;
import de.geolykt.starplane.remapping.MultiMappingProvider;
import de.geolykt.starplane.remapping.StarplaneAnnotationRemapper;
import de.geolykt.starplane.remapping.StarplaneMappingsProvider;
import de.geolykt.starplane.remapping.TRAccessWidenerRemapper;

public class ObfuscationHandler {

    private static final String INTERMEDIARY_FILE_NAME = "slintermediary.tiny";
    private static final String STARMAP_FILE_NAME = "spstarmap.tiny";
    private static final byte[] IO_BUFFER = new byte[4096];
    private static final Logger LOGGER = LoggerFactory.getLogger(ObfuscationHandler.class);

    @Nullable
    private final String accessWidenerContent;

    @NotNull
    private final Path cacheDir;

    public boolean didRefresh = false;

    @NotNull
    private final Path projectDir;

    public ObfuscationHandler(@NotNull Path cacheDir, @NotNull Path projectDir, @Nullable String accessWidenerContent) {
        this.cacheDir = cacheDir;
        this.projectDir = projectDir;
        this.accessWidenerContent = accessWidenerContent;
    }

    @NotNull
    public Path getTransformedGalimulatorJar() {
        if (!Files.isDirectory(this.cacheDir)) {
            try {
                Files.createDirectories(this.cacheDir);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create cache folder!", e);
            }
        }

        Path awHash = this.cacheDir.resolve("accesswidener-hash.dat");
        boolean recomputeAw = false;
        String currentHash = null;

        final AccessTransformInfo atInfo;
        String accessWidenerContent = this.accessWidenerContent; // Eclipse has a hard time figuring out the meaning of `final`.

        if (accessWidenerContent == null) {
            atInfo = null;
        } else {
            try (InputStream is = new ByteArrayInputStream(accessWidenerContent.getBytes(StandardCharsets.UTF_8))) {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                try (DigestInputStream in = new DigestInputStream(is, digest)) {
                    atInfo = new AccessTransformInfo();
                    try (AccessWidenerReader awr = new AccessWidenerReader(atInfo, in, false)) {
                        awr.readHeader();
                        while (awr.readLn());
                    }
                    currentHash = toHexHash(digest.digest());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read accesswidener!", e);
            }
        }

        try {
            currentHash = currentHash + '-' + getStarplaneChecksum();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Check whether the effects of the access widener needs to be computed anew.
        if (Files.exists(awHash)) {
            try (BufferedReader br = Files.newBufferedReader(awHash, StandardCharsets.UTF_8)) {
                String readLine = br.readLine();
                if (!readLine.equalsIgnoreCase(currentHash)) {
                    recomputeAw = true;
                    LOGGER.info("AW Hash mismatch. Expected: " + readLine + ", but the current aw hash is " + currentHash);
                }
            } catch (IOException e) {
                e.printStackTrace();
                recomputeAw = true;
            }
        } else if (atInfo != null) {
            recomputeAw = true; // AW file newly created
        }

        final Path runAccess = this.cacheDir.resolve("galimulator-remapped-rt.jar");
        final Path compileAccess = this.cacheDir.resolve("galimulator-remapped.jar");

        if (Files.isRegularFile(runAccess) && Files.isRegularFile(compileAccess) && !Boolean.getBoolean("de.geolykt.starplane.nocache") && !recomputeAw) {
            return compileAccess;
        }

        didRefresh = true;

        // Now, somehow obtain the galim jar
        File cleanGalimJar = new File(this.projectDir.toFile(), "galimulator-desktop.jar");
        if (!cleanGalimJar.exists()) {
            LOGGER.info("Galimulator jar at " + cleanGalimJar.getAbsolutePath() + " not found.");
            cleanGalimJar = new File(this.cacheDir.toFile(), "galimulator-desktop.jar");
            if (!cleanGalimJar.exists()) {
                LOGGER.info("Galimulator jar at " + cleanGalimJar.getAbsolutePath() + " not found.");
                // obtain galimulator jar
                File galimDir = Utils.getGameDir("Galimulator");
                if (galimDir == null || !galimDir.exists()) {
                    LOGGER.error("Unable to resolve galimulator directory!");
                    throw new IllegalStateException("Cannot resolve dependencies");
                }
                cleanGalimJar = new File(galimDir, "jar/galimulator-desktop.jar");
                if (!cleanGalimJar.exists()) {
                    LOGGER.error(
                            "Unable to resolve galimulator jar file (was able to resolve the potential directory though)!");
                    throw new IllegalStateException("Cannot resolve dependencies");
                }
            }
        }
        LOGGER.info("Using the base galimulator jar found at " + cleanGalimJar.getAbsolutePath());

        File map = this.cacheDir.resolve(INTERMEDIARY_FILE_NAME).toFile();
        Oaktree deobfuscator = new Oaktree();
        try {
            long start = System.currentTimeMillis();
            JarFile jar = new JarFile(cleanGalimJar);
            deobfuscator.index(jar);
            jar.close();
            Map<String, ClassNode> nameToNode = new HashMap<>();
            for (ClassNode node : deobfuscator.getClassNodesDirectly()) {
                nameToNode.put(node.name, node);
            }
            LOGGER.info("Loaded input jar in " + (System.currentTimeMillis() - start) + " ms.");
            long startDeobf = System.currentTimeMillis();
            deobfuscator.fixInnerClasses();
            deobfuscator.fixParameterLVT();
            deobfuscator.guessFieldGenerics();
            addSignatures(deobfuscator.getClassNodesDirectly(), nameToNode, deobfuscator.analyseLikelyMethodReturnCollectionGenerics());
            Map<MethodReference, ClassWrapper> methods = new HashMap<>();
            deobfuscator.lambdaStreamGenericSignatureGuessing(null, methods);
            addSignatures(deobfuscator.getClassNodesDirectly(), nameToNode, methods);
            deobfuscator.inferMethodGenerics();
            deobfuscator.inferConstructorGenerics();
            deobfuscator.fixForeachOnArray();
            deobfuscator.fixComparators(true);
            deobfuscator.guessAnonymousInnerClasses();

            LOGGER.info("Deobfuscated classes in " + (System.currentTimeMillis() - startDeobf) + " ms.");
            long startIntermediarisation = System.currentTimeMillis();

            IntermediaryGenerator generator = new IntermediaryGenerator(map, null,
                    deobfuscator.getClassNodesDirectly());
            generator.useAlternateClassNaming(!Boolean.getBoolean("de.geolykt.starplane.oldnames"));
            generator.remapClassesV2(true);
            deobfuscator.fixSwitchMaps();
            generator.doProposeEnumFieldsV2();
            generator.remapGetters();
            generator.deobfuscate();

            try {
                Remapper remapper = new Remapper();
                remapper.addTargets(deobfuscator.getClassNodesDirectly());
                long startSlStarmap = System.currentTimeMillis();
                Autodeobf deobf = new Autodeobf(deobfuscator.getClassNodesDirectly(), remapper);
                try (Writer writer = Files.newBufferedWriter(this.cacheDir.resolve(STARMAP_FILE_NAME), StandardOpenOption.CREATE)) {
                    writer.write("v1\tintermediary\tnamed\n");
                    deobf.runAll(writer);
                    for (Map.Entry<String, String> e : remapper.fixICNNames(new StringBuilder()).entrySet()) {
                        writer.write("CLASS\t");
                        writer.write(e.getKey());
                        writer.write('\t');
                        writer.write(e.getValue());
                        writer.write('\n');
                    }
                    writer.flush();
                    remapper.process();
                }
                LOGGER.info("Computed spStarmap in " + (System.currentTimeMillis() - startSlStarmap) + " ms.");
            } catch (Exception e) {
                throw new RuntimeException("Cannot write mappings", e);
            }

            deobfuscator.invalidateNameCaches();

            /*
            System.out.println("Deobfuscator found " + deobfuscator.guessAnonymousClasses().size() + " anon classes agressively");
            for (Map.Entry<String, MethodReference> e : deobfuscator.guessAnonymousClasses().entrySet()) {
                ClassNode node = nameToNode.get(e.getKey());
                if (!node.name.startsWith("snodd")) {
                    continue;
                }
                MethodReference mref = e.getValue();
                node.outerClass = mref.getOwner();
                node.outerMethod = mref.getName();
                node.outerMethodDesc = mref.getDesc();
                node.innerClasses.add(new InnerClassNode(node.name, mref.getOwner(), null, 0));
            }*/
            /*
            {
                Map<String, String> mappedNames = deobfuscator.getProposedLocalClassNames("InnerClass", (outer, inner) -> {
                    return inner.startsWith("snod");
                }, true);
                Remapper remapper = new Remapper();
                remapper.addTargets(deobfuscator.getClassNodesDirectly());
                remapper.remapClassNames(mappedNames);
                remapper.process();
                try (Writer writer = Files.newBufferedWriter(this.cacheDir.resolve(POSTINTERMEDIARY_FILE_NAME), StandardOpenOption.CREATE)) {
                    writer.write("v1\tintermediary\tnamed\n");
                    for (Map.Entry<String, String> entry : mappedNames.entrySet()) {
                        writer.write("CLASS ");
                        writer.write(entry.getKey());
                        writer.write(' ');
                        writer.write(entry.getValue());
                        writer.write('\n');
                    }
                }
                Logger.info("Deobfuscated " + mappedNames.size() + " local classes");
            }
            */

            deobfuscator.applyInnerclasses();

            LOGGER.info("Computed intermediaries of classes in " + (System.currentTimeMillis() - startIntermediarisation) + " ms.");

            if (atInfo == null) {
                try (OutputStream os = Files.newOutputStream(compileAccess)) {
                    assert os != null;
                    deobfuscator.write(os, cleanGalimJar.toPath());
                }
                // Compile-time Access = Runtime Access
                Files.copy(compileAccess, runAccess, StandardCopyOption.REPLACE_EXISTING);

                try (BufferedWriter bw = Files.newBufferedWriter(awHash, StandardCharsets.UTF_8)) {
                    bw.write("null-");
                    bw.write(getStarplaneChecksum());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                // Duplicate all nodes
                List<ClassNode> runNodes = new ArrayList<>();
                Map<String, ClassNode> compileNodes = new HashMap<>();

                for (ClassNode node : deobfuscator.getClassNodesDirectly()) {
                    ClassNode duplicate = new ClassNode();
                    node.accept(duplicate);
                    runNodes.add(duplicate);
                    compileNodes.put(node.name, node);
                    // Apply runtime AWs
                    atInfo.apply(duplicate, true);
                }

                // Apply compile-time AWs
                atInfo.apply(compileNodes, LOGGER::error);

                try (BufferedWriter bw = Files.newBufferedWriter(awHash, StandardCharsets.UTF_8)) {
                    bw.write(currentHash);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Write compile-time nodes to disk
                try (OutputStream os = Files.newOutputStream(compileAccess)) {
                    assert os != null;
                    deobfuscator.write(os, cleanGalimJar.toPath());
                }

                // Write runtime nodes to disk
                try (ZipOutputStream os = new ZipOutputStream(Files.newOutputStream(runAccess), StandardCharsets.UTF_8)) {
                    // Copy resources
                    try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(cleanGalimJar))) {
                        for (ZipEntry entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
                            if (entry.getName().endsWith(".class")) {
                                // Do not copy classes
                                continue;
                            }
                            os.putNextEntry(entry);
                            byte[] b = new byte[4096];
                            int read;
                            while ((read = zipIn.read(b)) != -1) {
                                os.write(b, 0, read);
                            }
                        }
                    }
                    // Write the actual nodes - in alphabetic order to preserve consistency
                    TreeSet<ClassNode> sortedNodes = new TreeSet<>((node1, node2) -> node1.name.compareTo(node2.name));
                    sortedNodes.addAll(runNodes);
                    for (ClassNode node : sortedNodes) {
                        ClassWriter writer = new ClassWriter(0);
                        node.accept(writer);
                        os.putNextEntry(new ZipEntry(node.name + ".class"));
                        os.write(writer.toByteArray());
                    }
                }
            }
            LOGGER.info("Finished transforming classes in " + (System.currentTimeMillis() - start) + " ms.");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return compileAccess;
    }

    private static String getStarplaneChecksum() throws IOException {
        try (InputStream autodeobfClass = Autodeobf.class.getClassLoader().getResourceAsStream("de/geolykt/starplane/Autodeobf.class");
                CheckedInputStream checkedIn = new CheckedInputStream(autodeobfClass, new Adler32())) {
            while (checkedIn.read(IO_BUFFER) != -1); // read the entire input stream until it is exhausted
            return Long.toString(checkedIn.getChecksum().getValue());
        }
    }

    @SuppressWarnings("null")
    @NotNull
    private static String toHexHash(byte[] hash) {
        final StringBuilder hex = new StringBuilder(2 * hash.length);
        for (final byte b : hash) {
            int x = ((int) b) & 0x00FF;
            if (x < 16) {
                hex.append('0');
            }
            hex.append(Integer.toHexString(x));
        }
        return hex.toString();
    }

    private void addSignatures(List<ClassNode> nodes, Map<String, ClassNode> nameToNode,
            Map<MethodReference, ClassWrapper> signatures) {
        StringBuilder builder = new StringBuilder();
        for (ClassNode node : nodes) {
            for (MethodNode method : node.methods) {
                if (method.signature == null) {
                    ClassWrapper newSignature = signatures.get(new MethodReference(node.name, method));
                    if (newSignature == null) {
                        continue;
                    }
                    builder.append(method.desc, 0, method.desc.length() - 1);
                    builder.append("<L");
                    builder.append(newSignature.getName());
                    builder.append(";>;");
                    method.signature = builder.toString();
                    builder.setLength(0);
                }
            }
        }
    }

    public void reobfuscateJar(@NotNull Path jarPath, @NotNull Path starmappedGalimulator) throws IOException {
        Path spstarmap = this.cacheDir.resolve(STARMAP_FILE_NAME);
        Path slintermediary = this.cacheDir.resolve(INTERMEDIARY_FILE_NAME);

        TinyRemapper tinyRemapper = TinyRemapper.newRemapper()
                .withMappings(
                        new MultiMappingProvider(
                                new StarplaneMappingsProvider(slintermediary, true),
                                new StarplaneMappingsProvider(spstarmap, true)
                        )
                ).extension(new StarplaneAnnotationRemapper())
                .extension(new MixinExtension())
                .keepInputData(true)
                .build();

        Path intermediaryBuild = this.cacheDir.resolve("temporaryBuild.jar");
        try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(intermediaryBuild).build()) {
            LOGGER.info("Reobfuscating... (reading inputs)");
            // TR is very strange. Why does it not accept class nodes?
            tinyRemapper.readInputs(jarPath);
            tinyRemapper.readClassPath(starmappedGalimulator);
            outputConsumer.addNonClassFiles(jarPath, tinyRemapper, Arrays.asList(new TRAccessWidenerRemapper()));
            LOGGER.info("Reobfuscating... (applying)");
            tinyRemapper.apply(outputConsumer);
        } catch (IOException t) {
            throw new RuntimeException(t);
        } finally {
            tinyRemapper.finish();
        }
        Files.move(intermediaryBuild, jarPath, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("Reobfuscating... (done)");
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ObfuscationHandler) {
            ObfuscationHandler other = (ObfuscationHandler) obj;
            return Objects.equal(this.accessWidenerContent, other.accessWidenerContent)
                    && this.cacheDir.equals(other.cacheDir)
                    && this.projectDir.equals(other.projectDir);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.accessWidenerContent, this.cacheDir, this.projectDir);
    }
}