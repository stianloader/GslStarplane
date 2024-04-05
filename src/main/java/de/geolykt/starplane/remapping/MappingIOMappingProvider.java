package de.geolykt.starplane.remapping;

import org.jetbrains.annotations.NotNull;

import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MappingTreeView.ClassMappingView;
import net.fabricmc.mappingio.tree.MappingTreeView.FieldMappingView;
import net.fabricmc.mappingio.tree.MappingTreeView.MethodArgMappingView;
import net.fabricmc.mappingio.tree.MappingTreeView.MethodMappingView;
import net.fabricmc.mappingio.tree.MappingTreeView.MethodVarMappingView;
import net.fabricmc.tinyremapper.IMappingProvider;

public class MappingIOMappingProvider implements IMappingProvider {

    @NotNull
    private final MappingTreeView mappingIOTree;
    private final int srcNamespace;
    private final int dstNamespace;

    public MappingIOMappingProvider(@NotNull MappingTreeView mappingIOTree, int srcNamespace, int dstNamespace) {
        this.mappingIOTree = mappingIOTree;
        this.srcNamespace = srcNamespace;
        this.dstNamespace = dstNamespace;
        if (this.srcNamespace == this.dstNamespace) {
            throw new IllegalArgumentException("srcNamespace == dstNamespace: " + srcNamespace + ", " + dstNamespace);
        }
    }

    @Override
    public void load(MappingAcceptor out) {
        for (ClassMappingView classView : this.mappingIOTree.getClasses()) {
            if (classView == null) {
                // Eclipse can be a bit pedantic at times (when using foreach over a wildcard collection)
                throw new NullPointerException();
            }
            String srcClassName = classView.getName(this.srcNamespace);
            out.acceptClass(srcClassName, classView.getName(this.dstNamespace));
            for (FieldMappingView fieldView : classView.getFields()) {
                if (fieldView == null) {
                    throw new NullPointerException();
                }
                Member srcMember = new Member(srcClassName, fieldView.getName(this.srcNamespace), fieldView.getDesc(this.srcNamespace));
                out.acceptField(srcMember, fieldView.getName(this.dstNamespace));
            }
            for (MethodMappingView methodView : classView.getMethods()) {
                if (methodView == null) {
                    throw new NullPointerException();
                }
                Member srcMember = new Member(srcClassName, methodView.getName(this.srcNamespace), methodView.getDesc(this.srcNamespace));
                out.acceptMethod(srcMember, methodView.getName(this.dstNamespace));
                for (MethodArgMappingView argView : methodView.getArgs()) {
                    if (argView == null) {
                        throw new NullPointerException();
                    }
                    out.acceptMethodArg(srcMember, argView.getLvIndex(), argView.getName(this.dstNamespace));
                }
                for (MethodVarMappingView lvView : methodView.getVars()) {
                    if (lvView == null) {
                        throw new NullPointerException();
                    }
                    // TODO is getLvtRowIndex really the same as asmIndex? Player (fabric contributor) suggested it as an equivalent, but I wouldn't be surprised if there was some miscommunication here
                    out.acceptMethodVar(srcMember, lvView.getLvIndex(), lvView.getStartOpIdx(), lvView.getLvtRowIndex(), lvView.getName(this.dstNamespace));
                }
            }
        }
    }

}
