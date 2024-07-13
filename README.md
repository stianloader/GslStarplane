# GslStarplane: Starplane for Gradle

GslStarplane is the gradle port of the starplane buildsystem originally written
for use in combination with Brachyura. Starplane lays the foundation of modern
galimulator modding.

## Including the plugin

**WARNING:** When publishing your code online with this plugin enabled, you
should make extra sure to NOT include the `build` directory. GslStarplane
pushes deobfuscated jars which have a copyright on them to it.

GslStarplane functions as a gradle plugin, so it first needs to be applied on a
gradle project in order to work. Under groovy you can do it by adding
`id 'gsl-starplane' version '0.2.0-a20240504'` to the plugins block in the `build.gradle`
file. The full plugins block will thus look something like follows:

```groovy
plugins {
    id 'java'
    id 'java-library'
    id 'maven-publish'
    // Note: When debugging self-compiled versions of gsl-starplane you should leave out
    // the automatically generated "-aYYYYMMDD" tag.
    id 'gsl-starplane' version '0.2.0-a20240504'
}
```

However, as GslStarplane is not located in the default repositories and
depends on external projects, you also need to add a few maven repos to the
pluginManagement block of the `settings.gradle` file. In full the additions
probably look like that:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
//      mavenLocal()   // Uncomment this line if you wish to debug the plugin
        mavenCentral()
        maven {
            name = 'stianloader-maven'
            url = 'https://stianloader.org/maven/'
        }
    }
}
```

And that is it! Unfortunately you won't be able to use the plugin for much
with such a setup. So continue reading!

## Specifying reversible access setters

**WARNING:** Reversible access setters are as dangerous as they are simple to
use. Changing access of a method could make methods that normally would be
independent of each other to override each other. For mutating fields,
an accessor mixin should be used with `@Mutable`. For methods, invokers
should be used (not yet supported in micromixin).

Reversible access setters can optionally be declared in the `build.gradle`
as follows:

```groovy
starplane {
    withRAS(rootProject.file("src/main/resources/starloader-api.ras"))
}
```

Starplane will automatically apply the contents of the reversible access
setter on the galimulator jar and will remap all \*.ras file during the
`remap` task.

At the moment, only one reversible access setter can be set at a time
and transitive reversible access setters are ignored.

**Declaration of reversible access setters to starplane is independent
from the Starloader-launcher declaration of reversible access setters!**

## The `galimulatorDependencies` configuration

Starplane automatically strips dependencies it can find from the galimulator
jar. This may sound unintuitive but has the benefit of reducing the amount
of classes that need to be decompiled and also provides (if configured
properly) javadocs and sources in the IDE, leading to a more comfortable
development experience.

The dependencies that need to be stripped can be configured through the
`galimulatorDependencies` configuration, however it is advised that the
configuration is not touched. However, by default dependencies which are in the
`galimulatorDependencies` configuration are not on the compile classpath.
To change this, `compileOnlyApi` can be made to extend from
`galimulatorDependencies`. In practice this can be done by inserting

```groovy
configurations {
    compileOnlyApi.extendsFrom(galimulatorDependencies)
}
```

in the project buildscript.

## The `remapJar` task

**WARNING**: The 0.2.X branch uses the more experimental stianloader-remapper
and micromixin-remapper frameworks. Unlike the tiny-remapper used by the 0.1.X
releases they haven't been tested quite a lot and may especially be vulnerable
to failing to remap mixins or accounting for the member inheritance hierarchy.

The `remapJar` task remaps all references of deobfuscated members to use
the obfuscated names instead. It produces the jar that can be used outside
the development environment and which you can freely distribute
(should there not be other limitations).

The `remapJar` task extends the `jar` task, which means that it can
be configured similar to `jar`. However, unlike the `jar` task
you probably want to define the `from` inputs.

Note: It is recommended to set the `archiveClassifier` of the
remapped jar as otherwise the `jar` task cannot be cached.

Including a jar will insert the jar in the built jar root.
This is probably not intended behaviour for you, so you'd need to decompress
is beforehand. Alternatively, the `fromJar` method can be used to include
jars. Furthermore, `fromJar` implicitly adds dependencies on Tasks if the input
is a task. The below example shows just that.

The recommended configuration of the `remapJar` task is follows:

```groovy
remapJar {
    archiveClassifier = 'remapped'
    fromJar jar

    inputs.file jar.archiveFile // Needed for gradle's "up-to-date" checks
}
```

Sometimes muscle memory gets the better of you and you are still
accustomed to using `build` to create jars. Unfortunately, by default, `build`
does not create a remapped jar. In order for the `build` task to also remap,
one can make `build` depend on `remapJar`. In practice this would look as
follows:

```groovy
build {
    dependsOn remapJar
}
```

## Defining the mods in the development environment

The mods that are run in the dev env (which is started through the `runMods`
task that will be talked about later on) can be selected by configuring the
`deployMods` task. If you mod does not depend on any other mods, nothing needs to
be done. However if you want to depend on third party mods, an approach such
as the one below can be used: 

```groovy
deployMods {
    from "libs/mymod.jar"
}
```

Aside from `String` (which is used in the above example) other types can be used.
A complete list of supported types is as follows:

 - `PublishArtifact`
 - `AbstractArchiveTask` tasks (includes `Jar` tasks)
 - `DefaultAdhocSoftwareComponent` components
 - `Configuration`s
 - `String`, `File`, `Path` and all other types that can be converted to `File`
   as per [Project#file](https://docs.gradle.org/8.1/javadoc/org/gradle/api/Project.html#file-java.lang.Object-).

Multiple files can be referenced, however only files that are mods (as in
Starloader-launcher extensions) will get copied into the extension directory.
All mods with the same id/name that already exist in the extension directory
will get removed in order to avoid duplicates.

**WARNING:** Removing a mod from the `deployMods` list will not remove them
from the extensions folder yet. This may get changed should there be sufficient
momentum.

Reversible access setters from other mods ("transitive reversible access
setters") are not applied at compile-time, but are applied as usual at
runtime.

In order add external mods via maven, the following approach can be used:

```groovy
// Note: The order of the "configurations" block matters - it should be over the "deployMods" block.
configurations {
    dependencyMods // Define the "dependencyMods" configuration so it can be used
    compileOnlyApi.extendsFrom(dependencyMods) // Also add all deployed mods to the compile classpath
}

