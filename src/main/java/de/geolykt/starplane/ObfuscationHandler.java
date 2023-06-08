package de.geolykt.starplane;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.gradle.internal.impldep.com.google.common.base.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

import de.geolykt.starloader.ras.ReversibleAccessSetterContext;
import de.geolykt.starloader.ras.ReversibleAccessSetterContext.RASTransformFailure;
import de.geolykt.starloader.ras.ReversibleAccessSetterContext.RASTransformScope;
import de.geolykt.starplane.remapping.StarplaneAnnotationRemapper;

public class ObfuscationHandler {

    private static final byte[] IO_BUFFER = new byte[4096];
    private static final Logger LOGGER = LoggerFactory.getLogger(ObfuscationHandler.class);
    @Nullable
    private final String rasContent;
    @NotNull
    private final Path cacheDir;
    public boolean didRefresh = false;
    @NotNull
    private final Path projectDir;
    @NotNull
    private final String gameName;
    @NotNull
    private final String expectedJarPath;
    @NotNull
    private final List<@NotNull String> includes;

    public ObfuscationHandler(@NotNull Path cacheDir, @NotNull Path projectDir, @NotNull String gameName, @NotNull String jarPath, @Nullable String rasContent, @NotNull List<@NotNull String> includes) {
        this.cacheDir = cacheDir;
        this.projectDir = projectDir;
        this.gameName = gameName;
        this.expectedJarPath = jarPath;
        this.rasContent = rasContent;
        this.includes = includes;
    }

    @NotNull
    public Path getRuntimeRemappedJar() {
        getTransformedGalimulatorJar();
        return this.cacheDir.resolve(this.gameName.toLowerCase(Locale.ROOT) + "-remapped-rt.jar");
    }

    @NotNull
    public String getGameName() {
        return this.gameName;
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
            currentHash = currentHash + '-' + getStarplaneChecksum();
        } catch (IOException e) {
            e.printStackTrace();
        }

        List<@NotNull Path> cleanGameJars = new ArrayList<>();
        Set<@NotNull String> remainingJars = new LinkedHashSet<>();
        remainingJars.add(this.expectedJarPath);
        remainingJars.addAll(this.includes);
        int extraHash = 0;
        for (String jar : remainingJars) {
            Path cleanJar = this.projectDir.resolve(jar);
            if (Files.notExists(cleanJar)) {
                LOGGER.info("Include jar at " + cleanJar.toAbsolutePath() + " not found.");
                cleanJar = this.cacheDir.resolve(jar);
                if (Files.notExists(cleanJar)) {
                    LOGGER.info("Include jar at " + cleanJar.toAbsolutePath() + " not found.");
                    cleanJar = this.projectDir.resolve(jar.substring(jar.lastIndexOf('/') + 1));
                    if (jar.codePointBefore(jar.length()) == '/' || Files.notExists(cleanJar)) {
                        LOGGER.info("Include jar at " + cleanJar.toAbsolutePath() + " not found.");
                        File gameDir = Utils.getGameDir(this.gameName);
                        if (gameDir == null || !gameDir.exists()) {
                            LOGGER.error("Unable to resolve game directory!");
                            throw new IllegalStateException("Cannot resolve dependency include " + jar);
                        }
                        cleanJar = gameDir.toPath().resolve(jar);
                        if (Files.notExists(cleanJar)) {
                            LOGGER.error(
                                    "Unable to resolve include jar file {} (was able to resolve the potential directory at '{}' though)!", jar, gameDir);
                            throw new IllegalStateException("Cannot resolve dependencies");
                        }
                    }
                }
            }

            LOGGER.info("Include jar found at {}", cleanJar.toAbsolutePath());

            if (jar.codePointBefore(jar.length()) == '/') {
                try {
                    Path[] jars = Files.list(cleanJar.toAbsolutePath()).toArray(Path[]::new);
                    for (Path subdirJar : jars) {
                        extraHash ^= subdirJar.toAbsolutePath().hashCode();
                        try {
                            extraHash ^= Files.getLastModifiedTime(subdirJar).toMillis();
                        } catch (Exception ignore) {
                            // NOP
                        }
                        cleanGameJars.add(subdirJar.toAbsolutePath());
                    }
                } catch (IOException e) {
                    LOGGER.error("Unable to resolve include jar file {} as it was not possible to list sub-directories!", jar, e);
                    throw new IllegalStateException("Cannot resolve dependencies", e);
                }
            } else {
                extraHash ^= cleanJar.toAbsolutePath().hashCode();
                try {
                    extraHash ^= Files.getLastModifiedTime(cleanJar).toMillis();
                } catch (Exception ignore) {
                    // NOP
                }
                cleanGameJars.add(cleanJar.toAbsolutePath());
            }
        }

