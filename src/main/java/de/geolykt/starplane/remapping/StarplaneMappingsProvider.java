package de.geolykt.starplane.remapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.jetbrains.annotations.NotNull;

import net.fabricmc.tinyremapper.IMappingProvider;

import de.geolykt.starplane.Utils;

public class StarplaneMappingsProvider implements IMappingProvider {

    @NotNull
    private final Path map;

    private final boolean reverse;

    public StarplaneMappingsProvider(@NotNull Path map, boolean reversed) {
        this.map = map;
        this.reverse = reversed;
    }

    @Override
    public void load(MappingAcceptor out) {
        int lineNr = 0;
        try (BufferedReader br = Files.newBufferedReader(map, StandardCharsets.UTF_8)) {
            // the first line must specify the version of tiny and the namespace.
            // we are going to ignore the namespace as they just produce too much headache
            String header = br.readLine();
            lineNr++;
            if (header == null || Utils.isBlank(header)) {
                br.close();
                throw new IOException("No tiny header present (empty file?).");
            }
            String[] headerTokens = header.split("\\s+");
            if (headerTokens.length != 3) {
                br.close();
                throw new IOException("The tiny header had " + headerTokens.length + " tokens, however it is expected to be exactly 3.");
            }
            if (!headerTokens[0].equals("v1")) {
                br.close();
                throw new IOException("This method can only read tiny v1 maps.");
            }
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                lineNr++;
                if (line.charAt(0) == '#') { // fast short-circuiting
                    continue;
                }
                line = line.split("#", 2)[0];
                if (line == null || Utils.isBlank(line)) {
                    continue;
                }
                String[] colums = line.split("\\s+");
                String type = colums[0].toUpperCase(Locale.ROOT);
                if (type.equals("CLASS")) {
                    // Format: CLASS originalName newName
                    if (colums.length != 3) {
                        throw new IOException("Line " + lineNr + " is of type CLASS, but only " + colums.length + " colums are present, even though it expects 3.");
                    }
                    if (this.reverse) {
                        out.acceptClass(colums[2], colums[1]);
                    } else {
                        out.acceptClass(colums[1], colums[2]);
                    }
                } else if (type.equals("METHOD")) {
                    // Format: METHOD owner originalName descriptor newName
                    //                11111 222222222222 3333333333 4444444
                    if (colums.length != 5) {
                        throw new IOException("Line " + lineNr + " is of type CLASS, but only " + colums.length + " colums are present, even though it expects 5.");
                    }
                    if (this.reverse) {
                        out.acceptMethod(new Member(colums[1], colums[4], colums[3]), colums[2]);
                    } else {
                        out.acceptMethod(new Member(colums[1], colums[2], colums[3]), colums[4]);
                    }
                } else if (type.equals("FIELD")) {
                    // Format: FIELD owner descriptor originalName newName
                    //               11111 2222222222 333333333333 4444444
                    if (colums.length != 5) {
                        throw new IOException("Line " + lineNr + " is of type CLASS, but only " + colums.length + " colums are present, even though it expects 5.");
                    }
                    if (this.reverse) {
                        out.acceptField(new Member(colums[1], colums[4], colums[2]), colums[3]);
                    } else {
                        out.acceptField(new Member(colums[1], colums[3], colums[2]), colums[4]);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot read mappings!", e);
        }
    }
}