deployMods {
    // Tell starplane to deploy all mods from the "dependencyMods" configuration
    from configurations["dependencyMods"]
}

dependencies {
    // Add the mod to the configuration (you can also add several mods to the same configuration)
    dependencyMods "de.geolykt:starloader-api:2.0.0-a20240624.1"
}
```

## Running the development environment

The dev env can be run through the `runMods` task. The development environment
allows you to test your mod quickly while still using deobfuscated mappings.

The `runMods` task is a `JavaExec` task, which means it can be configured as one
(if you need to add JVM Arguments), however in default circumstances no further
changes need to be applied.

In order for the dev env to work, the `deployMods` task needs to be correctly
configured.

## The `genEclipseRuns` task

While the `runMods` task works to execute the development environment, it does
not allow to easily debug the environment with the aid of a debugger. While
setting the `debug` option of the `runMods` task to true (as allowed by
`JavaExec`) does allow to attach a debugger to the environment, IDEs such as
Eclipse struggle with finding the sources of the classes. This can make
debugging difficult. To counteract this, the `genEclipseRuns` task generates
the `runMods.launch` file that can be used to execute the development
environment right within your IDE with all the extras your IDE provides.

**NOTE:** In legacy applications which still use access wideners the generated
\*.launch files won't work as necessary tasks (such as negating AWs) aren't run.
To counteract this issue, mods should instead of RAS (reversible access setters),
which can be reversed at runtime if deemed necessary. While AWs are still supported
by SLL, gslStarplane no longer supports remapping them - RAS is still supported
and will continue to be supported for the forseeable future.

### Dealing with shaded dependencies

When dealing with dependencies that are normally included in the jar of your mod,
you will generally be quick to see that using these dependencies within your IDE's
launch configurations will not work due to classloader constraints not be matched.
This is an unfortunate byproduct of how the IDE evaluates the run configurations
and the root problem cannot be fixed by us. However, we can tell SLL to bundle
arbitrary files into your mod at runtime.

What these arbitrary files are is evaluated by the `genEclipseRuns` task
and can be set by the buildscript using the
`additionalRuntimeDependency(String, Object)` method.

As gslStarplane will treat each source set as a separate mod, the individual mods
of your project are identified using the source set's name. This will generally be
`main`. The most common use of this feature will as such look as follows:

```groovy
genEclipseRuns {
    additionalRuntimeDependency("main", configurations["runtimeClasspath"])
}
```

The value is a path element which is one of the following:
 - A `Configuration`
 - A `File`, `URI`, `URL` or a `CharSequence` which represents a valid `URL`
 - All other types that can be converted to `File`
   as per [Project#file](https://docs.gradle.org/8.1/javadoc/org/gradle/api/Project.html#file-java.lang.Object-).

### Handling expansion of placeholder properties in the development environment

Note: This feature requires SLL 4.0.0-a20240711 or newer. It will gracefully do nothing
under older versions of SLL.

Note: As of now, this feature is exclusive to the extension.json file. Expansion will
thus not be handled in the development environment for other files. Be vigilant in that
case.

At times it can be tedious to remember to keep the extension.json file in sync with
your gradle buildscripts (version mismatches anyone?). However for this problem gradle
has a neat solution: Resource postprocessing or more specifically the expansion of
placeholder properties. For this, you define that the extension.json file should get expanded
using the project's properties within in your `build.gradle` buildscript:

```groovy
processResources {
    filesMatching("extension.json") {
        expand(project.properties)
    }
}
```

then you define your extension.json file with the appropriate placeholders. For this example
we will go with the following, but in practice you can do much more than just that:

```json
{
    "entrypoint": "de.geolykt.s2dmenues.S2DMenues",
    "name": "S2DMenues",
    "version": "${version}",
    "dependencies": [ "StarloaderAPI" ]
}
```

Then, you also want to define the value of the property. This is done within the `gradle.properties`
file:

```properties
version=0.1.0
```

Now, everything works - mostly. However, when launching from the IDE you will quickly notice
that the placeholders are never getting expanded. Henceforth, SLL provides a way to emulate the
expansion of these placeholders at runtime. This is plainly done by specifying the file
from which the properties shall be taken from. The file needs to be a `.properties` file - the
`gradle.properties` file will usually suffice. Henceforth, you'll find yourself with the
following in your `build.gradle` buildscript:

```groovy
genEclipseRuns {
    propertyExpansionSource = project.file("gradle.properties")
}
```

Note that only one source can be active at any given time.
If the value is declared multiple times, the value will be plainly overriden by later calls.

For this change to apply, the `genEclipseRuns` task has to be executed anew.

## Selecting the modloader (the `devRuntime` configuration)

As of now, only SLL - regardless of mixin engine - can be used as a mod loader,
though in theory modloaders that work as javaagents can easily be added. The version
used depends on the contents of the devRuntime configuration classpath - so
SLL needs to be either present on the runtime classpath or be explicitly
declared as being part of the devRuntime configuration in order for the dev env
to work. SLL can thus be declared as follows:

```groovy
dependencies {
    // [...]
    devRuntime "org.stianloader:launcher-micromixin:4.0.0-a20240413"
    // [...]
}
```

The devRuntime configuration extends from the `runtimeClasspath` configuration,
so elements you added through `runtimeOnly` or similar will be available.
However as this may has consequences on classloading that may be removed
in the future (it is plausible that all runtime elements will be available to us
anyways due to shading).

It is generally not advisable to add mods through the devRuntime, instead the
deployMods task should be configured accordingly. Failure to understand this may
result in mods not properly loading or other classloading issues.

## Selecting the mappings

At the moment the spStarmap ontop of slIntermediary mappings are hardcoded,
but it is possible to define further supplementary mappings via either softmaps
or traditional mapping formats.

### Declaring softmaps

*Note: This is an experimental feature and is subject to change*

**WARNING**: This feature turned out to be less capable than anticipated and
will likely be removed. Use other supplementary mappings instead.

**WARNING**: At this point in time layered mappings are planned, but not yet
fully supported. Handle multiple softmap mappings with care, as current behaviour
is subject to change.

Softmap is a purpose built deobfuscation mappings format that is most resistant
against changes in the source names. Unlike other mappings format, this format
doesn't map things based on 1:1 mappings. Instead, it uses context clues, such
as the bytecode of methods, descriptors, usages, and more. How these context
clues are used can be freely choosen by whoever write the softmap mappings files.

Unlike slintermediary and spStarmap, softmap mappings files can be freely choosen
by the buildscript, meaning that you can easily swap out names if you feel like
not working with them. By default gslStarplane does not ship any softmap mappings
file. One way to define a mappings file would be:

```groovy
starplane {
    softmapFile("softmap.softmap")
}
```

Aside from `String`, following types can be used:
 - A `Configuration`
 - A `Path`
 - All types that can be converted to `File`
   as per [Project#file](https://docs.gradle.org/8.1/javadoc/org/gradle/api/Project.html#file-java.lang.Object-).

### Declaring other supplementary mappings

*Note: This is an experimental feature and is subject to change*

Similar to softmap files, supplementary mappings can be defined using

```groovy
starplane {
    mappingsFile("tinyv2", "deobf-mappings.tinyv2")
}
```

The first argument is the format of the mappings. Ususally that would
either be "tiny" or "tinyv2". Other mapping formats are supported,
but are not recommended for use. The second argument is the path to the
mapping file. Aside from `String`, following types can be used:
 - A `Configuration`
 - A `Path`
 - All types that can be converted to `File`
   as per [Project#file](https://docs.gradle.org/8.1/javadoc/org/gradle/api/Project.html#file-java.lang.Object-).

For mapping formats with multiple namespaces (such as those of the tiny family),
the source namespace is the first column where as the destination (deobfuscated)
namespace is the last column.

### Using the development environment with asymmetric deobfuscation mappings

*Hint*: By nature, the production environment uses the common "official"
(i.e. obfuscated) namespace which leads to the production environment having
symmetric deobfuscation mappings. This means that the issue described in this
section is exclusive to the development environment.

When using supplementary mappings with dependency mods, then all dependency mods
need to make use of the same supplementary mappings. Such an environment is
described to have symmetric deobfuscation mappings. However, in practice
this is unlikely to occur, in which case the environment has asymmetric
deobfuscation mappings.

Asymmetric deobfuscation mappings may lead to crashes or other incompatibilities
induced by classes or members not existing in one mapping namespace while
existing in the other. As such, common symptoms of this issue are
`ClassNotFoundException`s, `ClassDefNotFoundError`s and `LinkageError`s.

To rememdy this issue, the dependency mods have to be remapped into a common
mapping namespace. Gsl-Starplane uses the mapping namespace of the mod defined
by the project it was applied on as the common namespace, remapping all
deployed mods to that namespace. However, it will **only do so if configured.**

An example of proper configuration is as follows:
```groovy
configurations {
    dependencyMods {
        transitive = false
    }
}

