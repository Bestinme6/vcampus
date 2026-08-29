package com.vcampus.client.ui;

import com.vcampus.common.model.ModuleCode;
import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class WorkspaceCardResolver {
    private WorkspaceCardResolver() {
    }

    public static List<WorkspaceCardSpec> resolve(Set<UserRole> roles) {
        Objects.requireNonNull(roles, "roles");
        RoleCompositionPolicy.requireValid(roles);
        if (roles.contains(UserRole.SUPER_ADMIN)) {
            return superAdministratorCards();
        }
        if (roles.contains(UserRole.STUDENT)) {
            return studentCards(roles);
        }
        return teacherCards(roles);
    }

    private static List<WorkspaceCardSpec> studentCards(Set<UserRole> roles) {
        return List.of(
                card(ModuleCode.PERSONAL_PROFILE, "学", "学籍信息", "档案、联系方式与学籍状态"),
                card(ModuleCode.ACADEMIC, "教", "课程安排", "选课、课表与成绩"),
                card(ModuleCode.LIBRARY, "书",
                        roles.contains(UserRole.LIBRARY_ADMIN) ? "图书管理" : "图书借阅",
                        roles.contains(UserRole.LIBRARY_ADMIN) ? "馆藏、借阅与归还管理" : "检索、借阅与归还"),
                card(ModuleCode.SHOP, "商", "商店购物", "商品、购物车与订单"),
                card(ModuleCode.BANK, "银", "线上银行", "余额、转账与流水"),
                card(ModuleCode.FORUM, "论",
                        roles.contains(UserRole.FORUM_ADMIN) ? "论坛管理" : "校园论坛",
                        roles.contains(UserRole.FORUM_ADMIN) ? "帖子、评论与内容管理" : "帖子、评论与校园交流"));
    }

    private static List<WorkspaceCardSpec> teacherCards(Set<UserRole> roles) {
        List<WorkspaceCardSpec> cards = new ArrayList<>();
        if (roles.contains(UserRole.STUDENT_ADMIN)) {
            cards.add(card(ModuleCode.STUDENT_STATUS, "学", "学籍管理", "学生档案、班级与学籍状态"));
        }
        cards.add(card(ModuleCode.PERSONAL_PROFILE, "师", "教师信息", "工号、学院、职称与联系方式"));
        cards.add(card(ModuleCode.ACADEMIC, "教",
                roles.contains(UserRole.ACADEMIC_ADMIN) ? "教务管理" : "教学管理",
                roles.contains(UserRole.ACADEMIC_ADMIN) ? "课程、排课与成绩管理" : "授课、课表与成绩"));
        cards.add(card(ModuleCode.LIBRARY, "书",
                roles.contains(UserRole.LIBRARY_ADMIN) ? "图书管理" : "图书借阅",
                roles.contains(UserRole.LIBRARY_ADMIN) ? "馆藏、借阅与归还管理" : "检索、借阅与归还"));
        cards.add(card(ModuleCode.SHOP, "商",
                roles.contains(UserRole.SHOP_ADMIN) ? "商店管理" : "商店购物",
                roles.contains(UserRole.SHOP_ADMIN) ? "商品、库存与订单管理" : "商品、购物车与订单"));
        cards.add(card(ModuleCode.BANK, "银",
                roles.contains(UserRole.BANK_ADMIN) ? "银行管理" : "线上银行",
                roles.contains(UserRole.BANK_ADMIN) ? "账户、充值与流水管理" : "余额、转账与流水"));
        cards.add(card(ModuleCode.FORUM, "论",
                roles.contains(UserRole.FORUM_ADMIN) ? "论坛管理" : "校园论坛",
                roles.contains(UserRole.FORUM_ADMIN) ? "帖子、评论与内容管理" : "帖子、评论与校园交流"));
        return List.copyOf(cards);
    }

    private static List<WorkspaceCardSpec> superAdministratorCards() {
        return List.of(
                card(ModuleCode.STUDENT_STATUS, "学", "学籍管理", "学生档案、班级与学籍状态"),
                card(ModuleCode.ACADEMIC, "教", "教务管理", "课程、排课与成绩管理"),
                card(ModuleCode.LIBRARY, "书", "图书管理", "馆藏、借阅与归还管理"),
                card(ModuleCode.SHOP, "商", "商店管理", "商品、库存与订单管理"),
                card(ModuleCode.BANK, "银", "银行管理", "账户、充值与流水管理"),
                card(ModuleCode.FORUM, "论", "论坛管理", "帖子、评论与内容管理"));
    }

    private static WorkspaceCardSpec card(
            ModuleCode module, String iconText, String title, String description) {
        return new WorkspaceCardSpec(module, iconText, title, description);
    }
}
