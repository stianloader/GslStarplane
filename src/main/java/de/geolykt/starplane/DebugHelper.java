package de.geolykt.starplane;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.ClassNode;
import org.stianloader.remapper.MemberRef;
import org.stianloader.remapper.SimpleTopLevelLookup;
import org.stianloader.remapper.SimpleTopLevelLookup.MemberRealm;

class DebugHelper {
    public static void debugMemberRealms(@NotNull List<@NotNull ClassNode> nodes) {
        Map<@NotNull MemberRef, @NotNull MemberRealm> realms = SimpleTopLevelLookup.realmsOf(nodes);
        realms.forEach((ref, realm) -> {
            System.out.println("Realm of: '" + ref + "'. Root: '" + realm.rootDefinition + "'. Members: " + realm.realmMembers);
        });
    }
}
