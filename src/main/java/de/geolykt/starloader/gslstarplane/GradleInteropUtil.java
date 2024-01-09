package de.geolykt.starloader.gslstarplane;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;

import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.internal.component.UsageContext;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

public class GradleInteropUtil {
    @Nullable
    private static final MethodHandle COMPONENT_USAGE_CONTEXT_MH;

    @SuppressWarnings("unused") // Used reflectively via method handles
    private static Iterable<UsageContext> onIncompatibleComponent(SoftwareComponent component) {
        throw new IllegalStateException("Only implementations of SoftwareComponent that are an instance of DefaultAdhocSoftwareComponent (gradle 7 and older gradle 8 releases) or SoftwareComponentInternal (newer gradle 8 releases) can be used as a mod jar. However an implementation of type " + component.getClass() + " was used!");
    }

    public static Iterable<UsageContext> getUsageContexts(SoftwareComponent component) {
        try {
            MethodHandle handle = GradleInteropUtil.COMPONENT_USAGE_CONTEXT_MH;
            if (handle == null) {
                throw new UnsupportedOperationException("Software components are not available as the needed method handles could not be set up correctly.");
            }
            return (Iterable<UsageContext>) handle.invoke(component);
        } catch (Throwable e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else if (e instanceof Error) {
                throw (Error) e;
            }
            throw new RuntimeException(e);
        }
    }

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle softwareComponentMH = null;
        Class<?> softwareComponentImplClass = null;

        try {
            softwareComponentImplClass = lookup.findClass("org.gradle.api.plugins.internal.DefaultAdhocSoftwareComponent");
        } catch (ReflectiveOperationException e) {
            try {
                softwareComponentImplClass = lookup.findClass("org.gradle.api.publish.internal.component.SoftwareComponentInternal");
            } catch (ReflectiveOperationException e2) {
                e2.addSuppressed(e);
                LoggerFactory.getLogger(GradleInteropUtil.class).error("Unable to generate method handle for component content lookup. Some features might not work properly!", e2);
            }
        }

        label:
        try {
            if (softwareComponentImplClass == null) {
                break label;
            }
            MethodHandle isInstanceof = lookup.findVirtual(Class.class, "isInstance", MethodType.methodType(boolean.class, Object.class));
            isInstanceof = isInstanceof.bindTo(softwareComponentImplClass);
            MethodHandle getUsages = lookup.findVirtual(softwareComponentImplClass, "getUsages", MethodType.methodType(Set.class));
            MethodHandle throwEx = lookup.findStatic(GradleInteropUtil.class, "onIncompatibleComponent", MethodType.methodType(Iterable.class, SoftwareComponent.class));
            softwareComponentMH = MethodHandles.guardWithTest(isInstanceof, getUsages, throwEx);
        } catch (ReflectiveOperationException e) {
            LoggerFactory.getLogger(GradleInteropUtil.class).error("Unable to generate method handle for component content lookup. Some features might not work properly!", e);
        }

        COMPONENT_USAGE_CONTEXT_MH = softwareComponentMH;
    }
}
