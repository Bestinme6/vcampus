package com.vcampus.client.fx.academic;

import com.vcampus.common.model.ScheduleSlot;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleGridTest {
    @BeforeAll
    static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException started) {
            ready.countDown();
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void publishedCourseCardSpansPeriodsAndShowsCompleteCourseInformation() throws Exception {
        fx(() -> {
            ScheduleGrid grid = new ScheduleGrid(slot -> { });
            AcademicData.ScheduleEntry entry = new AcademicData.ScheduleEntry(
                    301, 7, "2026 秋", "CS2101", "面向对象程序设计",
                    new BigDecimal("3.5"), "01 班", "张老师",
                    new ScheduleSlot(1, 1, 2, 1, 16, "教一-401"));

            grid.setCourseEntries(List.of(entry));

            Label card = (Label) grid.getChildren().stream()
                    .filter(node -> "academic-course-card-301-1-1".equals(node.getId()))
                    .findFirst().orElseThrow();
            assertTrue(card.getText().contains("面向对象程序设计"));
            assertTrue(card.getText().contains("3.5 学分"));
            assertTrue(card.getText().contains("张老师"));
            assertTrue(card.getText().contains("教一-401"));
            assertEquals(1, GridPane.getColumnIndex(card));
            assertEquals(1, GridPane.getRowIndex(card));
            assertEquals(2, GridPane.getRowSpan(card));
            assertTrue(card.getStyleClass().contains("academic-course-card"));
            assertTrue(card.getAccessibleText().contains("教师张老师"));
            assertTrue(card.getTooltip().getText().contains("教室：教一-401"));
            return null;
        });
    }

    @Test
    void singlePeriodCardUsesCompactLayoutWithoutDroppingLongCourseInformation() throws Exception {
        fx(() -> {
            ScheduleGrid grid = new ScheduleGrid(slot -> { });
            AcademicData.ScheduleEntry entry = new AcademicData.ScheduleEntry(
                    302, 7, "2026 秋", "GE1308", "科学、技术与社会专题研讨",
                    new BigDecimal("2"), "荣誉实验班", "周嘉宁老师",
                    new ScheduleSlot(5, 7, 7, 1, 8, "人文楼-报告厅"));

            grid.setCourseEntries(List.of(entry));

            Label card = (Label) grid.getChildren().stream()
                    .filter(node -> "academic-course-card-302-5-7".equals(node.getId()))
                    .findFirst().orElseThrow();
            assertTrue(card.getStyleClass().contains("academic-course-card-single"));
            assertTrue(card.getText().contains("科学、技术与社会专题研讨"));
            assertTrue(card.getText().contains("2 学分"));
            assertTrue(card.getText().contains("周嘉宁老师"));
            assertTrue(card.getText().contains("人文楼-报告厅"));
            assertEquals(1, GridPane.getRowSpan(card));
            assertEquals(60, grid.getRowConstraints().get(7).getPrefHeight());
            return null;
        });
    }

    @Test
    void dragCreatesOneContiguousSlotAndStopsBeforeBlockedCell() {
        ScheduleGrid.Selection result = ScheduleGrid.select(
                new ScheduleGrid.Cell(1, 1), new ScheduleGrid.Cell(1, 4),
                Set.of(new ScheduleGrid.Cell(1, 3)), 1, 16, "教一-101");

        assertEquals(List.of(new ScheduleSlot(1, 1, 2, 1, 16, "教一-101")), result.slots());
        assertTrue(result.hitBlockedCell());
    }

    @Test
    void dragRemainsOnTheStartingWeekday() {
        ScheduleGrid.Selection result = ScheduleGrid.select(
                new ScheduleGrid.Cell(2, 5), new ScheduleGrid.Cell(4, 7),
                Set.of(), 3, 12, "实验楼-204");

        assertEquals(List.of(new ScheduleSlot(2, 5, 7, 3, 12, "实验楼-204")), result.slots());
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
}