        LOGGER.info("Using the base game jar(s) found at {}", cleanGameJars);
        currentHash += "-" + Integer.toHexString(extraHash);

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
        } else if (rasInfo != null) {
            recomputeAw = true; // AW file newly created
        }

        final Path runAccess = this.cacheDir.resolve(this.gameName.toLowerCase(Locale.ROOT) + "-remapped-rt.jar");
        final Path compileAccess = this.cacheDir.resolve(this.gameName.toLowerCase(Locale.ROOT) + "game-remapped.jar");

        if (Files.isRegularFile(runAccess) && Files.isRegularFile(compileAccess) && !Boolean.getBoolean("de.geolykt.starplane.nocache") && !recomputeAw) {
            return compileAccess;
        }

        didRefresh = true;

        try {
            long start = System.currentTimeMillis();

            try (ZipOutputStream ros = new ZipOutputStream(Files.newOutputStream(runAccess), StandardCharsets.UTF_8);
                    ZipOutputStream cos = new ZipOutputStream(Files.newOutputStream(compileAccess), StandardCharsets.UTF_8)) {
                Set<String> copiedEntries = new HashSet<>();
                for (Path inJar : cleanGameJars) {
                    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(inJar), StandardCharsets.UTF_8)) {
                        for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                            if (!copiedEntries.add(entry.getName())) {
                                continue;
                            }
                            ZipEntry clone = new ZipEntry(entry);
                            clone.setCompressedSize(-1); // Different compression algos may yield different compressed entry sizes
                            ros.putNextEntry(clone);
                            cos.putNextEntry(new ZipEntry(clone));
                            if (entry.getName().endsWith(".class")) {
                                ClassReader reader = new ClassReader(in);
                                ClassNode node = new ClassNode();
                                reader.accept(node, 0);
                                // Write runtime access jar (i.e. no RAS transforms, those will get applied by SLL)
                                ClassWriter writer = new ClassWriter(reader, 0);
                                node.accept(writer);
                                ros.write(writer.toByteArray());
                                // Write compile access jar
                                writer = new ClassWriter(reader, 0);
                                if (rasInfo != null) {
                                    try {
                                        rasInfo.accept(node);
                                    } catch (RASTransformFailure e) {
                                        LOGGER.error("Unable to apply RAS transform for node {}", node.name, e);
                                    }
                                }
                                node.accept(writer);
                                cos.write(writer.toByteArray());
                            } else {
                                byte[] b = new byte[4096];
                                int read;
                                while ((read = in.read(b)) != -1) {
                                    ros.write(b, 0, read);
                                    cos.write(b, 0, read);
                                }
                            }
                        }
                    }
                }
            }

            try (BufferedWriter bw = Files.newBufferedWriter(awHash, StandardCharsets.UTF_8)) {
                bw.write(currentHash);
            } catch (IOException e) {
                e.printStackTrace();
            }

            LOGGER.info("Finished transforming classes in " + (System.currentTimeMillis() - start) + " ms.");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return compileAccess;
    }

    private static String getStarplaneChecksum() throws IOException {
        try (InputStream autodeobfClass = ObfuscationHandler.class.getClassLoader().getResourceAsStream("de/geolykt/starplane/ObfuscationHandler.class");
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

    public void reobfuscateJar(@NotNull Path jarPath, @NotNull Path mappedGameJar,
            @NotNull Collection<@NotNull Path> alsoInclude) throws IOException {

        TinyRemapper tinyRemapper = TinyRemapper.newRemapper()
                .extension(new StarplaneAnnotationRemapper())
                .extension(new MixinExtension())
                .keepInputData(true)
                .build();

        Path intermediaryBuild = this.cacheDir.resolve("temporaryBuild.jar");
        try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(intermediaryBuild).build()) {
            LOGGER.info("Reobfuscating... (reading inputs)");
            tinyRemapper.readInputs(jarPath);
            tinyRemapper.readClassPath(mappedGameJar);
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

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ObfuscationHandler) {
            ObfuscationHandler other = (ObfuscationHandler) obj;
            return Objects.equal(this.rasContent, other.rasContent)
                    && this.cacheDir.equals(other.cacheDir)
                    && this.projectDir.equals(other.projectDir);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.rasContent, this.cacheDir, this.projectDir);
    }
}