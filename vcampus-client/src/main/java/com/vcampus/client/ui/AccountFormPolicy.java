package com.vcampus.client.ui;

import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;

import java.util.Set;

final class AccountFormPolicy {
    private AccountFormPolicy() {
    }

    static String username(UserRole identity, String number) {
        if (identity == UserRole.STUDENT) {
            if (number != null && number.matches("[0-9]{10}")) {
                return number;
            }
            throw new IllegalArgumentException("学号必须是 10 位数字");
        }
        if (identity == UserRole.TEACHER) {
            if (number != null && number.matches("T[0-9]{7}")) {
                return number;
            }
            throw new IllegalArgumentException("教师工号必须是大写 T 加 7 位数字");
        }
        throw new IllegalArgumentException("只能创建学生或教师账号");
    }

    static Set<UserRole> allowedRoles(UserRole identity) {
        if (identity != UserRole.STUDENT && identity != UserRole.TEACHER) {
            throw new IllegalArgumentException("只能为学生或教师分配业务管理员角色");
        }
        return RoleCompositionPolicy.allowedAdministrativeRoles(identity);
    }

    static boolean showsAcademicClassFields(UserRole identity) {
        allowedRoles(identity);
        return identity == UserRole.STUDENT;
    }
}
