package de.geolykt.starplane.remapping;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface CommentLookup {
    @Nullable
    String getClassComment(@NotNull String className);

    @Nullable
    String getMethodComment(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc);

    @Nullable
    String getFieldComment(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc);
}
