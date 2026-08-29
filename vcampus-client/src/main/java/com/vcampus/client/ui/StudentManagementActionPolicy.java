package com.vcampus.client.ui;

import java.util.List;

final class StudentManagementActionPolicy {
    private static final List<Action> ACTIONS = List.of(
            new Action(ActionCode.EDIT_PROFILE, "编辑档案"),
            new Action(ActionCode.EDIT_CONTACT, "修改联系方式"),
            new Action(ActionCode.CHANGE_STATUS, "变更状态"),
            new Action(ActionCode.STATUS_HISTORY, "状态历史"));

    private StudentManagementActionPolicy() {
    }

    static List<Action> actions() {
        return ACTIONS;
    }

    enum ActionCode {
        EDIT_PROFILE, EDIT_CONTACT, CHANGE_STATUS, STATUS_HISTORY
    }

    record Action(ActionCode code, String label) {
    }
}
