package de.geolykt.starplane.remapping;

import net.fabricmc.tinyremapper.IMappingProvider.Member;

public class HashableMember {

    public final Member member;

    public HashableMember(Member member) {
        this.member = member;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof HashableMember) {
            HashableMember other = (HashableMember) obj;
            return other.member.owner.equals(this.member.owner)
                    && other.member.desc.equals(this.member.desc)
                    && other.member.name.equals(this.member.name);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return this.member.desc.hashCode() ^ this.member.name.hashCode() ^ this.member.owner.hashCode();
    }
}
