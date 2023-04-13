package de.geolykt.starplane.slruntime;

import java.nio.file.Path;

import de.geolykt.starloader.launcher.LauncherConfiguration;
import de.geolykt.starloader.launcher.Utils;
import de.geolykt.starloader.mod.ExtensionPrototypeList;

/**
 * Entrypoint for Starloader-related tasks. This basically starts starloader by bypassing the GUI and loading
 * all extensions that are in the specified extensions directory.
 *
 * @author Geolykt
 */
public class StarplaneStarloaderEntrypoint {

    public static void main(Path galimJar, Path extensionsDirectory, String[] args) throws Exception {
        System.out.println("Starting SLL. Extension folder " + extensionsDirectory + ", using galimulator jar " + galimJar + ".");
        LauncherConfiguration cfg = new LauncherConfiguration(true);
        cfg.setExtensionsEnabled(true);
        cfg.setExtensionsFolder(extensionsDirectory.toFile());
        cfg.setTargetJar(galimJar.toFile());
        cfg.setExtensionList(getExtensionList(extensionsDirectory));

        System.out.println("Starting galimulator");
        Utils.startGalimulator(args, cfg);
        while (true) {
            System.out.println("Awaiting system exit. If this never happens you may need to manually shut this down via Ctrl + C which kills this task.");
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    public static ExtensionPrototypeList getExtensionList(Path extensionsDirectory) {
        ExtensionPrototypeList prototypeList = new ExtensionPrototypeList(extensionsDirectory.toFile());
        prototypeList.getPrototypes().forEach(prototype -> prototype.enabled = true);
        System.out.println("Using following prototypes:");
        prototypeList.getPrototypes().forEach((prototype) -> {
            System.out.println("- " + prototype.name + " v" + prototype.version + " (loaded from " + prototype.origin + ")");
        });
        return prototypeList;
    }
}
