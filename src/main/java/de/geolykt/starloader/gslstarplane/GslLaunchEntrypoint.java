package de.geolykt.starloader.gslstarplane;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;

import de.geolykt.starplane.slruntime.StarplaneStarloaderEntrypoint;

public class GslLaunchEntrypoint {

    
    public static void main(String[] args) {
        try {
            // Gradle can be rather ... difficult to work with.
            // So we just bypass their artifact transform shenanigans and do it ourselves.
            MethodHandle getProperty = MethodHandles.publicLookup().findStatic(System.class, "getProperty", MethodType.methodType(String.class, String.class));
            StarplaneStarloaderEntrypoint.main(Path.of((String) getProperty.invokeExact("gslstarplane.galimulator")), Path.of((String) getProperty.invokeExact("gslstarplane.extensiondir")), args);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
