package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleAvailabilityTest {
    @org.junit.jupiter.api.BeforeAll static void toolkit() throws Exception { ScheduleGridTest.toolkit(); }

    @Test void editorShowsRedBeforePublicationAndUpdatesWhenRoomOrWeeksChange() throws Exception {
        var task = new java.util.concurrent.FutureTask<Void>(() -> {
            var view = new ScheduleEditorView(new ScheduleEditorView.Listener() {
                public void save(AcademicCommands.ScheduleDraftCommand command) { }
                public void publish(AcademicCommands.SchedulePublishCommand command) { fail("No publication needed"); }
                public void reload(long id) { }
                public void back() { }
            });
            new javafx.scene.Scene(view, 1100, 760);
            view.showSection(new AcademicData.TeachingSection(1, 1, "2026秋", 1, "C000001", "课程甲",
                    java.math.BigDecimal.ONE, "01班", 10, "教师甲", 30, 0,
                    com.vcampus.common.model.CourseSectionStatus.PLANNED, false, "", "", null, null));
            view.showDraft(new AcademicData.ScheduleDraft(1, 1, 1,
                    com.vcampus.common.model.ScheduleRevisionStatus.DRAFT, List.of()));
            var teacher = new AcademicData.ScheduleOccupancy(2, 10, "教师甲", new ScheduleSlot(1, 1, 2, 1, 8, "A101"));
            var room = new AcademicData.ScheduleOccupancy(3, 11, "教师乙", new ScheduleSlot(2, 3, 4, 1, 16, "B202"));
            view.showOccupancy(List.of(teacher, room));
            view.applyCss(); view.layout();
            var grid = (ScheduleGrid) view.lookup(".academic-schedule-grid");
            assertEquals(2, redCells(grid));
            ((javafx.scene.control.TextField) view.lookup("#schedule-classroom")).setText(" b202 ");
            assertEquals(4, redCells(grid));
            @SuppressWarnings("unchecked") var from = (javafx.scene.control.Spinner<Integer>) view.lookup("#schedule-start-week");
            from.getValueFactory().setValue(9);
            assertEquals(2, redCells(grid));
            ((javafx.scene.control.TextField) view.lookup("#schedule-classroom")).setText("C303");
            assertEquals(0, redCells(grid));
            return null;
        });
        javafx.application.Platform.runLater(task);
        task.get(20, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static long redCells(ScheduleGrid grid) {
        return grid.getChildren().stream().filter(node -> node.getStyleClass().contains("academic-grid-blocked")).count();
    }
    @Test void existingDraftConflictsUseTheirOwnClassroomAndActualWeekOverlap() {
        var occupied = new AcademicData.ScheduleOccupancy(2, 10, "教师甲", new ScheduleSlot(1, 1, 2, 1, 8, "A101"));
        var conflicting = new ScheduleSlot(1, 2, 3, 8, 16, " a101 ");
        var later = new ScheduleSlot(1, 1, 2, 9, 16, "A101");
        var otherRoom = new ScheduleSlot(1, 1, 2, 1, 16, "B202");
        assertEquals(List.of(new ScheduleSlot(1, 2, 2, 8, 8, "a101")), ScheduleAvailability.conflicts(List.of(occupied), 1, 11,
                List.of(conflicting, later, otherRoom)));
    }

    @Test void decodesVersionedOccupancyWithoutLosingTeacherOrClassroom() throws Exception {
        var response = com.vcampus.common.protocol.ResponseMessage.success("test", "ok", java.util.Map.of(
                "schemaVersion", "2", "count", "1", "row.0", com.vcampus.common.protocol.RowCodec.encode(
                        "2", "10", "教师甲", "1", "1", "2", "1", "8", "A101")));
        assertEquals(List.of(new AcademicData.ScheduleOccupancy(2, 10, "教师甲",
                new ScheduleSlot(1, 1, 2, 1, 8, "A101"))), AcademicData.scheduleOccupancy(response));
    }
    @Test void filtersTeacherRoomWeeksAndExcludesOwnPublishedSection() {
        var teacher = new AcademicData.ScheduleOccupancy(2, 10, "教师甲", new ScheduleSlot(1, 1, 2, 1, 8, "A101"));
        var room = new AcademicData.ScheduleOccupancy(3, 11, "教师乙", new ScheduleSlot(2, 3, 4, 1, 16, "B202"));
        var own = new AcademicData.ScheduleOccupancy(1, 10, "教师甲", new ScheduleSlot(3, 1, 2, 1, 16, "B202"));
        var data = List.of(teacher, room, own);
        assertEquals(List.of(teacher.slot(), room.slot()), ScheduleAvailability.blocked(data, 1, 10, " b202 ", 1, 8));
        assertEquals(List.of(room.slot()), ScheduleAvailability.blocked(data, 1, 10, "B202", 9, 16));
        assertTrue(ScheduleAvailability.blocked(data, 1, 12, "C303", 1, 16).isEmpty());
    }
}
