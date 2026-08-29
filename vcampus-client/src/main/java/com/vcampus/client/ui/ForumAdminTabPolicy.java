package com.vcampus.client.ui;

import com.vcampus.common.model.ForumAccessPolicy;
import com.vcampus.common.model.UserRole;

import java.util.Set;

final class ForumAdminTabPolicy {
    private ForumAdminTabPolicy() {
    }

    static boolean visible(Set<UserRole> roles) {
        return ForumAccessPolicy.canManage(roles);
    }
}
