package com.vcampus.client.fx;

import com.vcampus.client.ui.NotificationDestination;
import com.vcampus.common.model.NotificationTarget;
import com.vcampus.common.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotificationCampusIntegrationTest {
    @Test void resolvesEveryMessageDestinationToANativeCampusRoute(){
        assertEquals("academic-teacher-schedule",CampusApplication.notificationRoute(destination(NotificationTarget.TEACHER_SCHEDULE,null),Set.of(UserRole.TEACHER)));
        assertEquals("academic-section/81",CampusApplication.notificationRoute(destination(NotificationTarget.ACADEMIC_SCHEDULE,81L),Set.of(UserRole.ACADEMIC_ADMIN)));
        assertEquals("academic-student-schedule",CampusApplication.notificationRoute(destination(NotificationTarget.ACADEMIC_SCHEDULE,81L),Set.of(UserRole.STUDENT)));
        assertEquals("academic-teacher-schedule",CampusApplication.notificationRoute(destination(NotificationTarget.ACADEMIC_SCHEDULE,81L),Set.of(UserRole.TEACHER)));
        assertEquals("academic-grades",CampusApplication.notificationRoute(destination(NotificationTarget.STUDENT_GRADES,null),Set.of(UserRole.STUDENT)));
        assertEquals("personal-profile",CampusApplication.notificationRoute(destination(NotificationTarget.STUDENT_PROFILE,null),Set.of(UserRole.STUDENT)));
        assertEquals("library-loans",CampusApplication.notificationRoute(destination(NotificationTarget.LIBRARY_LOANS,null),Set.of(UserRole.STUDENT)));
        assertEquals("library-book/27",CampusApplication.notificationRoute(destination(NotificationTarget.LIBRARY_CATALOG,27L),Set.of(UserRole.STUDENT)));
        assertEquals("forum-post/31",CampusApplication.notificationRoute(destination(NotificationTarget.FORUM_POST,31L),Set.of(UserRole.STUDENT)));
        assertEquals("bank-ledger",CampusApplication.notificationRoute(destination(NotificationTarget.BANK_LEDGER,null),Set.of(UserRole.STUDENT)));
        assertEquals("shop-order/42",CampusApplication.notificationRoute(destination(NotificationTarget.SHOP_ORDERS,42L),Set.of(UserRole.STUDENT)));
        assertNull(CampusApplication.notificationRoute(destination(NotificationTarget.NONE,null),Set.of(UserRole.STUDENT)));
        assertNull(CampusApplication.notificationRoute(destination(NotificationTarget.FORUM_POST,null),Set.of(UserRole.STUDENT)));
    }

    private static NotificationDestination destination(NotificationTarget target,Long id){return new NotificationDestination(target,id);}
}
