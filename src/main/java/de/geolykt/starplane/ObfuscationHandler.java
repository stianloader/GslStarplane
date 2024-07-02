package de.geolykt.starplane;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stianloader.micromixin.remapper.IllegalMixinException;
import org.stianloader.micromixin.remapper.MicromixinRemapper;
import org.stianloader.micromixin.remapper.MissingFeatureException;
import org.stianloader.remapper.HierarchyAwareMappingDelegator;
import org.stianloader.remapper.HierarchyAwareMappingDelegator.TopLevelMemberLookup;
import org.stianloader.remapper.MappingLookup;
import org.stianloader.remapper.Remapper;
import org.stianloader.remapper.SimpleHierarchyAwareMappingLookup;
import org.stianloader.remapper.SimpleTopLevelLookup;
import org.stianloader.softmap.SoftmapApplicationError;
import org.stianloader.softmap.SoftmapContext;
import org.stianloader.softmap.SoftmapContext.ApplicationResult;
import org.stianloader.softmap.SoftmapParseError;
import org.stianloader.softmap.tokens.Token;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitableMappingTree;

import de.geolykt.starloader.deobf.ClassWrapper;
import de.geolykt.starloader.deobf.IntermediaryGenerator;
import de.geolykt.starloader.deobf.MethodReference;
import de.geolykt.starloader.deobf.Oaktree;
import de.geolykt.starloader.ras.ReversibleAccessSetterContext;
import de.geolykt.starloader.ras.ReversibleAccessSetterContext.RASTransformFailure;
import de.geolykt.starloader.ras.ReversibleAccessSetterContext.RASTransformScope;
import de.geolykt.starplane.remapping.ChainMappingLookup;
import de.geolykt.starplane.remapping.RASRemapper;
import de.geolykt.starplane.remapping.ReadOnlyMIOMappingLookup;
import de.geolykt.starplane.remapping.ReadOnlyMappingLookupSink;
import de.geolykt.starplane.remapping.StarplaneAnnotationRemapper;
import de.geolykt.starplane.remapping.StarplaneMappingLookup;

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

    public void deobfuscateJar(@NotNull Path source, @NotNull Path target) throws IOException {
        Path spstarmap = this.cacheDir.resolve(ObfuscationHandler.STARMAP_FILE_NAME);
        Path slintermediary = this.cacheDir.resolve(ObfuscationHandler.INTERMEDIARY_FILE_NAME);
        Path compiledSoftmap = this.cacheDir.resolve(ObfuscationHandler.COMPILED_SOFTMAP_FILE_NAME);

        List<@NotNull MappingLookup> lookups = new ArrayList<>();
        lookups.add(new StarplaneMappingLookup(slintermediary, false).load());
        lookups.add(new StarplaneMappingLookup(spstarmap, false).load());
        lookups.add(new StarplaneMappingLookup(compiledSoftmap, false, true).load());

        if (!this.supplementaryMappings.isEmpty()) {
            LOGGER.info("Loading supplementary mappings");
            ListIterator<Map.Entry<@NotNull MappingFormat, @NotNull Path>> it = this.supplementaryMappings.listIterator(0);

            while (it.hasNext()) {
                Map.Entry<@NotNull MappingFormat, @NotNull Path> supplementaryMapping = it.next();
                VisitableMappingTree mappingTree = new MemoryMappingTree(); 
                try {
                    MappingReader.read(supplementaryMapping.getValue(), supplementaryMapping.getKey(), mappingTree);
                } catch (IOException e) {
                    throw new IOException("Unable to consume supplementary mappings file at " + supplementaryMapping, e);
                }
                mappingTree.reset();
                lookups.add(new ReadOnlyMIOMappingLookup(mappingTree, mappingTree.getMinNamespaceId(), mappingTree.getMaxNamespaceId() - 1));
            }
        }

        Map<String, ClassNode> remapNodes = new HashMap<>();
        Map<String, byte[]> rawFiles = new HashMap<>();

        try (InputStream is = Files.newInputStream(source); ZipInputStream zipIn = new ZipInputStream(is, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zipIn.getNextEntry()) != null) {
                byte[] allData = zipIn.readAllBytes();
                if (allData.length < 4
                        || allData[0] != (byte) 0xCA
                        || allData[1] != (byte) 0xFE
                        || allData[2] != (byte) 0xBA
                        || allData[3] != (byte) 0xBE
                        || !e.getName().endsWith(".class")) {
                    if (rawFiles.put(e.getName(), allData) != null) {
                        ObfuscationHandler.LOGGER.warn("Overwrote entry for raw file {}. The remapped jar may be malformed", e.getName());
                    }
                    continue;
                }
                try {
                    ClassReader reader = new ClassReader(allData);
                    ClassNode visitedNode = new ClassNode();
                    reader.accept(visitedNode, 0); // Do not skip anything (we will write the nodes as-is, including frames!)
                    remapNodes.put(e.getName(), visitedNode);
                } catch (Exception ex) {
                    ObfuscationHandler.LOGGER.warn("Unable to read classfile {}; treating it as a regular file instead.", e.getName(), ex);
                    if (rawFiles.put(e.getName(), allData) != null) {
                        ObfuscationHandler.LOGGER.warn("Overwrote entry for raw file {}. The remapped jar may be malformed", e.getName());
                    }
                }
            }
        } catch (IOException e) {
            throw new IOException("Unable to read input jar " + source, e);
        }

        Map<String, ClassNode> libraryNodes = new HashMap<>();
        try (InputStream is = Files.newInputStream(this.getOriginalGalimulatorJar());
                ZipInputStream zipIn = new ZipInputStream(is, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zipIn.getNextEntry()) != null) {
                byte[] allData = zipIn.readAllBytes();
                if (allData.length < 4
                        || allData[0] != (byte) 0xCA
                        || allData[1] != (byte) 0xFE
                        || allData[2] != (byte) 0xBA
                        || allData[3] != (byte) 0xBE
                        || !e.getName().endsWith(".class")) {
                    continue;
                }
                try {
                    ClassReader reader = new ClassReader(allData);
                    ClassNode visitedNode = new ClassNode();
                    reader.accept(visitedNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    if (libraryNodes.put(visitedNode.name, visitedNode) != null) {
                        ObfuscationHandler.LOGGER.warn("Collision for path {}, entry {}. Likely caused due to unexpected multi-release-jar", visitedNode.name, e.getName());
                    }
                } catch (Exception ex) {
                    ObfuscationHandler.LOGGER.warn("Unable to read library classfile {}; skipping it instead.", e.getName(), ex);
                }
            }
        }

        // multi-release jar version -> map of class name to ClassNode pairs.
        Map<Integer, Map<String, ClassNode>> mrjClasses = new HashMap<>();
        int maxMrjVersion = 8;

        for (Map.Entry<String, ClassNode> entry : remapNodes.entrySet()) {
            String path = entry.getKey();
            ClassNode node = entry.getValue();
            String fullpath = path;
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            int mrjVersion = 8;
            if (path.startsWith("META-INF/versions/")) {
                path = path.substring(18);
                mrjVersion = Integer.parseInt(path.substring(0, path.indexOf('/')));
                if (mrjVersion < 9) {
                    ObfuscationHandler.LOGGER.warn("Class {} of path {} would fit under the multi-release jar version of {} - which makes little sense as that would be before the introduction of multi-release jars.", node.name, fullpath, mrjVersion);
                    mrjVersion = 8;
                }
            }

            if (mrjVersion > maxMrjVersion) {
                maxMrjVersion = mrjVersion;
            }

            mrjClasses.computeIfAbsent(mrjVersion, (ignored) -> {
                return new HashMap<>();
            }).put(node.name, node);
        };

        try (OutputStream os = Files.newOutputStream(target);
                ZipOutputStream zipOut = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            @SuppressWarnings("null")
            ChainMappingLookup externalLookups = new ChainMappingLookup(lookups.toArray(new @NotNull MappingLookup[0]));
            for (int mrjVersion = maxMrjVersion; mrjVersion >= 8; mrjVersion--) {
                Map<String, ClassNode> versionClasses = mrjClasses.remove(mrjVersion);
                if (versionClasses == null) {
                    continue;
                }
                List<ClassNode> mainClasses = new ArrayList<>(versionClasses.values());
                mainClasses.sort((n1, n2) -> n1.name.compareTo(n2.name));
                for (int earlierVersion = mrjVersion; earlierVersion >= 8; earlierVersion--) {
                    Map<String, ClassNode> availableEarlier = mrjClasses.get(earlierVersion);
                    if (availableEarlier == null) {
                        continue;
                    }
                    for (Map.Entry<String, ClassNode> earlierEntry : availableEarlier.entrySet()) {
                        versionClasses.putIfAbsent(earlierEntry.getKey(), earlierEntry.getValue());
                    }
                }

                List<ClassNode> allClasses = new ArrayList<>(libraryNodes.values());
                allClasses.addAll(mainClasses);
                SimpleTopLevelLookup allTopLevelLookup = new SimpleTopLevelLookup(allClasses);
                DebugableMemberLister libraryMemberLister = new DebugableMemberLister(allTopLevelLookup, libraryNodes);

                @SuppressWarnings("null")
                SimpleHierarchyAwareMappingLookup mixinLookup = new SimpleHierarchyAwareMappingLookup(new ArrayList<>(versionClasses.values()));
                ReadOnlyMappingLookupSink readOnlyExternalLookups = new ReadOnlyMappingLookupSink(externalLookups);
                MappingLookup externalHierarchyLookup = new HierarchyAwareMappingDelegator<>(readOnlyExternalLookups, allTopLevelLookup);
                ChainMappingLookup allLookup = new ChainMappingLookup(mixinLookup, externalHierarchyLookup);
                MicromixinRemapper mixinRemapper = new MicromixinRemapper(allLookup, mixinLookup, libraryMemberLister);
                Remapper coreRemaper = new Remapper(allLookup);

                StringBuilder sharedBuilder = new StringBuilder();
                for (ClassNode mainNode : mainClasses) {
                    mainNode = Objects.requireNonNull(mainNode);

                    StarplaneAnnotationRemapper.apply(mainNode, coreRemaper, sharedBuilder);
                    try {
                        mixinRemapper.remapClass(mainNode);
                    } catch (IllegalMixinException | MissingFeatureException e) {
                        throw new IOException("Unable to remap due to a problem which occured while remapping mixin " + mainNode.name + " in multi-release-jar sourceset " + mrjVersion, e);
                    }

                    coreRemaper.remapNode(mainNode, sharedBuilder);

                    ClassWriter writer = new ClassWriter(0);
                    mainNode.accept(writer);
                    if (mrjVersion != 8) {
                        zipOut.putNextEntry(new ZipEntry("META-INF/versions/" + mrjVersion + "/" + mainNode.name + ".class"));
                    } else {
                        zipOut.putNextEntry(new ZipEntry(mainNode.name + ".class"));
                    }
                    zipOut.write(writer.toByteArray());
                }

                if (mrjVersion == 8) {
                    for (Map.Entry<String, byte[]> resource : rawFiles.entrySet()) {
                        zipOut.putNextEntry(new ZipEntry(resource.getKey()));
                        byte[] data = resource.getValue();
                        if (resource.getKey().toLowerCase(Locale.ROOT).endsWith(".ras")) {
                            data = new RASRemapper(allLookup, sharedBuilder).transform(data, "jar://?!" + resource.getKey());
                        }
                        zipOut.write(data);
                    }
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
    public Path getOriginalGalimulatorJar() {
        File cleanGalimJar = new File(this.projectDir.toFile(), "galimulator-desktop.jar");

        found:
        if (!cleanGalimJar.exists()) {
            LOGGER.debug("Galimulator jar at " + cleanGalimJar.getAbsolutePath() + " not found.");
            cleanGalimJar = new File(this.cacheDir.toFile(), "galimulator-desktop.jar");
            if (cleanGalimJar.exists()) {
                break found;
            }

            LOGGER.debug("Galimulator jar at " + cleanGalimJar.getAbsolutePath() + " not found.");
            String propertyPath = System.getProperty("de.geolykt.starplane.galimulator-jar");

            if (propertyPath != null) {
                cleanGalimJar = this.projectDir.resolve(propertyPath).toFile();
                if (cleanGalimJar.exists()) {
                    break found;
                }
                LOGGER.warn("Galimulator jar at " + cleanGalimJar.getAbsolutePath() + " not found.");
            } else {
                LOGGER.debug("System property 'de.geolykt.starplane.galimulator-jar' not defined.");
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
        return cleanGalimJar.toPath();
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
        Path cleanGalimJar = this.getOriginalGalimulatorJar();
        LOGGER.info("Using the base galimulator jar found at " + cleanGalimJar.toAbsolutePath());

        Path map = this.cacheDir.resolve(ObfuscationHandler.INTERMEDIARY_FILE_NAME);
        Oaktree deobfuscator = new Oaktree();
        try {
            long start = System.currentTimeMillis();
            JarFile jar = new JarFile(cleanGalimJar.toFile());
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

            // sl-deobf adds ACC_SUPER as that was the observed behaviour of compilers when compiling anonymous inner classes.
            // However, asm-util's ClassCheckAdapter does not tolerate that flag on anonymous inner classes, so we shall strip it.
            // In the end, this should have absolutely no impact on runtime 90% of the time (the other 10% are when the
            // ClassCheckAdapter is being used by SLL in case a class failed to transform).
            for (ClassNode node : deobfuscator.getClassNodesDirectly()) {
                for (InnerClassNode icn : node.innerClasses) {
                    icn.access &= ~Opcodes.ACC_SUPER;
                }
            }

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
                de.geolykt.starloader.deobf.remapper.Remapper remapper = new de.geolykt.starloader.deobf.remapper.Remapper();
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
                    de.geolykt.starloader.deobf.remapper.Remapper remapper = new de.geolykt.starloader.deobf.remapper.Remapper();
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

            if (!this.supplementaryMappings.isEmpty()) {
                for (Map.Entry<@NotNull MappingFormat, @NotNull Path> supplementaryMapping : this.supplementaryMappings) {
                    VisitableMappingTree mappingTree = new MemoryMappingTree(); 
                    try {
                        MappingReader.read(supplementaryMapping.getValue(), supplementaryMapping.getKey(), mappingTree);
                    } catch (IOException e) {
                        throw new IllegalStateException("Unable to consume supplementary mappings file at " + supplementaryMapping, e);
                    }
                    mappingTree.reset();

                    TopLevelMemberLookup definitionLookup = new SimpleTopLevelLookup(deobfuscator.getClassNodesDirectly());
                    ReadOnlyMIOMappingLookup directLookup = new ReadOnlyMIOMappingLookup(mappingTree, mappingTree.getMinNamespaceId(), mappingTree.getMaxNamespaceId() - 1);
                    HierarchyAwareMappingDelegator<ReadOnlyMIOMappingLookup> hierarchicalLookup = new HierarchyAwareMappingDelegator<>(directLookup, definitionLookup);
                    Remapper remapper = new Remapper(hierarchicalLookup);
                    StringBuilder sharedBuilder = new StringBuilder();
                    for (ClassNode node : deobfuscator.getClassNodesDirectly()) {
                        remapper.remapNode(node, sharedBuilder);
                    }
                }
            }

            deobfuscator.invalidateNameCaches();
            deobfuscator.applyInnerclasses();
            // TODO fix ICN names here

            LOGGER.info("Computed intermediaries of classes in " + (System.currentTimeMillis() - startIntermediarisation) + " ms.");

            if (rasInfo == null) {
                try (OutputStream os = Files.newOutputStream(compileAccess)) {
                    deobfuscator.write(os, cleanGalimJar);
                }
                // Compile-time Access = Runtime Access
                Files.copy(compileAccess, runAccess, StandardCopyOption.REPLACE_EXISTING);

                try {
                    Files.writeString(awHash, currentHash, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    LOGGER.warn("Cannot write aw hash; caching may not work correctly", e);
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

                try {
                    Files.writeString(awHash, currentHash, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    LOGGER.warn("Cannot write aw hash; caching may not work correctly", e);
                }

                // Write compile-time nodes to disk
                try (OutputStream os = Files.newOutputStream(compileAccess)) {
                    deobfuscator.write(os, cleanGalimJar);
                }

                // Write runtime nodes to disk
                try (ZipOutputStream os = new ZipOutputStream(Files.newOutputStream(runAccess), StandardCharsets.UTF_8)) {
                    // Copy resources
                    try (InputStream rawIn = Files.newInputStream(cleanGalimJar);
                            ZipInputStream zipIn = new ZipInputStream(rawIn)) {
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

    @Override
    public int hashCode() {
        return Objects.hash(this.rasContent, this.cacheDir, this.projectDir, this.softmapFiles);
    }

    public void reobfuscateJar(@NotNull Path jarPath, @NotNull Collection<@NotNull Path> alsoInclude) throws IOException {
        Path spstarmap = this.cacheDir.resolve(ObfuscationHandler.STARMAP_FILE_NAME);
        Path slintermediary = this.cacheDir.resolve(ObfuscationHandler.INTERMEDIARY_FILE_NAME);
        Path compiledSoftmap = this.cacheDir.resolve(ObfuscationHandler.COMPILED_SOFTMAP_FILE_NAME);

        List<@NotNull MappingLookup> lookups = new ArrayList<>();
        lookups.add(new StarplaneMappingLookup(compiledSoftmap, true, true).load());
        lookups.add(new StarplaneMappingLookup(spstarmap, true).load());
        lookups.add(new StarplaneMappingLookup(slintermediary, true).load());

        if (!this.supplementaryMappings.isEmpty()) {
            LOGGER.info("Loading supplementary mappings");
            ListIterator<Map.Entry<@NotNull MappingFormat, @NotNull Path>> it = this.supplementaryMappings.listIterator(0);

            while (it.hasNext()) {
                Map.Entry<@NotNull MappingFormat, @NotNull Path> supplementaryMapping = it.next();
                VisitableMappingTree mappingTree = new MemoryMappingTree(); 
                try {
                    MappingReader.read(supplementaryMapping.getValue(), supplementaryMapping.getKey(), mappingTree);
                } catch (IOException e) {
                    throw new IOException("Unable to consume supplementary mappings file at " + supplementaryMapping, e);
                }
                mappingTree.reset();
                lookups.add(0, new ReadOnlyMIOMappingLookup(mappingTree, mappingTree.getMaxNamespaceId() - 1, mappingTree.getMinNamespaceId()));
            }
        }

        Map<String, ClassNode> remapNodes = new HashMap<>();
        Map<String, byte[]> rawFiles = new HashMap<>();

        Set<@NotNull Path> allInputs = new HashSet<>(alsoInclude);
        allInputs.add(jarPath);

        for (Path p : allInputs) {
            try (InputStream is = Files.newInputStream(p); ZipInputStream zipIn = new ZipInputStream(is, StandardCharsets.UTF_8)) {
                ZipEntry e;
                while ((e = zipIn.getNextEntry()) != null) {
                    byte[] allData = zipIn.readAllBytes();
                    if (allData.length < 4
                            || allData[0] != (byte) 0xCA
                            || allData[1] != (byte) 0xFE
                            || allData[2] != (byte) 0xBA
                            || allData[3] != (byte) 0xBE
                            || !e.getName().endsWith(".class")) {
                        if (rawFiles.put(e.getName(), allData) != null) {
                            ObfuscationHandler.LOGGER.warn("Overwrote entry for raw file {}. The remapped jar may be malformed", e.getName());
                        }
                        continue;
                    }
                    try {
                        ClassReader reader = new ClassReader(allData);
                        ClassNode visitedNode = new ClassNode();
                        reader.accept(visitedNode, 0); // Do not skip anything (we will write the nodes as-is, including frames!)
                        remapNodes.put(e.getName(), visitedNode);
                    } catch (Exception ex) {
                        ObfuscationHandler.LOGGER.warn("Unable to read classfile {}; treating it as a regular file instead.", e.getName(), ex);
                        if (rawFiles.put(e.getName(), allData) != null) {
                            ObfuscationHandler.LOGGER.warn("Overwrote entry for raw file {}. The remapped jar may be malformed", e.getName());
                        }
                    }
                }
            } catch (IOException e) {
                throw new IOException("Unable to read input jar " + jarPath, e);
            }
        }

        Map<String, ClassNode> libraryNodes = new HashMap<>();
        try (InputStream is = Files.newInputStream(this.getTransformedGalimulatorJar());
                ZipInputStream zipIn = new ZipInputStream(is, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zipIn.getNextEntry()) != null) {
                byte[] allData = zipIn.readAllBytes();
                if (allData.length < 4
                        || allData[0] != (byte) 0xCA
                        || allData[1] != (byte) 0xFE
                        || allData[2] != (byte) 0xBA
                        || allData[3] != (byte) 0xBE
                        || !e.getName().endsWith(".class")) {
                    continue;
                }
                try {
                    ClassReader reader = new ClassReader(allData);
                    ClassNode visitedNode = new ClassNode();
                    reader.accept(visitedNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    if (libraryNodes.put(visitedNode.name, visitedNode) != null) {
                        ObfuscationHandler.LOGGER.warn("Collision for path {}, entry {}. Likely caused due to unexpected multi-release-jar", visitedNode.name, e.getName());
                    }
                } catch (Exception ex) {
                    ObfuscationHandler.LOGGER.warn("Unable to read library classfile {}; skipping it instead.", e.getName(), ex);
                }
            }
        }

        // multi-release jar version -> map of class name to ClassNode pairs.
        Map<Integer, Map<String, ClassNode>> mrjClasses = new HashMap<>();
        int maxMrjVersion = 8;

        for (Map.Entry<String, ClassNode> entry : remapNodes.entrySet()) {
            String path = entry.getKey();
            ClassNode node = entry.getValue();
            String fullpath = path;
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            int mrjVersion = 8;
            if (path.startsWith("META-INF/versions/")) {
                path = path.substring(18);
                mrjVersion = Integer.parseInt(path.substring(0, path.indexOf('/')));
                if (mrjVersion < 9) {
                    ObfuscationHandler.LOGGER.warn("Class {} of path {} would fit under the multi-release jar version of {} - which makes little sense as that would be before the introduction of multi-release jars.", node.name, fullpath, mrjVersion);
                    mrjVersion = 8;
                }
            }

            if (mrjVersion > maxMrjVersion) {
                maxMrjVersion = mrjVersion;
            }

            mrjClasses.computeIfAbsent(mrjVersion, (ignored) -> {
                return new HashMap<>();
            }).put(node.name, node);
        };

        try (OutputStream os = Files.newOutputStream(jarPath);
                ZipOutputStream zipOut = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            @SuppressWarnings("null")
            ChainMappingLookup externalLookups = new ChainMappingLookup(lookups.toArray(new @NotNull MappingLookup[0]));
            for (int mrjVersion = maxMrjVersion; mrjVersion >= 8; mrjVersion--) {
                Map<String, ClassNode> versionClasses = mrjClasses.remove(mrjVersion);
                if (versionClasses == null) {
                    continue;
                }
                List<ClassNode> mainClasses = new ArrayList<>(versionClasses.values());
                mainClasses.sort((n1, n2) -> n1.name.compareTo(n2.name));
                for (int earlierVersion = mrjVersion; earlierVersion >= 8; earlierVersion--) {
                    Map<String, ClassNode> availableEarlier = mrjClasses.get(earlierVersion);
                    if (availableEarlier == null) {
                        continue;
                    }
                    for (Map.Entry<String, ClassNode> earlierEntry : availableEarlier.entrySet()) {
                        versionClasses.putIfAbsent(earlierEntry.getKey(), earlierEntry.getValue());
                    }
                }

                List<ClassNode> allClasses = new ArrayList<>(libraryNodes.values());
                allClasses.addAll(mainClasses);
                SimpleTopLevelLookup allTopLevelLookup = new SimpleTopLevelLookup(allClasses);
                DebugableMemberLister libraryMemberLister = new DebugableMemberLister(allTopLevelLookup, libraryNodes);

                @SuppressWarnings("null")
                SimpleHierarchyAwareMappingLookup mixinLookup = new SimpleHierarchyAwareMappingLookup(new ArrayList<>(versionClasses.values()));
                ReadOnlyMappingLookupSink readOnlyExternalLookups = new ReadOnlyMappingLookupSink(externalLookups);
                MappingLookup externalHierarchyLookup = new HierarchyAwareMappingDelegator<>(readOnlyExternalLookups, allTopLevelLookup);
                ChainMappingLookup allLookup = new ChainMappingLookup(mixinLookup, externalHierarchyLookup);
                MicromixinRemapper mixinRemapper = new MicromixinRemapper(allLookup, mixinLookup, libraryMemberLister);
                Remapper coreRemaper = new Remapper(allLookup);

                StringBuilder sharedBuilder = new StringBuilder();
                for (ClassNode mainNode : mainClasses) {
                    mainNode = Objects.requireNonNull(mainNode);

//                    if (mainNode.name.equals("de/geolykt/fastgalgen/mixins/QuadTreeMixins")) {
//                        allLookup.enableDebugMode(true);
//                        externalLookups.enableDebugMode(true);
//                        libraryMemberLister.setDebugging(true);
//                    }

                    StarplaneAnnotationRemapper.apply(mainNode, coreRemaper, sharedBuilder);
                    try {
                        mixinRemapper.remapClass(mainNode);
                    } catch (IllegalMixinException | MissingFeatureException e) {
                        throw new IOException("Unable to remap due to a problem which occured while remapping mixin " + mainNode.name + " in multi-release-jar sourceset " + mrjVersion, e);
                    }

                    coreRemaper.remapNode(mainNode, sharedBuilder);

//                    if (mainNode.name.equals("de/geolykt/fastgalgen/mixins/QuadTreeMixins")) {
//                        allLookup.enableDebugMode(false);
//                        externalLookups.enableDebugMode(false);
//                        libraryMemberLister.setDebugging(false);
//                    }

                    ClassWriter writer = new ClassWriter(0);
                    mainNode.accept(writer);
                    if (mrjVersion != 8) {
                        zipOut.putNextEntry(new ZipEntry("META-INF/versions/" + mrjVersion + "/" + mainNode.name + ".class"));
                    } else {
                        zipOut.putNextEntry(new ZipEntry(mainNode.name + ".class"));
                    }
                    zipOut.write(writer.toByteArray());
                }

                if (mrjVersion == 8) {
                    for (Map.Entry<String, byte[]> resource : rawFiles.entrySet()) {
                        zipOut.putNextEntry(new ZipEntry(resource.getKey()));
                        byte[] data = resource.getValue();
                        if (resource.getKey().toLowerCase(Locale.ROOT).endsWith(".ras")) {
                            data = new RASRemapper(allLookup, sharedBuilder).transform(data, "jar://?!" + resource.getKey());
                        }
                        zipOut.write(data);
                    }
                }
            }
        }

        ObfuscationHandler.LOGGER.info("Reobfuscating done");
    }
}
