# GcmcStarplane: Common Modding (Tool-)chain Starplane for Gradle

GslStarplane is the gradle port of the starplane buildsystem originally written
for use in combination with Brachyura. Starplane lays the foundation of modern
galimulator modding.

GcmcStarplane is a more multi-purpose branch of GslStarplane, exploring capabilities
for mods beyond the current slintermediary + spstarmap approach.

GcmcStarplane is meant to be used for modding non-galimulator games such as necesse.
However it is not meant for modding games with an already established toolchain, as it
applies to minecraft or runescape.

## Including the plugin

GcmcStarplane functions as a gradle plugin, so it first needs to be applied on a
gradle project in order to work. Under groovy you can do it by adding
`id 'gcmc-starplane' version '0.1.0'` to the plugins block in the `build.gradle`
file. The full plugins block will thus look something like follows:

```groovy
plugins {
    id 'java'
    id 'java-library'
    id 'maven-publish'
    id 'gcmc-starplane' version '0.1.0'
}
```

However, as GcmcStarplane is not located in the default repositories and
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
            name = 'Geolykt'
            url = 'https://geolykt.de/maven/'
        }
    }
}
```

GcmcStarplane is meant to work in a game-independent manner. This means it can
(in theory) be used to mod several (albeit one at a time) games, though the
game needs to be on steam.

To declare which game you want to mod you must declare it like below:

```groovy
starplane {
    game(1169040, "Necesse", "Necesse.jar", "lib/")
    mainClass = "StartClient"
}
```

`1169040` is the game's app id on steam. `1169040` would be the app id for necesse,
`808100` is the appid of galimulator.

The second argument (`Necesse`) is the steam app name. More concretely, this is the
name of the folder of the game within the steam directory.

The third argument is the game's central jar. For necesse it is `Necesse.jar`, for
galimulator it'd be `jar/galimulator-desktop.jar`.

All arguments after that are optional, and represent additional jars you want to use
within the game. Strings that end with a slash (`/`) denote directories. All jars
within the directory are then considered as being part of the game.

And that is it! Unfortunately you won't be able to use the plugin for much
with such a setup. So continue reading!

## Specifing reversible access setters

**WARNING:** RAS is a very niche file format and is only supported by SLL.

**WARNING:** Reversible access setters are as dangerous as they are simple to
use. Changing access of a method could make methods that normally would be
independent of each other to override each other.

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

## The `gameDependencies` configuration

Starplane automatically strips dependencies it can find from the game
jar(-s). This may sound unintuitive but has the benefit of reducing the amount
of classes that need to be decompiled and also provides (if configured
properly) javadocs and sources in the IDE, leading to a more comfortable
development experience.

The dependencies that need to be stripped can be configured through the
`gameDependencies` configuration, however it is advised that the
configuration is not touched. However, by default dependencies which are in the
`gameDependencies` configuration are not on the compile classpath.
To change this, `compileOnlyApi` can be made to extend from
`gameDependencies`. In practice this can be done by inserting

```groovy
configurations {
    compileOnlyApi.extendsFrom(gameDependencies)
}
```

in the project buildscript.

## Defining the mods in the development environment

**NOTE:** This section only applies to Starloader-launcher mods. Mods that are loaded
by the game's modding engine cannot be imported like this.

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

During the copy process the starplane annotations will get evaluated.

**WARNING:** Removing a mod from the `deployMods` list will not remove them
from the extensions folder yet. This may get changed should there be sufficent
momentuum.

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
    dependencyMods "de.geolykt:starloader-api:2.0.0-SNAPSHOT"
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

## Selecting the Mod loader (the `devRuntime` configuration)

As of now, only the Starloader launcher can be used as a mod loader (though
in theory modloaders that work as javaagents can easily be added). The version
used depends on the contents of the devRuntime configuration classpath
- so the starloader launcher needs to be either present on the runtime classpath
or be explicitly declared as being part of the devRuntime configuration in order
for the dev env to work. The Starloader Launcher can thus be declared as follows:

```groovy
dependencies {
    // [...]
    devRuntime "de.geolykt.starloader:launcher:4.0.0-20230608"
    // [...]
}
```

The devRuntime configuration extends from the `runtimeClasspath` configuration,
so elements you added through `runtimeOnly` or similar will be available.
However as this may has consequences on classloading that may be removed
in the future (it is plausible that all runtime elements will be available to us
anyways due to shading).

It is generally not adviseable to add mods through the devRuntime, instead the
deployMods should be configured accordingly. Failure to understand this may
result in mods not properly loading or other classloading issues.

## Selecting the Mappings

GcmcStarplane does not support deobfuscation mappings at the moment.
Once gslStarplane and GcmcStarplane are joined together, mappings would
get resolved in a more dynamic and user-configurable system.

## Decompilation

GcmcStarplane decompiles your game jar with Quiltflower, a Fernflower-based
decompiler. The dependencies added on the decompilation classpath are
controlled by the `gameDependencies`. Furthermore, GcmcStarplane always
decompiles the stripped game jar with compile-time access.

The original line mappings are visible in the decompiled output,
but starplane automatically changes the line mappings of the runtime and
the stripped compile-time jars to reflect the line mappings of the decompiled
output.

**NOTE**: I am aware that QuiltMC (the organisation behind Quiltflower)
had a serious internal disruption starting from the 20th April of 2023.
Due to reasons I wouldn't want to talk about here, I have been banned on Quilt's
toolchain discord. I thus deem it likely that I have been completely banned from
the entirety of the quilt project. The current strategy is to just wait and
hope that they forget that I am banned. Should that not work, Quiltflower will
probably get forked by us (or we use a fork from an organisation that suffered
a similar fate).

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
