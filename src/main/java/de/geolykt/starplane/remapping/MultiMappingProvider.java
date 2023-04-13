package de.geolykt.starplane.remapping;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.IMappingProvider.MappingAcceptor;

import de.geolykt.starloader.deobf.remapper.Remapper;

// When using this class intermediary has to be applied before named.
class MultiMappingContainer implements MappingAcceptor, IMappingProvider {

    final Map<String, String> classes = new HashMap<>();
    final Map<HashableMember, String> fields  = new HashMap<>();
    final Map<HashableMember, String> methods = new HashMap<>();

    private final Map<String, String> unappliedClasses = new HashMap<>();
    private final Map<HashableMember, String> unappliedFields  = new HashMap<>();
    private final Map<HashableMember, String> unappliedMethods = new HashMap<>();

    @Override
    public void acceptClass(String srcName, String dstName) {
        unappliedClasses.put(srcName, dstName);
    }

    @Override
    public void acceptField(Member field, String dstName) {
        unappliedFields.put(new HashableMember(field), dstName);
    }

    @Override
    public void acceptMethod(Member method, String dstName) {
        unappliedMethods.put(new HashableMember(method), dstName);
    }

    @Override
    public void acceptMethodArg(Member method, int lvIndex, String dstName) {
        // Ignore
    }

    @Override
    public void acceptMethodVar(Member method, int lvIndex, int startOpIdx, int asmIndex, String dstName) {
        // Ignore
    }

    public void apply() {
        if (classes.isEmpty() && methods.isEmpty() && fields.isEmpty()) {
            classes.putAll(unappliedClasses);
            methods.putAll(unappliedMethods);
            fields.putAll(unappliedFields);
            unappliedClasses.clear();
            unappliedMethods.clear();
            unappliedFields.clear();
            return;
        }
        Remapper remapper = new Remapper();
        remapper.remapClassNames(classes);

        // Requires that the apply method is invoked in application order.
        // I. e. first named then intermediary for reversals
        // For non-reversals first intermediary then named
        // However this method has SERIOUS flaws if it isn't used for reversals
        unappliedClasses.forEach((src, dst) -> {
            String old = classes.remove(dst);
            if (old != null) {
                classes.put(src, old);
            } else {
                classes.put(src, dst);
            }
        });

        StringBuilder sharedBuilder = new StringBuilder();

        unappliedFields.forEach((field, name) -> {
            @SuppressWarnings("null")
            String originalOwner = remapper.getRemappedClassName(field.member.owner);
            @SuppressWarnings("null")
            String originalDesc = remapper.getRemappedFieldDescriptor(field.member.desc, sharedBuilder);
            String old = fields.remove(new HashableMember(new Member(originalOwner, field.member.name, originalDesc)));
            HashableMember hashableMember = new HashableMember(new Member(originalOwner, field.member.name, originalDesc));
            if (old == null) {
                fields.put(hashableMember, name);
            } else {
                fields.put(hashableMember, old);
            }
        });
        unappliedMethods.forEach((method, name) -> {
            @SuppressWarnings("null")
            String originalOwner = remapper.getRemappedClassName(method.member.owner);
            @SuppressWarnings("null")
            String originalDesc = remapper.getRemappedMethodDescriptor(method.member.desc, sharedBuilder);
            String old = methods.remove(new HashableMember(new Member(originalOwner, name, originalDesc)));
            HashableMember hashableMember = new HashableMember(new Member(originalOwner, method.member.name, originalDesc));
            if (old == null) {
                methods.put(hashableMember, name);
            } else {
                methods.put(hashableMember, old);
            }
        });

        unappliedClasses.clear();
        unappliedMethods.clear();
        unappliedFields.clear();
    }

    @Override
    public void load(MappingAcceptor out) {
        if (!unappliedClasses.isEmpty()) {
            throw new IllegalStateException("Called #load while there are still unapplied changes! Consider calling #apply beforehand");
        }

        Remapper reverser = new Remapper(); // I do like my sl-deobf remapper
        classes.forEach((src, dest) -> {
            reverser.remapClassName(dest, src);
            out.acceptClass(src, dest);
        });

        StringBuilder sharedBuilder = new StringBuilder();

        fields.forEach((field, name) -> {
            @SuppressWarnings("null")
            String deobfDesc = reverser.getRemappedFieldDescriptor(field.member.desc, sharedBuilder);
            @SuppressWarnings("null")
            String deobfOwner = reverser.getRemappedClassName(field.member.owner);
            Member deobfMember = new Member(deobfOwner, field.member.name, deobfDesc);
            out.acceptField(deobfMember, name);
        });

        methods.forEach((method, name) -> {
            @SuppressWarnings("null")
            String deobfDesc = reverser.getRemappedMethodDescriptor(method.member.desc, sharedBuilder);
            @SuppressWarnings("null")
            String deobfOwner = reverser.getRemappedClassName(method.member.owner);
            Member deobfMember = new Member(deobfOwner, method.member.name, deobfDesc);
            out.acceptMethod(deobfMember, name);
        });
    }

    @NotNull
    String stringifyMember(@Nullable Member member) {
        if (member == null) {
            return "null";
        }
        return member.owner + "." + member.name + " " + member.desc;
    }
}
public class MultiMappingProvider implements IMappingProvider {

    @NotNull
    private final IMappingProvider @NotNull[] children;

    public MultiMappingProvider(@NotNull IMappingProvider @NotNull... children) {
        this.children = children;
    }

    @Override
    public void load(MappingAcceptor out) {
        MultiMappingContainer container = new MultiMappingContainer();
        for (IMappingProvider child : children) {
            child.load(container);
            container.apply();
        }

        container.load(out);
    }
}
