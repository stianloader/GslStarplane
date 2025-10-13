package de.geolykt.starplane.remapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;

import org.jetbrains.annotations.NotNull;

import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitableMappingTree;

import de.geolykt.starplane.remapping.MIOContainerFormat.MappingContainer;

public class MIOMappingTreeProvider {
    @NotNull
    private final MIOContainerFormat format;
    @NotNull
    private final Path path;

    public MIOMappingTreeProvider(@NotNull MappingFormat format, @NotNull Path path) {
        this.format = new MIOContainerFormat(format, MappingContainer.PLAIN);
        this.path = path;
    }

    public MIOMappingTreeProvider(@NotNull MIOContainerFormat format, @NotNull Path path) {
        this.format = format;
        this.path = path;
    }

    @NotNull
    public VisitableMappingTree get() throws IOException {
        VisitableMappingTree tree = new MemoryMappingTree();
        try {
            this.format.read(this.path, tree);
        } catch (IOException e) {
            throw new IOException("Unable to consume supplementary mappings file at " + this.path + " using format " + this.format.toString(), e);
        }
        tree.reset();
        return tree;
    }

    public void checksum(@NotNull Checksum csum, byte @NotNull[] exhaustBuffer) throws IOException {
        csum.update(this.format.containerFormat.ordinal());
        csum.update(this.format.coreFormat.ordinal());
        try (CheckedInputStream cis = new CheckedInputStream(Files.newInputStream(this.path), csum)) {
            while (cis.read(exhaustBuffer) != -1); // Discard all read bytes
        }
    }
}
