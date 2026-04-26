package vio.barie.core.common;

public enum RoleEnum {
    ROLE_ORG_ROOT,
    ROLE_ORG_VIEW,
    ROLE_ORG_EDIT,
    ROLE_BRANCH_VIEW,
    ROLE_BRANCH_EDIT;

    public String authority() {
        return this.name();
    }

    public static String[] CAN_VIEW() {
        return new String[]{
                ROLE_ORG_ROOT.authority(),
                ROLE_ORG_VIEW.authority(),
                ROLE_ORG_EDIT.authority(),
                ROLE_BRANCH_VIEW.authority(),
                ROLE_BRANCH_EDIT.authority()
        };
    }

    public static String[] CAN_EDIT() {
        return new String[]{
                ROLE_ORG_ROOT.authority(),
                ROLE_ORG_EDIT.authority(),
                ROLE_BRANCH_EDIT.authority()
        };
    }

    public static String[] ONLY_ORG_VIEW() {
        return new String[]{
                ROLE_ORG_ROOT.authority(),
                ROLE_ORG_EDIT.authority(),
                ROLE_ORG_VIEW.authority()
        };
    }

    public static String[] ONLY_ORG_EDIT() {
        return new String[]{
                ROLE_ORG_ROOT.authority(),
                ROLE_ORG_EDIT.authority(),
        };
    }
    public static String[] ONLY_ROOT() {
        return new String[]{
                ROLE_ORG_ROOT.authority(),
        };
    }
    public static String[] REQUIRED_BRANCH() {
        return new String[]{
                ROLE_BRANCH_VIEW.authority(),
                ROLE_BRANCH_EDIT.authority(),
        };
    }


}