deployMods {
    from configurations["dependencyMods"]
    remapMods = true
}

dependencies {
    dependencyMods("de.geolykt:starloader-api:2.0.0-a20240624.1:remapped")
    compileOnlyApi("de.geolykt:starloader-api:2.0.0-a20240624.1")
    // [...]
}
``` 

The two important parts are that you use `remapMods = true` for the `deployMods`
task (the default value is `false`) and that you use the remapped
(i.e. obfuscated) jars for the deployment inputs. In most cases this corresponds
to using the artifact with the `remapped` classifier. In general, when using
`remapMods = true`, the jars configured to be used by `deployMods` should be
the same kind of jars used within the production environment.

Failure to use the obfuscated jars as the input is likely going to cause issues
with inferring the names of mixin targets. It may work in other circumstances,
but this is behaviour that should not be depended on.

As dependencies could depend on mods that don't have the `remapped` classifier,
depending on certain mods may pull in mods that fail to remap. In general the
mod that fails to remap is SLAPI. To prevent these transitive dependencies (or
dependencies of dependencies, in layman's terms) being resolved, disable
transitive resolution for your configuration - this can be configured by setting
`transitive = false`. To verify that your change has the intended effect, you
can look at the dependency graph of your desired configuration by running the
`dependencies` task via the `./gradlew dependencies` command or similar. This
also has the upside of eliminating non-mod entries, although in general
gsl-starplane will do a good job at filtering out non-mod dependencies within
the configuration.

*Note*: Due to how gsl-starplane remaps starplane-annotations annotations,
mods built using older versions of gsl-starplane (that is before 2024-06-24)
will be incompatible with this process. However, this detail will unlikely
have an impact for you. Mods built using newer versions of gsl-starplane
or mods that do not make use of starplane-annotations are unaffected.

*Tip*: Due to how IDEs retrieve source and javadoc artifacts, it is
advisable to use the `remapped` artifact for the configuration used
for `deployMods` where as the compilation classpath is provided with the
standard non-remapped (i.e. dev env) jar. The above example follows this
principle. Do note that when doing this, `compileOnlyApi` does not need to
extend from `dependencyMods` even though doing so is recommended in
previous sections. That being said, disregarding this tip should have
no averse consequences if you know how to navigate with these issues.

## Decompilation

gslStarplane decompiles Galimulator with Vineflower, a Fernflower-based
decompiler. The dependencies added on the decompilation classpath are
controlled by the `galimulatorDependencies`. Furthermore, gslStarplane always
decompiles the stripped galimulator jar with compile-time access.

The original line mappings are visible in the decompiled output,
but starplane automatically changes the line mappings of the runtime and
the stripped compile-time jars to reflect the line mappings of the decompiled
output.

## Eclipse external null annotations

In order to provide full parity over the old starplane on slbrachyura system
gslStarplane supports the feature you'd expect the least: Eclipse external null
annotations. EEAs can be added like so:

```groovy
plugins {
    // [...]
    id 'eclipse'
}

