package de.geolykt.starloader.gslstarplane;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;

import de.geolykt.starplane.Utils;
import de.geolykt.starplane.XmlWriter;

@CacheableTask
public class GslGenEclipseRunsTask extends DefaultTask {

    private final File runModLaunchFile;

    public GslGenEclipseRunsTask() {
        super.dependsOn("deployMods");
        this.runModLaunchFile = super.getProject().file("runMods.launch");
    }

    @OutputFile // Required in order for caching to work
    public File getRunModLaunchFile() {
        return this.runModLaunchFile;
    }

    private static void writeLine(@NotNull BufferedWriter writer, @NotNull String string) throws IOException {
        writer.write(string);
        writer.newLine();
    }

    @TaskAction
    public void genRuns() {
        try (XmlWriter writer = new XmlWriter(new FileWriter(runModLaunchFile, StandardCharsets.UTF_8))) {
            writeLine(writer, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>");
            writeLine(writer, "<launchConfiguration type=\"org.eclipse.jdt.launching.localJavaApplication\">");
            writer.indent();

            String jvmVersion = "17";
            Path workingDir = getProject().getProjectDir().toPath();
            List<String> jvmArgs = new ArrayList<>();
            JavaExec jExecTask = (JavaExec) getProject().getTasks().findByName("runMods");
            if (jExecTask != null) {
                if (jExecTask.getJavaVersion().isJava9Compatible()) {
                    jvmVersion = jExecTask.getJavaVersion().getMajorVersion();
                } else {
                    jvmVersion = "1." + jExecTask.getJavaVersion().getMajorVersion();
                }
                workingDir = jExecTask.getWorkingDir().toPath();
                jvmArgs.addAll(jExecTask.getAllJvmArgs());
                jvmArgs.add(GslStarplanePlugin.getBootPath(super.getProject()));
            }
            jvmArgs.add("-Dde.geolykt.starloader.launcher.IDELauncher.modURLs=" + getModURLs().toString());
            Path dataFolder = workingDir.resolve("data");

            List<String> classpathElements = new ArrayList<>();

            // resolve data folder
            if (Files.notExists(dataFolder)) {
                File gameFolder = Utils.getGameDir(Utils.STEAM_GALIMULATOR_APPNAME);
                Path galimDataFolder;
                if (gameFolder == null || Files.notExists(galimDataFolder = gameFolder.toPath().resolve("data"))) {
                    getLogger().error("Couldn't locate data folder. You might need to copy the data folder manually in order to be able to run this task");
                } else {
                    try {
                        Files.createSymbolicLink(dataFolder, galimDataFolder);
                    } catch (IOException e) {
                        getLogger().error("Cannot link data folder. You might need to copy the data folder manually in order to be able to run this task", e);
                    }
                }
            }

            // Project source sets
            classpathElements.add("&lt;?xml version=&quot;1.0&quot; encoding=&quot;UTF-8&quot; standalone=&quot;no&quot;?&gt;&#10;&lt;runtimeClasspathEntry path=&quot;5&quot; projectName=&quot;" + getProject().getName() + "&quot; type=&quot;1&quot;/&gt;&#10;");
            // Gradle classpath
            classpathElements.add("&lt;?xml version=&quot;1.0&quot; encoding=&quot;UTF-8&quot; standalone=&quot;no&quot;?&gt;&#10;&lt;runtimeClasspathEntry containerPath=&quot;org.eclipse.buildship.core.gradleclasspathcontainer&quot; javaProject=&quot;" + getProject().getName() + "&quot; path=&quot;5&quot; type=&quot;4&quot;/&gt;&#10;");
            // JVM
            classpathElements.add("&lt;?xml version=&quot;1.0&quot; encoding=&quot;UTF-8&quot; standalone=&quot;no&quot;?&gt;&#10;&lt;runtimeClasspathEntry containerPath=&quot;org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-" + jvmVersion + "/&quot; path=&quot;5&quot; type=&quot;4&quot;/&gt;&#10;");
            writer.writeListAttr("org.eclipse.jdt.launching.CLASSPATH", classpathElements);
            writer.writeStringAttr("org.eclipse.jdt.launching.MAIN_TYPE", "de.geolykt.starloader.launcher.IDELauncher");
            writer.writeListAttr("org.eclipse.jdt.launching.MODULEPATH", Collections.emptyList());
            writer.writeStringAttr("org.eclipse.jdt.launching.MODULE_NAME", getProject().getName());
            writer.writeStringAttr("org.eclipse.jdt.launching.PROJECT_ATTR", getProject().getName());
            writer.writeStringAttr("org.eclipse.jdt.launching.WORKING_DIRECTORY", workingDir.toAbsolutePath().toString());
            writer.writeStringAttr("org.eclipse.jdt.launching.VM_ARGUMENTS", String.join(" ", jvmArgs));
            writer.unindent();
            writer.write("</launchConfiguration>");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JSONArray getModURLs() {
        JSONArray urls = new JSONArray();
        SourceSetContainer sourceSets = (SourceSetContainer) Objects.requireNonNull(super.getProject().getProperties().get("sourceSets"));
        for (SourceSet sourceSet : sourceSets) {
            if (sourceSet.getName().equals("test")) {
                continue;
            }
            try {
                urls.put(getProject().file("bin/" + sourceSet.getName()).toURI().toURL().toExternalForm());
            } catch (MalformedURLException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new JSONArray().put(urls);
    }
}
