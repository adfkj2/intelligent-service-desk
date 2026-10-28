package com.isd.support;

import com.isd.auth.domain.UserRole;
import com.isd.auth.security.LoginUser;

/**
 * 测试用登录态工厂。
 * 注意：这里的 id 不必对应真实的 sys_user 行——当前表结构没有物理外键，
 * 这样接口测试就可以只造"身份"，不用顺手造用户数据。
 */
public final class TestUsers {

    private TestUsers() {
    }

    public static LoginUser employee(long id) {
        return new LoginUser(id, "emp" + id, "{noop}", "员工" + id, UserRole.EMPLOYEE, true, false);
    }

    public static LoginUser support(long id) {
        return new LoginUser(id, "sup" + id, "{noop}", "支持" + id, UserRole.SUPPORT, true, false);
    }

    public static LoginUser admin(long id) {
        return new LoginUser(id, "adm" + id, "{noop}", "管理员" + id, UserRole.ADMIN, true, false);
    }
}
