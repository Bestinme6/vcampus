package com.vcampus.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StudentManagementActionPolicyTest {
    @Test
    void managementKeepsMaintenanceActionsWithoutAccountCreation() {
        assertEquals(
                java.util.List.of("编辑档案", "修改联系方式", "变更状态", "状态历史"),
                StudentManagementActionPolicy.actions().stream()
                        .map(StudentManagementActionPolicy.Action::label)
                        .toList());
    }
}
