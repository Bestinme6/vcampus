package com.vcampus.client.ui;

import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCardResolverTest {
    @Test
    void studentCardsUseStudentCopyAndElevateLibraryAndForumTitles() {
        List<WorkspaceCardSpec> cards = WorkspaceCardResolver.resolve(Set.of(
                UserRole.STUDENT,
                UserRole.LIBRARY_ADMIN,
                UserRole.FORUM_ADMIN));

        assertEquals(List.of("学", "教", "书", "商", "银", "论"), icons(cards));
        assertEquals(List.of(
                "学籍信息", "课程安排", "图书管理", "商店购物", "线上银行", "论坛管理"),
                titles(cards));
    }

    @Test
    void studentAdministratorTeacherGetsSevenCardsInApprovedOrder() {
        List<WorkspaceCardSpec> cards = WorkspaceCardResolver.resolve(Set.of(
                UserRole.TEACHER,
                UserRole.STUDENT_ADMIN));

        assertEquals(List.of("学", "师", "教", "书", "商", "银", "论"), icons(cards));
        assertEquals(List.of(
                "学籍管理", "教师信息", "教学管理", "图书借阅", "商店购物", "线上银行", "校园论坛"),
                titles(cards));
    }

    @Test
    void teacherAdministratorTitlesReplaceOnlyTheirBusinessCards() {
        List<WorkspaceCardSpec> cards = WorkspaceCardResolver.resolve(Set.of(
                UserRole.TEACHER,
                UserRole.ACADEMIC_ADMIN,
                UserRole.SHOP_ADMIN,
                UserRole.BANK_ADMIN));

        assertEquals(List.of(
                "教师信息", "教务管理", "图书借阅", "商店管理", "银行管理", "校园论坛"),
                titles(cards));
    }

    @Test
    void superAdministratorGetsSixManagementCardsOnly() {
        List<WorkspaceCardSpec> cards = WorkspaceCardResolver.resolve(Set.of(UserRole.SUPER_ADMIN));

        assertEquals(List.of("学", "教", "书", "商", "银", "论"), icons(cards));
        assertEquals(List.of(
                "学籍管理", "教务管理", "图书管理", "商店管理", "银行管理", "论坛管理"),
                titles(cards));
    }

    @Test
    void invalidRoleCombinationCannotProduceAWorkspace() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceCardResolver.resolve(Set.of(UserRole.STUDENT, UserRole.TEACHER)));
    }

    @Test
    void everySupportedIdentityKeepsALibraryEntry() {
        List<Set<UserRole>> roleSets = List.of(
                Set.of(UserRole.STUDENT),
                Set.of(UserRole.TEACHER),
                Set.of(UserRole.STUDENT, UserRole.LIBRARY_ADMIN),
                Set.of(UserRole.TEACHER, UserRole.LIBRARY_ADMIN),
                Set.of(UserRole.SUPER_ADMIN));
        for (Set<UserRole> roles : roleSets) {
            assertTrue(WorkspaceCardResolver.resolve(roles).stream()
                    .anyMatch(card -> card.module() == com.vcampus.common.model.ModuleCode.LIBRARY));
        }
    }

    private List<String> icons(List<WorkspaceCardSpec> cards) {
        return cards.stream().map(WorkspaceCardSpec::iconText).toList();
    }

    private List<String> titles(List<WorkspaceCardSpec> cards) {
        return cards.stream().map(WorkspaceCardSpec::title).toList();
    }
}
