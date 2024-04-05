package de.geolykt.starplane;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stianloader.softmap.SoftmapApplicationError;
import org.stianloader.softmap.SoftmapContext;
import org.stianloader.softmap.SoftmapContext.ApplicationResult;
import org.stianloader.softmap.SoftmapParseError;
import org.stianloader.softmap.tokens.Token;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitableMappingTree;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

import de.geolykt.starloader.deobf.ClassWrapper;
import de.geolykt.starloader.deobf.IntermediaryGenerator;
import de.geolykt.starloader.deobf.MethodReference;
import de.geolykt.starloader.deobf.Oaktree;
import de.geolykt.starloader.deobf.remapper.Remapper;
import de.geolykt.starloader.ras.ReversibleAccessSetterContext;
import de.geolykt.starloader.ras.ReversibleAccessSetterContext.RASTransformFailure;
import de.geolykt.starloader.ras.ReversibleAccessSetterContext.RASTransformScope;
import de.geolykt.starplane.remapping.MappingIOMappingProvider;
import de.geolykt.starplane.remapping.MultiMappingProvider;
import de.geolykt.starplane.remapping.StarplaneAnnotationRemapper;
import de.geolykt.starplane.remapping.StarplaneMappingsProvider;

public class ObfuscationHandler {

    @NotNull
    private static final String COMPILED_SOFTMAP_FILE_NAME = "compiled-softmap.tiny";
    @NotNull
    private static final String INTERMEDIARY_FILE_NAME = "slintermediary.tiny";
    private static final byte @NotNull[] IO_BUFFER = new byte[4096];
    private static final Logger LOGGER = LoggerFactory.getLogger(ObfuscationHandler.class);
    @NotNull
    private static final String STARMAP_FILE_NAME = "spstarmap.tiny";

