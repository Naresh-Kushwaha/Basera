package app.basera.identity;

import app.basera.identity.dto.MemberResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MemberMapper {

    @Mapping(target = "membershipId", source = "membership.id")
    @Mapping(target = "userId", source = "membership.userId")
    @Mapping(target = "displayName", source = "membership.displayName")
    @Mapping(target = "photoUrl", source = "membership.photoUrl")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "role", source = "membership.role")
    @Mapping(target = "status", source = "membership.status")
    @Mapping(target = "collegeEmailVerified", expression = "java(membership.getCollegeEmailVerifiedAt() != null)")
    @Mapping(target = "joinedAt", source = "membership.createdAt")
    MemberResponse toResponse(Membership membership, UserAccount user);
}
