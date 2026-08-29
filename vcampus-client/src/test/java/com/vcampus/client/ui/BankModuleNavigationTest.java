package com.vcampus.client.ui;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankModuleNavigationTest {
    @Test
    void ordinaryAndAdminTabsMatchTheApprovedDesign() {
        BankModulePanel ordinary = panel(Set.of(UserRole.STUDENT));
        assertEquals(List.of("账户首页", "转账", "流水明细"), ordinary.tabTitles());

        BankModulePanel admin = panel(Set.of(UserRole.TEACHER, UserRole.BANK_ADMIN));
        assertEquals(List.of("账户首页", "转账", "流水明细", "账户管理", "充值与冻结", "全量流水"),
                admin.tabTitles());
    }

    @Test
    void notificationDeepLinkOpensPersonalLedger() {
        BankModulePanel panel = panel(Set.of(UserRole.STUDENT));
        panel.openLedger();
        assertEquals("流水明细", panel.selectedTabTitle());
    }

    private BankModulePanel panel(Set<UserRole> roles) {
        return new BankModulePanel(new VCampusClient("localhost", 1), "token", roles, null);
    }
}
