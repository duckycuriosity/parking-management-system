package vio.barie.core.config.filter;


import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.user.UserDTO;

public class UserContext {

    private static final ThreadLocal<UserDTO> CONTEXT = new ThreadLocal<>();

    public static void set(UserDTO user) {
        CONTEXT.set(user);
    }

    public static UserDTO get() {
        UserDTO user = CONTEXT.get();
        if (user == null) {
            throw new ServiceException("User context is not set");
        }
        return user;
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static Long getOrgId() {
        Long orgId = get().getOrganizeId();
        if (orgId == null) {
            throw new ServiceException("User context is not set or organizeId is null");
        }
        return orgId;
    }

    public static Long getBranchId() {
        return get().getBranchId();
        //can be null
        // not null
    }
    public static Long getOrgCode() {
        return get().getOrganizeId();
        //can be null
        // not null
    }
}