package de.geolykt.starplane.sourcegen;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.StructMethod;

import de.geolykt.starplane.remapping.CommentLookup;
import net.fabricmc.fernflower.api.IFabricJavadocProvider;

public class JavadocSource implements IFabricJavadocProvider {

    @NotNull
    private final CommentLookup commentLookup;

    public JavadocSource(@NotNull CommentLookup lookup) {
        this.commentLookup = lookup;
    }

    @Override
    public String getClassDoc(StructClass structClass) {
        return this.commentLookup.getClassComment(structClass.qualifiedName);
    }

    @Override
    public String getFieldDoc(StructClass structClass, StructField structField) {
        return this.commentLookup.getFieldComment(structClass.qualifiedName, structField.getName(), structField.getDescriptor());
    }

    @Override
    public String getMethodDoc(StructClass structClass, StructMethod structMethod) {
        return this.commentLookup.getMethodComment(structClass.qualifiedName, structMethod.getName(), structMethod.getDescriptor());
    }

}
