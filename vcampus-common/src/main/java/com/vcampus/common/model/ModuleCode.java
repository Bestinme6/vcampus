package com.vcampus.common.model;

public enum ModuleCode {
    AUTH("统一登录门户"),
    PERSONAL_PROFILE("个人信息"),
    STUDENT_STATUS("虚拟学籍管理"),
    ACADEMIC("虚拟教务管理"),
    LIBRARY("虚拟图书馆"),
    SHOP("虚拟商店"),
    BANK("虚拟银行"),
    FORUM("校园论坛");

    private final String displayName;

    ModuleCode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
