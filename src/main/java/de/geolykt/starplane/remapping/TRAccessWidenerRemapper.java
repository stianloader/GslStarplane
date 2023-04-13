package de.geolykt.starplane.remapping;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;

import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.api.TrRemapper;

import de.geolykt.starloader.deobf.access.AccessFlagModifier;
import de.geolykt.starplane.Utils;

public class TRAccessWidenerRemapper implements OutputConsumerPath.ResourceRemapper {

    @Override
    public boolean canTransform(TinyRemapper remapper, Path relativePath) {
        return relativePath.getFileName().toString().endsWith(".accesswidener");
    }

    public void remapAccesswidener(@NotNull BufferedReader br, BufferedWriter bw, TinyRemapper tinyRemapper) throws IOException {
        TrRemapper remapper = tinyRemapper.getEnvironment().getRemapper();
        while (true) {
            String ln = br.readLine();
            if (ln == null) {
                break;
            }
            int indexOfCommentSymbol = ln.indexOf('#');
            @NotNull
            String pureLine = indexOfCommentSymbol == -1 ? ln : ln.substring(0, indexOfCommentSymbol);
            if (Utils.isBlank(pureLine) || pureLine.toLowerCase(Locale.ROOT).startsWith("accesswidener")) {
                bw.write(ln);
                bw.newLine();
                continue;
            }
            String[] blocks = pureLine.trim().split("\\s+");

            boolean compileOnly = false;
            if (blocks.length != 0 && (compileOnly = blocks[0].equalsIgnoreCase("compileOnly"))) {
                String[] copy = new String[blocks.length - 1];
                System.arraycopy(blocks, 1, copy, 0, copy.length);
                blocks = copy;
            }

            if (blocks.length != 3 && blocks.length != 5) {
                throw new IOException("Illegal block count. Got " + blocks.length + " expected 3 or 5. Line: " + pureLine);
            }

            String targetClass = blocks[2].replace('.', '/');
            String operation = blocks[0];
            String typeName = blocks[1];

            Optional<String> name;
            Optional<String> desc;
            AccessFlagModifier.Type memberType = null;
            switch (typeName.toLowerCase(Locale.ROOT)) {
            case "class":
                if (blocks.length != 3) {
                    throw new IOException("Illegal block count. Got " + blocks.length
                            + " but expected 3 due to the CLASS modifier. Line: " + pureLine);
                }
                memberType = AccessFlagModifier.Type.CLASS;
                name = Optional.empty();
                desc = Optional.empty();
                break;
            case "field":
                memberType = AccessFlagModifier.Type.FIELD;
                // Fall-through intended
            case "method":
                if (blocks.length != 5) {
                    throw new IOException("Illegal block count. Got " + blocks.length
                            + " but expected 5 due to the METHOD or FIELD modifier. Line: " + pureLine);
                }
                if (memberType == null) {
                    memberType = AccessFlagModifier.Type.METHOD;
                    desc = Optional.of(remapper.mapMethodDesc(blocks[4]));
                    name = Optional.of(remapper.mapMethodName(targetClass, blocks[3], blocks[4]));
                } else {
                    desc = Optional.of(remapper.mapDesc(blocks[4]));
                    name = Optional.of(remapper.mapFieldName(remapper.map(targetClass), blocks[3], blocks[4]));
//                    System.out.println(targetClass + "." + blocks[3] + " " + blocks[4]);
//                    System.out.println(targetClass + "." + name.get() + " " + desc.get());
                }
                break;
            default:
                throw new IOException();
            }
            targetClass = remapper.map(targetClass);

            AccessFlagModifier modifier;

            switch (operation.toLowerCase(Locale.ROOT)) {
            case "accessible":
                modifier = new AccessFlagModifier.AccessibleModifier(memberType, targetClass, name, desc, compileOnly);
                break;
            case "extendable":
                modifier = new AccessFlagModifier.ExtendableModifier(memberType, targetClass, name, desc, compileOnly);
                break;
            case "mutable":
                modifier = new AccessFlagModifier.RemoveFlagModifier(memberType, targetClass, name, desc, Opcodes.ACC_FINAL, "mutable", compileOnly);
                break;
            case "natural":
                modifier = new AccessFlagModifier.RemoveFlagModifier(memberType, targetClass, name, desc, Opcodes.ACC_SYNTHETIC, "natural", compileOnly);
                break;
            case "denumerised":
                modifier = new AccessFlagModifier.RemoveFlagModifier(memberType, targetClass, name, desc, Opcodes.ACC_ENUM, "denumerised", compileOnly);
                break;
            default:
                throw new UnsupportedOperationException("Unknown mode: " + operation);
            }

            if (compileOnly) {
                bw.write("#compileOnly " + modifier.toAccessWidenerString());
                bw.newLine();
            } else {
                bw.write(modifier.toAccessWidenerString());
                bw.newLine();
            }
        }
        br.close();
        bw.close();
    }

    @Override
    public void transform(Path destinationDirectory, Path relativePath, InputStream input, TinyRemapper remapper)
            throws IOException {
        if (relativePath == null) {
            throw new NullPointerException("relativePath was null");
        }
        Path outputFile = destinationDirectory.resolve(relativePath);
        Path outputDir = outputFile.getParent();
        if (outputDir != null) {
            Files.createDirectories(outputDir);
        }

        try (BufferedWriter out = Files.newBufferedWriter(outputFile)) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(input))) {
                remapAccesswidener(in, out, remapper);
            }
        }
    }
}
