package com.vcampus.common.model;

import com.vcampus.common.protocol.Actions;
import com.vcampus.common.protocol.MessageCodec;
import com.vcampus.common.protocol.RequestMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationVocabularyTest {
    @Test
    void notificationRequestKeepsStableActionAndEnumValuesAcrossCodec() throws Exception {
        RequestMessage request = RequestMessage.create(
                Actions.NOTIFICATION_SEARCH,
                Map.of(
                        "source", NotificationSource.ACCOUNT_SECURITY.name(),
                        "target", NotificationTarget.STUDENT_GRADES.name(),
                        "type", NotificationType.GRADE_PUBLISHED.name()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MessageCodec.writeRequest(new DataOutputStream(output), request);
        RequestMessage decoded = MessageCodec.readRequest(
                new DataInputStream(new ByteArrayInputStream(output.toByteArray())));

        assertEquals("notification.search", decoded.action());
        assertEquals("ACCOUNT_SECURITY", decoded.parameters().get("source"));
        assertEquals("STUDENT_GRADES", decoded.parameters().get("target"));
        assertEquals("GRADE_PUBLISHED", decoded.parameters().get("type"));
        assertEquals("notification.markAllRead", Actions.NOTIFICATION_MARK_ALL_READ);
    }

    @Test
    void libraryNotificationAndActionVocabularySurvivesCodec() throws Exception {
        RequestMessage request = RequestMessage.create(
                Actions.LIBRARY_CATALOG_SEARCH,
                Map.of(
                        "source", NotificationSource.LIBRARY.name(),
                        "target", NotificationTarget.LIBRARY_LOANS.name(),
                        "type", NotificationType.LIBRARY_DUE_SOON.name()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MessageCodec.writeRequest(new DataOutputStream(output), request);
        RequestMessage decoded = MessageCodec.readRequest(
                new DataInputStream(new ByteArrayInputStream(output.toByteArray())));

        assertEquals("library.catalog.search", decoded.action());
        assertEquals("LIBRARY", decoded.parameters().get("source"));
        assertEquals("LIBRARY_LOANS", decoded.parameters().get("target"));
        assertEquals("LIBRARY_DUE_SOON", decoded.parameters().get("type"));
        assertEquals("LIBRARY_OVERDUE", NotificationType.LIBRARY_OVERDUE.name());
        List<String> typeNames = Arrays.stream(NotificationType.values()).map(Enum::name).toList();
        assertTrue(typeNames.containsAll(List.of(
                "LIBRARY_BORROWED", "LIBRARY_RENEWED", "LIBRARY_RETURNED", "LIBRARY_LOST")));
        assertEquals("library.admin.loan.return", Actions.LIBRARY_ADMIN_LOAN_RETURN);
    }

    @Test
    void forumNotificationVocabularyIsAvailableForCrossModuleNavigation() {
        assertEquals("FORUM", NotificationSource.FORUM.name());
        assertEquals("FORUM_POST", NotificationTarget.FORUM_POST.name());
        List<String> typeNames = Arrays.stream(NotificationType.values()).map(Enum::name).toList();
        assertTrue(typeNames.containsAll(List.of(
                "FORUM_POST_COMMENTED",
                "FORUM_POST_MODERATED",
                "FORUM_COMMENT_MODERATED")));
    }
}
