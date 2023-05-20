package de.geolykt.starplane;

import java.nio.file.Path;
import java.util.Locale;

import net.fabricmc.tinyremapper.TinyRemapper;

import de.geolykt.starloader.ras.AbstractTinyRASRemapper;

class SimpleRASRemapper extends AbstractTinyRASRemapper {

    public static final SimpleRASRemapper INSTANCE = new SimpleRASRemapper();

    private SimpleRASRemapper() { }

    @Override
    public boolean canTransform(TinyRemapper remapper, Path relativePath) {
        return relativePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ras");
    }
}
