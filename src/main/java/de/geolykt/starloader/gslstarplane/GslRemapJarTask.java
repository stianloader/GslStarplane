package de.geolykt.starloader.gslstarplane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.geolykt.starplane.ObfuscationHandler;

@DisableCachingByDefault(because = "Not worth caching") // Gradle does this with the standard Jar task. But is this really the case?
public class GslRemapJarTask extends Jar {

    private static final Logger LOGGER = LoggerFactory.getLogger(GslRemapJarTask.class);
    private final Set<Object> fromJars = new HashSet<>();

    public void fromJar(Object... notations) {
        for (Object notation : notations) {
            if (notation instanceof Task) {
                super.dependsOn(notation);
            }
            this.fromJars.add(notation);
            this.getInputs().files(notation);
        }
    }

    @Override
    protected CopyAction createCopyAction() {
        return (CopyActionProcessingStream caps) -> {
            {
                // Build the base jar
                WorkResult result = super.createCopyAction().execute(caps);
                if (!result.getDidWork()) {
                    return result;
                }
            }
            ObfuscationHandler oHandler = GslStarplanePlugin.OBF_HANDLERS.get(super.getProject());
            if (oHandler == null) {
                throw new IllegalStateException("Obfuscation handler not set for this project: " + super.getProject().getName());
            }

            Set<@NotNull Path> includes = new HashSet<>();
            for (Object fromJar : this.fromJars) {
                if (fromJar instanceof Task) {
                    FileCollection taskOutputs = ((Task) fromJar).getOutputs().getFiles();
                    taskOutputs.forEach(f -> {
                        includes.add(f.toPath());
                    });
                } else {
                    includes.add(super.getProject().file(fromJar).toPath());
                }
            }

            try {
                LOGGER.info("Remapping");
                oHandler.reobfuscateJar(super.getArchiveFile().get().getAsFile().toPath(), oHandler.getTransformedGalimulatorJar(), includes);
                LOGGER.info("Remap complete");
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to remap", e);
            }

            return WorkResults.didWork(true);
        };
    }
}
