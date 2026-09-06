package com.vcampus.client.fx.academic;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseCatalogViewTest {
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
    void rendersSelectionAndPreservesSearchAcrossRefresh() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            CourseCatalogView view = new CourseCatalogView(listener);
            new Scene(view, 1000, 720);
            view.showPage(page());
            view.applyCss();
            view.layout();

            @SuppressWarnings("unchecked")
            TableView<AcademicData.Course> table =
                    (TableView<AcademicData.Course>) view.lookup("#academic-course-table");
            Button edit = (Button) view.lookup("#academic-course-edit");
            assertEquals(2, table.getItems().size());
            assertTrue(edit.isDisabled());
            table.getSelectionModel().selectFirst();
            assertFalse(edit.isDisabled());

            TextField keyword = (TextField) view.lookup("#academic-course-keyword");
            keyword.setText("数据");
            ((Button) view.lookup("#academic-course-search")).fire();
            assertEquals("数据", listener.keyword);
            assertEquals(1, listener.page);

            view.showPage(page());
            assertEquals("数据", keyword.getText());
            return null;
        });
    }

    @Test
    void busyStateDisablesEveryMutationEntryPoint() throws Exception {
        fx(() -> {
            CourseCatalogView view = new CourseCatalogView(new RecordingListener());
            new Scene(view, 1000, 720);
            view.showPage(page());
            view.busy(true);
            assertTrue(((Button) view.lookup("#academic-course-create")).isDisabled());
            assertTrue(((Button) view.lookup("#academic-course-search")).isDisabled());
            assertTrue(((TableView<?>) view.lookup("#academic-course-table")).isDisabled());
            return null;
        });
    }

    private static AcademicData.CoursePage page() {
        return new AcademicData.CoursePage(List.of(
                new AcademicData.Course(1, "C000001", "数据结构", new BigDecimal("4.0"),
                        64, "核心课程", true),
                new AcademicData.Course(2, "C000002", "大学英语", new BigDecimal("3.0"),
                        48, "公共课程", false)), 1, 8, 2);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static final class RecordingListener implements CourseCatalogView.Listener {
        private String keyword;
        private int page;

        @Override
        public void search(String keyword, int page) {
            this.keyword = keyword;
            this.page = page;
        }

        @Override
        public void create(AcademicCommands.CourseDraft draft) {
        }

        @Override
        public void update(long courseId, AcademicCommands.CourseDraft draft, boolean enabled) {
        }
    }
}