starplane {
    eclipseEEA = rootProject.file("src/eclipse-eea")
}
```

and will be applied on all gradle classpath elements.


Note: the backend code within gslStarplane is basically equal to

```groovy
apply plugin: 'eclipse'

eclipse {
    classpath {
      containers 'org.eclipse.buildship.core.gradleclasspathcontainer'  
      file {
            whenMerged {
                def source = entries.find { it.path == 'org.eclipse.buildship.core.gradleclasspathcontainer' }
                source.entryAttributes['annotationpath'] = "src/eclipse-eea"
            }
        }
    }
}
```

## Checking for mapping tears

When creating APIs that must be stable across both the development environment
as well as within production it is an attractive API design to hide implementations
behind an interface. However, doing so has the acute problem that the remapper
might be tempted to incorrectly remap the implementation method while keeping
the interface definition untouched: A mapping tear appears.
This effect is mostly caused by implementing a method within an interface
while simultaneously overriding a method from galimulator with the same descriptor
and name.

**Note:** At this point in time, gslStarplane will not verify for mapping tears
in mixins. Be aware with them. That being said, micromixin-remapper will do it's
best to fail if an obvious mapping tear is detected.

The solution for this problem is one of the diagnostic nature, as avoiding
or fixing it outright is not possible without significantly tampering with
the class files. For this, gslStarplane provides the `GslVerifyRemapperJarTask`,
which verifies the produced jar for possible programmer blunders (at this point
only mapping tears).

**Note:** `GslVerifyRemapperJarTask` will not fail compilation if it detects
a fault. Instead, it will plainly just report the fault to the log as an error
(though gradle will not handle it differently by default).

To use the task, it first needs to be declared as follows (gslStarplane
does not preconfigure the task, meaning that all instances of the task need
to be defined within the buildscript):

```groovy
task jarVerification(type: de.geolykt.starloader.gslstarplane.GslVerifyRemapperJarTask, dependsOn: remapJar) {
    includingGalimulatorJar = true
    validationJar = remapJar.archiveFile
}

check {
    dependsOn jarVerification
}
```

The `check` block can be omitted if it is needed and is only included here
to make the task be run whenever the `check` task is executed (`build` also
executes `check` by default).

The `GslVerifyRemapperJarTask` task defines three properties:
- `validationJar` (required): This defines the input jar which should be
  validated.
- `classpath` (optional): The runtime classpath used for validation.
- `includingGalimulatorJar` (optional, default `true`): Whether the
  **original** (i.e. fat and obfuscated) galimulator jar should be added
  to the validation classpath.

Note that the validation classpath can be plainly left empty and is
generally the recommended approach for performance reasons as long as
`includingGalimulatorJar` is set to `true`. However, with an empty
classpath the task might not always be aware of all abstract methods
and thus may not be able to detect certain mapping tears.

## Roadmap

Currently it is planned (or rather said, dreamed of) to offer Starplane as
Eclipse and IntelliJ plugins, however due to the required effort needed to
learn their plugin APIs this will only happen in the far future - if ever.

A Maven integration is unlikely due to it apparently lacking the ability
to add dependencies programmatically without touching the POM.
