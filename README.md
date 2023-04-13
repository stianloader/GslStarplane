# GslStarplane: Starplane for Gradle

GslStarplane is the gradle port of the starplane buildsystem originally written
for use in combination with Brachyura. Starplane lays the foundation of modern
galimulator modding.

## Including the plugin

**WARNING:** When publishing your code online you should make extra sure to NOT
include the `build` directory. GslStarplane pushes deobfuscated jars to it.

GslStarplane functions as a gradle plugin, so it first needs to be applied on a
gradle project in order to work. Under groovy you can do it by adding
`id 'gsl-starplane' version '0.1.0'` to the plugins block in the `build.gradle`
file. The full plugins block will thus look something like follows:

```groovy
plugins {
    id 'java'
    id 'java-library'
    id 'maven-publish'
    id 'gsl-starplane' version '0.1.0'
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
        mavenLocal()
        mavenCentral()
        maven {
            name = 'Geolykt'
            url = 'https://geolykt.de/maven/'
        }
        maven {
            name 'fabric'
            url 'https://maven.fabricmc.net/'
        }
    }
}
```

And that is it! Unfortunately you won't be able to use the plugin for much
with such a setup. So continue reading!

## Specifing Access Wideners

**WARNING:** Access Wideners are as dangerous as they are simple to use.
Widening access of a method could make methods that normally would be
independent of each other override each other.

Access Wideners can optionally be declared in the `build.gradle` as follows:

```groovy
starplane {
    accesswidener = rootProject.file("src/main/resources/aw.accesswidener")
}
```

Starplane will automatically apply the contents of the accesswidener on the
galimulator jar and will remap the accesswidener in `remap` task.

At the moment, only one accesswidener can be used at a time.
**Declaration of AWs to starplane is independent from the
Starloader-launcher declaration of accesswideners!**

## The `remap` task

The `remap` task remaps all references of deobfuscated members to use
the obfuscated names instead. It produces the jar that can be used outside
the development environment and which you can freely distribute
(should there not be other limitations).

The `remap` task extends the `jar` task, which means that it can
be configured similar to `jar`. However, unlike the `jar` task
you probably want to define the `from` inputs.

Note: It is recommended to set the `archiveClassifier` of the
remapped jar as otherwise the `jar` task cannot be cached.

The recommended configuration of the `remap` task is follows:

```groovy
remap {
    archiveClassifier = 'remapped'
    dependsOn jar
    from jar
}
```

Sometimes muscle memory gets the better of you and you are still
acustomed to using `build` to create jars. Unfortunately, by default, `build`
does not create a remapped jar. In order for the `build` task to also remap,
one can make `build` depend on `remap`. In practice this would look as follows:

```groovy
build {
    dependsOn remap
}
```

## Defining the mods in the development environment

The mods that are run in the dev env (which is started through the `runMod`
task that will be talked about later on) can be selected by configuring the
`deployMods` task. If you mod does not depend on any other mods, the following
would be one of plenty correct approaches:

```groovy
deployMods {
    from components['java']
}
```

Aside from `DefaultAdhocSoftwareComponent` (which is used in the above example)
other types can be used. A complete list of supported types is as follows:

 - `PublishArtifact`
 - `Jar` tasks
 - `DefaultAdhocSoftwareComponent` components
 - `String`, `File`, `Path` and all other types that can be converted to `File`
   as per [Project#file](https://example.com).

Multiple files can be referenced, however only files that are mods (as in
Starloader-launcher extensions) will get copied into the extension directory.
All mods with the same id/name that already exist in the extension directory
will get removed in order to avoid duplicates.

During the copy process the starplane annotations will get evaluated and
their access wideners are also accordingly transformed.

**WARNING:** External mods are not yet fully supported. However, this will change
in the future.

**WARNING:** Access wideners from other mods might not get correctly applied.
Further investigation is needed (there seems to be a flaw in how we deal
with runtime vs compiletime galimulator jars).

**WARNING:** Removing a mod from the `deployMods` list will not remove them
from the extensions folder yet. This may get changed should there be sufficent
momentuum.

## Running the development environment

The dev env can be run through the `runMod` task. The development environment
allows you to test your mod quickly while still using deobfuscated mappings.
Furthermore it allows you to use your IDE to debug your mod.

The `runMod` task is a `JavaExec` task, which means it can be configured as one
(if you need to add JVM Arguments), however in default circumstances no further
changes need to be applied.

In order for the dev env to work, the `deployMods` task needs to be correctly
configured.

## Selecting the Mod loader

As of now, only the Starloader launcher can be used as a mod loader (though
in theory modloaders that work as javaagents can easily be added). The version
used depends on the contents of the runtime classpath - so the starloader
launcher needs to be present on the runtime classpath in order for the dev
env to work.

**WARNING:** We are aware of a flaw that makes the above process collide
with other processes such as shading. This will get fixed in the future.

## Selecting the Mappings

At the moment only spStarmap ontop of slintermediary can be used.
In the future other variants of deobfuscation mappings (such as mmStarmap) may
get supported.

## Roadmap

Currently it is planned to offer Starplane as Eclipse and IntelliJ plugins,
however due to the required effort needed to learn their plugin APIs this
will only happen in the far future - if ever.

A Maven integration is unlikely due to it apparently lacking the ability
to add dependencies programatically without touching the POM.
