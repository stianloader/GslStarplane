package de.geolykt.starloader.gslstarplane;

import java.io.IOException;

import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.work.DisableCachingByDefault;

import de.geolykt.starplane.ObfuscationHandler;

@DisableCachingByDefault(because = "Not worth caching") // Gradle does this with the standard Jar task. But is this really the case?
public class GslRemapTask extends Jar {

    @Override
    protected CopyAction createCopyAction() {
        return (CopyActionProcessingStream caps) -> {
            {
                // Build the base jar
                WorkResult result = GslRemapTask.super.createCopyAction().execute(caps);
                if (!result.getDidWork()) {
                    return result;
                }
            }
            ObfuscationHandler oHandler = GslStarplanePlugin.OBF_HANDLERS.get(super.getProject());
            if (oHandler == null) {
                super.getLogger().error("Obfuscation handler not set for this project: " + super.getProject().getName());
                return WorkResults.didWork(false);
            }

            try {
                super.getLogger().info("Remapping...");
                oHandler.reobfuscateJar(super.getArchiveFile().get().getAsFile().toPath(), oHandler.getTransformedGalimulatorJar());
                super.getLogger().info("Remap complete.");
            } catch (IOException e) {
                super.getLogger().error("Unable to remap", e);
                return WorkResults.didWork(false);
            }

            return WorkResults.didWork(true);
        };
    }
}
