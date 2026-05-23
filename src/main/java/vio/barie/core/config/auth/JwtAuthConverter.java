package vio.barie.core.config.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vio.barie.core.dto.user.UserDTO;
import vio.barie.core.repository.OrganizeRepository;
import vio.barie.core.repository.RoleRepository;
import vio.barie.core.repository.SysUserRepository;
import vio.barie.core.service.CurrentUserService;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final CurrentUserService currentUserService;
    private final SysUserRepository userRepository;
    private final OrganizeRepository organizeRepository;
    private final RoleRepository roleRepository;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {

        String keycloakUserId = jwt.getSubject();
        UserDTO userDTO = null ;
//        if(jwt.getClaim("role") != null && jwt.getClaim("role").equals(RoleEnum.ROLE_ORG_ROOT.name())) {
//            userDTO = UserDTO.builder().keycloakUserId(keycloakUserId).organizeId(Integer.valueOf((String) jwt.getClaim("orgId"))).email(jwt.getClaim("email"))
//                    .username(jwt.getClaim("preferred_username"))
//                    .roles(new HashSet<>(Set.of(RoleEnum.ROLE_ORG_ROOT.name())))
//                    .permissions(new HashSet<>(Set.of(RoleEnum.ROLE_ORG_ROOT.name())))
//                    .build();
//            UserEntity userEntity = new UserEntity();
//            OrganizeEntity org = organizeRepository.findById(userDTO.getOrganizeId())
//                    .orElseThrow(() -> new ClientException("Organize not found", 404));
//            userEntity.setOrganize(org);
//            userEntity.setEmail(userDTO.getEmail());
//            userEntity.setKeycloakUserId(userDTO.getKeycloakUserId());
//            userEntity.setUsername(userDTO.getUsername());
//            RoleEntity role = roleRepository.findByRoleCode(RoleEnum.ROLE_ORG_ROOT.name())
//                    .orElseThrow(() -> new ClientException("Role not found", 404));
//            userEntity.setRoles(Set.of(role));
//            userRepository.save(userEntity);
//        }else{
            userDTO = currentUserService.getCurrentUser(keycloakUserId);
//        }


        Set<String> roles = new HashSet<>();

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof Collection<?> r) {
                for (Object role : r) {
                    roles.add("ROLE_" + role);
                }
            }
        }

        Set<String> permissions = new HashSet<>(userDTO.getPermissions());

        Set<GrantedAuthority> authorities = new HashSet<>();

        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        userDTO.getRoles().forEach(r -> authorities.add(new SimpleGrantedAuthority(r)));
        userDTO.getRoles().addAll(roles);
        userDTO.getPermissions().addAll(permissions);
        userDTO.getPermissions().addAll(userDTO.getRoles());

        return new UsernamePasswordAuthenticationToken(
                userDTO,
                null,
                authorities
        );
    }
}