    private static String getStarplaneChecksum() throws IOException {
        try (InputStream autodeobfClass = Autodeobf.class.getClassLoader().getResourceAsStream("de/geolykt/starplane/Autodeobf.class");
                CheckedInputStream checkedIn = new CheckedInputStream(autodeobfClass, new Adler32())) {
            while (checkedIn.read(ObfuscationHandler.IO_BUFFER) != -1); // read the entire input stream until it is exhausted
            return Long.toUnsignedString(checkedIn.getChecksum().getValue(), Character.MAX_RADIX);
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

    @NotNull
    private final Path cacheDir;

    public boolean didRefresh = false;

    @NotNull
    private final Path projectDir;

    @Nullable
    private final String rasContent;

    @NotNull
    @Unmodifiable
    private final Collection<@NotNull Path> softmapFiles;

    @NotNull
    @Unmodifiable
    private final List<Map.Entry<@NotNull MappingFormat, @NotNull Path>> supplementaryMappings;

    public ObfuscationHandler(@NotNull Path cacheDir, @NotNull Path projectDir, @Nullable String rasContent,
            @NotNull @Unmodifiable Collection<@NotNull Path> softmapFiles,
            @NotNull @Unmodifiable List<Map.Entry<@NotNull MappingFormat, @NotNull Path>> supplementaryMappings) {
        this.cacheDir = cacheDir;
        this.projectDir = projectDir;
        this.rasContent = rasContent;
        this.softmapFiles = softmapFiles;
        this.supplementaryMappings = supplementaryMappings;
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

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ObfuscationHandler) {
            ObfuscationHandler other = (ObfuscationHandler) obj;
            return Objects.equals(this.rasContent, other.rasContent)
                    && this.cacheDir.equals(other.cacheDir)
                    && this.projectDir.equals(other.projectDir)
                    && this.softmapFiles.equals(other.softmapFiles);
        }
        return false;
    }

    @NotNull
    private String getAdditionalMappingChecksums() throws IOException {
        if (this.softmapFiles.isEmpty() && this.supplementaryMappings.isEmpty()) {
            return "0";
        }

        Checksum csum = new Adler32();

        for (Path p : this.softmapFiles) {
            try (CheckedInputStream cis = new CheckedInputStream(Files.newInputStream(p), csum)) {
                while (cis.read(ObfuscationHandler.IO_BUFFER) != -1); // Discard all read bytes
            }
        }

        for (Entry<@NotNull MappingFormat, @NotNull Path> e : this.supplementaryMappings) {
            csum.update(e.getKey().ordinal());
            try (CheckedInputStream cis = new CheckedInputStream(Files.newInputStream(e.getValue()), csum)) {
                while (cis.read(ObfuscationHandler.IO_BUFFER) != -1); // Discard all read bytes
            }
        }

        return Long.toUnsignedString(csum.getValue(), Character.MAX_RADIX);
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

        final ReversibleAccessSetterContext rasInfo;
        String rasContent = this.rasContent;

        if (rasContent == null) {
            rasInfo = null;
        } else {
            try (DigestInputStream din = new DigestInputStream(new ByteArrayInputStream(rasContent.getBytes(StandardCharsets.UTF_8)), MessageDigest.getInstance("SHA-1"));
                    BufferedReader br = new BufferedReader(new InputStreamReader(din))) {
                rasInfo = new ReversibleAccessSetterContext(RASTransformScope.BUILDTIME, false);
                rasInfo.read("<mod>", br, false);
                currentHash = toHexHash(din.getMessageDigest().digest());
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read reversibleAccessSetter!", e);
            }
        }

        try {
            currentHash = currentHash + '-' + ObfuscationHandler.getStarplaneChecksum() + '-' + this.getAdditionalMappingChecksums();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Check whether the effects of the access widener needs to be computed anew.
        if (Files.exists(awHash)) {
            try (BufferedReader br = Files.newBufferedReader(awHash, StandardCharsets.UTF_8)) {
                String readLine = br.readLine();
                if (!readLine.equalsIgnoreCase(currentHash)) {
                    recomputeAw = true;
                    ObfuscationHandler.LOGGER.warn("AW Hash mismatch. Expected: " + readLine + ", but the current aw hash is " + currentHash + ". Caches are considered invalid.");
                }
            } catch (IOException e) {
                e.printStackTrace();
                recomputeAw = true;
            }
        } else if (rasInfo != null) {
            recomputeAw = true; // AW file newly created
        }

        final Path runAccess = this.cacheDir.resolve("galimulator-remapped-rt.jar");
        final Path compileAccess = this.cacheDir.resolve("galimulator-remapped.jar");

        if (Files.isRegularFile(runAccess) && Files.isRegularFile(compileAccess) && !Boolean.getBoolean("de.geolykt.starplane.nocache") && !recomputeAw) {
            return compileAccess;
        }

        this.didRefresh = true;

        // Now, somehow obtain the galim jar
        File cleanGalimJar = new File(this.projectDir.toFile(), "galimulator-desktop.jar");

        found:
        if (!cleanGalimJar.exists()) {
            LOGGER.info("Galimulator jar at " + cleanGalimJar.getAbsolutePath() + " not found.");
            cleanGalimJar = new File(this.cacheDir.toFile(), "galimulator-desktop.jar");
            if (cleanGalimJar.exists()) {
                break found;
            }

            LOGGER.info("Galimulator jar at " + cleanGalimJar.getAbsolutePath() + " not found.");
            String propertyPath = System.getProperty("de.geolykt.starplane.galimulator-jar");

            if (propertyPath != null) {
                cleanGalimJar = this.projectDir.resolve(propertyPath).toFile();
                if (cleanGalimJar.exists()) {
                    break found;
                }
                LOGGER.info("Galimulator jar at " + cleanGalimJar.getAbsolutePath() + " not found.");
            } else {
                LOGGER.info("System property 'de.geolykt.starplane.galimulator-jar' not defined.");
            }

            // obtain galimulator jar
            File galimDir = Utils.getGameDir("Galimulator");

            if (galimDir != null && galimDir.exists()) {
                cleanGalimJar = new File(galimDir, "jar/galimulator-desktop.jar");
                if (cleanGalimJar.exists()) {
                    break found;
                }
                LOGGER.error("Unable to resolve galimulator jar file (was able to resolve the potential directory though)!");
            } else {
                LOGGER.error("Unable to resolve galimulator directory!");
            }

            throw new IllegalStateException("Cannot resolve dependencies");
        }

        LOGGER.info("Using the base galimulator jar found at " + cleanGalimJar.getAbsolutePath());

        Path map = this.cacheDir.resolve(ObfuscationHandler.INTERMEDIARY_FILE_NAME);
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

            IntermediaryGenerator generator = new IntermediaryGenerator(map, null, deobfuscator.getClassNodesDirectly());
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
                try (Writer writer = Files.newBufferedWriter(this.cacheDir.resolve(ObfuscationHandler.STARMAP_FILE_NAME), StandardOpenOption.CREATE)) {
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
                throw new RuntimeException("Cannot write Autodeobf.java-generated mappings", e);
            }

            try {
                Path compiledSoftmap = this.cacheDir.resolve(ObfuscationHandler.COMPILED_SOFTMAP_FILE_NAME);
                if (this.softmapFiles.isEmpty()) {
                    Files.deleteIfExists(compiledSoftmap);
                } else {
                    Remapper remapper = new Remapper();
                    remapper.addTargets(deobfuscator.getClassNodesDirectly());
                    long startOfSoftmap = System.currentTimeMillis();

                    @SuppressWarnings("null")
                    @NotNull
                    List<@NotNull ClassNode> nodes = deobfuscator.getClassNodesDirectly();
                    List<@NotNull String> allTiny = new ArrayList<>();

                    allTiny.add("v1\tintermediary\tnamed");
                    allTiny.add("# This file was compiled from softmap files, do not touch unless you know what you are doing");

                    for (Path softmapFile : this.softmapFiles) {
                        List<@NotNull String> generatedTiny = this.compileSoftmap(softmapFile, nodes);
                        allTiny.addAll(generatedTiny);
                        for (String s : generatedTiny) {
                            String[] parts = s.split("\\s+");
                            if (parts[0].equals("METHOD")) {
                                remapper.remapMethod(parts[1], parts[2], parts[3], parts[4]);
                            } else if (parts[0].equals("FIELD")) {
                                remapper.remapField(parts[1], parts[2], parts[3], parts[4]);
                            } else if (parts[0].equals("CLASS")) {
                                remapper.remapClassName(parts[1], parts[2]);
                            }
                        }
                        remapper.process();
                    }

                    Files.write(compiledSoftmap, allTiny, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                    ObfuscationHandler.LOGGER.info("Compiled all softmap files in " + (System.currentTimeMillis() - startOfSoftmap) + "ms.");
                }
            } catch (Exception e) {
                throw new RuntimeException("Cannot write softmap-generated mappings", e);
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

            if (rasInfo == null) {
                try (OutputStream os = Files.newOutputStream(compileAccess)) {
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
                    if (node == null) {
                        continue;
                    }
                    // Apply RAS
                    try {
                        rasInfo.accept(node);
                    } catch (RASTransformFailure e) {
                        LOGGER.error("Unable to apply RAS on class {}", node.name, e);
                    }

                    ClassNode duplicate = new ClassNode();
                    node.accept(duplicate);
                    runNodes.add(duplicate);
                    compileNodes.put(node.name, node);
                }

                try (BufferedWriter bw = Files.newBufferedWriter(awHash, StandardCharsets.UTF_8)) {
                    bw.write(currentHash);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Write compile-time nodes to disk
                try (OutputStream os = Files.newOutputStream(compileAccess)) {
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

        if (!this.supplementaryMappings.isEmpty()) {
            // FIXME Find a more performance-oriented path for the long-term
            // - as of now the only excuse we have is that supplementary mappings are an experimental feature subject to change
            Path ctWorkJar = compileAccess;
            Path rtWorkJar = runAccess;
            int i = 0;
            TinyRemapper remapper = null;
            for (Map.Entry<@NotNull MappingFormat, @NotNull Path> supplementaryMapping : this.supplementaryMappings) {
                VisitableMappingTree mappingTree = new MemoryMappingTree(); 
                try {
                    MappingReader.read(supplementaryMapping.getValue(), supplementaryMapping.getKey(), mappingTree);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to consume supplementary mappings file at " + supplementaryMapping, e);
                }
                mappingTree.reset();
                Path ctTargetJar = ctWorkJar.resolveSibling("compile-access-jar-" + (i = (i + 1) % 2) + ".tmp");
                try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(ctTargetJar).assumeArchive(true).build()) {
                    remapper = TinyRemapper.newRemapper()
                            .withMappings(new MappingIOMappingProvider(mappingTree, mappingTree.getMinNamespaceId(), mappingTree.getMaxNamespaceId() - 1))
                            .extension(new StarplaneAnnotationRemapper())
                            .extension(new MixinExtension())
                            .build();
                    remapper.readInputs(ctWorkJar);
                    outputConsumer.addNonClassFiles(ctWorkJar);
                    remapper.apply(outputConsumer);
                } catch (IOException t) {
                    throw new UncheckedIOException("Unable to apply supplementary mappings file at " + supplementaryMapping + " for compile-time access", t);
                } finally {
                    if (remapper != null) {
                        remapper.finish();
                    }
                }
                ctWorkJar = ctTargetJar;
                mappingTree.reset();
                Path rtTargetJar = ctWorkJar.resolveSibling("runtime-access-jar-" + (i = (i + 1) % 2) + ".tmp");
                try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(rtTargetJar).assumeArchive(true).build()) {
                    remapper = TinyRemapper.newRemapper()
                            .withMappings(new MappingIOMappingProvider(mappingTree, mappingTree.getMinNamespaceId(), mappingTree.getMaxNamespaceId() - 1))
                            .extension(new StarplaneAnnotationRemapper())
                            .extension(new MixinExtension())
                            .build();
                    remapper.readInputs(rtWorkJar);
                    outputConsumer.addNonClassFiles(rtWorkJar);
                    remapper.apply(outputConsumer);
                } catch (IOException t) {
                    throw new UncheckedIOException("Unable to apply supplementary mappings file at " + supplementaryMapping + " for compile-time access", t);
                } finally {
                    if (remapper != null) {
                        remapper.finish();
                    }
                }
                rtWorkJar = rtTargetJar;
            }

            try {
                Files.move(ctWorkJar, compileAccess, StandardCopyOption.REPLACE_EXISTING);
                Files.move(rtWorkJar, runAccess, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to apply supplementary mappings: Generic I/O error", e);
            }
        }

        return compileAccess;
    }

    @NotNull
    @Unmodifiable
    @Contract(pure = true)
    private List<@NotNull String> compileSoftmap(@NotNull Path softmapFile, @NotNull List<@NotNull ClassNode> obfuscatedNodes) throws IOException {
        long fileStart = System.currentTimeMillis();
        String softmapContent = new String(Files.readAllBytes(softmapFile), StandardCharsets.UTF_8);
        SoftmapContext softmapContext = SoftmapContext.parse(softmapContent, 0, softmapContent.length(), 1, 1);

        List<SoftmapParseError> parseErrors = softmapContext.getParseErrors();
        if (!parseErrors.isEmpty()) {
            System.out.println();
            for (SoftmapParseError error : parseErrors) {
                String contentSplice;
                if (error.endCodepoint - error.startCodepoint < 100) {
                    contentSplice = softmapContent.substring(error.startCodepoint, error.endCodepoint);
                } else {
                    contentSplice = softmapContent.substring(error.startCodepoint, error.startCodepoint + 80);
                    contentSplice = contentSplice + "... (and " + (error.endCodepoint - error.startCodepoint - 80) + " further characters)";
                }
                ObfuscationHandler.LOGGER.error("Syntax error in softmap file {} (row {}, column {}): {}", softmapFile, error.row, error.column, error.getDescription());
                ObfuscationHandler.LOGGER.error("Invalid token: {}", contentSplice);
            }
            System.out.println();
        }

        @SuppressWarnings("null")
        ApplicationResult result = softmapContext.tryApply(obfuscatedNodes);

        List<SoftmapApplicationError> applyErrors = result.getErrors();
        if (!applyErrors.isEmpty()) {
            System.out.println();
            for (SoftmapApplicationError error : applyErrors) {
                String contentSplice;
                Token errorLoc = error.getErrorLocation();
                if (errorLoc.getStart() - errorLoc.getEnd() < 100) {
                    contentSplice = softmapContent.substring(errorLoc.getStart(), errorLoc.getEnd());
                } else {
                    contentSplice = softmapContent.substring(errorLoc.getStart(), errorLoc.getStart() + 80);
                    contentSplice = contentSplice + "... (and " + (errorLoc.getEnd() - errorLoc.getStart() - 80) + " further characters)";
                }
                ObfuscationHandler.LOGGER.error("Application error in softmap file {} (row {}, column {}): {}.", softmapFile, errorLoc.getRow(), errorLoc.getColumn(), error.getDescription());
                ObfuscationHandler.LOGGER.error("Invalid token: {}", contentSplice);
            }
            System.out.println();
        }

        ObfuscationHandler.LOGGER.info("Softmap file {} compiled in {}ms.", softmapFile, (System.currentTimeMillis() - fileStart));
        return result.getGeneratedTinyV1Mappings();
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.rasContent, this.cacheDir, this.projectDir, this.softmapFiles);
    }

    public void reobfuscateJar(@NotNull Path jarPath, @NotNull Path starmappedGalimulator,
            @NotNull Collection<@NotNull Path> alsoInclude) throws IOException {
        Path spstarmap = this.cacheDir.resolve(ObfuscationHandler.STARMAP_FILE_NAME);
        Path slintermediary = this.cacheDir.resolve(ObfuscationHandler.INTERMEDIARY_FILE_NAME);
        Path compiledSoftmap = this.cacheDir.resolve(ObfuscationHandler.COMPILED_SOFTMAP_FILE_NAME);

        TinyRemapper tinyRemapper = TinyRemapper.newRemapper()
                .withMappings(
                        new MultiMappingProvider(
                                new StarplaneMappingsProvider(slintermediary, true),
                                new StarplaneMappingsProvider(spstarmap, true),
                                new StarplaneMappingsProvider(compiledSoftmap, true, true)
                        )
                ).extension(new StarplaneAnnotationRemapper())
                .extension(new MixinExtension())
                .keepInputData(true)
                .build();


        if (!this.supplementaryMappings.isEmpty()) {
            LOGGER.info("Applying supplementary mappings");
            // FIXME Find a more performance-oriented path for the long-term
            // - as of now the only excuse we have is that supplementary mappings are an experimental feature subject to change
            Path workJar = jarPath;
            int i = 0;
            TinyRemapper remapper = null;
            ListIterator<Map.Entry<@NotNull MappingFormat, @NotNull Path>> it = this.supplementaryMappings.listIterator(this.supplementaryMappings.size());
            Set<Path> cleanPaths = new HashSet<>();
            try {
                while (it.hasPrevious()) {
                    Map.Entry<@NotNull MappingFormat, @NotNull Path> supplementaryMapping = it.previous();
                    VisitableMappingTree mappingTree = new MemoryMappingTree(); 
                    try {
                        MappingReader.read(supplementaryMapping.getValue(), supplementaryMapping.getKey(), mappingTree);
                    } catch (IOException e) {
                        throw new IOException("Unable to consume supplementary mappings file at " + supplementaryMapping, e);
                    }
                    mappingTree.reset();
                    Path targetJar = jarPath.resolveSibling("supplementary-reobf-jar-" + (i = (i + 1) % 2) + ".tmp");
                    cleanPaths.add(targetJar);
                    try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(targetJar).assumeArchive(true).build()) {
                        remapper = TinyRemapper.newRemapper()
                                .withMappings(new MappingIOMappingProvider(mappingTree, mappingTree.getMinNamespaceId(), mappingTree.getMaxNamespaceId() - 1))
                                .extension(new StarplaneAnnotationRemapper())
                                .extension(new MixinExtension())
                                .build();
                        remapper.readInputs(workJar);
                        remapper.apply(outputConsumer);
                    } catch (IOException t) {
                        throw new IOException("Unable to apply supplementary mappings file at " + supplementaryMapping + " for compile-time access", t);
                    } finally {
                        if (remapper != null) {
                            remapper.finish();
                        }
                    }
                    workJar = targetJar;
                }

                try {
                    Files.move(workJar, jarPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IOException("Unable to apply supplementary mappings: Generic I/O error", e);
                }
            } finally {
                for (Path p : cleanPaths) {
                    Files.deleteIfExists(p); // The move operation likely has deleted one file already
                }
            }
        }

        Path intermediaryBuild = this.cacheDir.resolve("temporaryBuild.jar");
        try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(intermediaryBuild).build()) {
            LOGGER.info("Reobfuscating... (reading inputs)");
            tinyRemapper.readInputs(jarPath);
            tinyRemapper.readClassPath(starmappedGalimulator);
            outputConsumer.addNonClassFiles(jarPath, tinyRemapper, Collections.singletonList(SimpleRASRemapper.INSTANCE));
            for (Path additionalInput : alsoInclude) {
                tinyRemapper.readInputs(additionalInput);
                outputConsumer.addNonClassFiles(additionalInput, tinyRemapper, Collections.singletonList(SimpleRASRemapper.INSTANCE));
            }
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
}