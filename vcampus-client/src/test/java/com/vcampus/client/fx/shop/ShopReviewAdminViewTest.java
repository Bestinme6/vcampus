package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopReviewStatus;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopReviewAdminViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test void normalHiddenAndDeletedRowsExposeCorrectModerationActions() throws Exception {
        fx(() -> {
            Recording listener = new Recording();
            ShopReviewAdminView view = new ShopReviewAdminView(listener);
            new Scene(view, 1000, 700);
            view.show(page(ShopReviewStatus.NORMAL)); view.applyCss(); view.layout();
            assertNotNull(view.lookup("#shop-review-admin-hide-41"));
            view.moderate(41, ShopReviewStatus.NORMAL, " 内容不当 ");
            assertEquals("hide:41:内容不当", listener.action);
            view.show(page(ShopReviewStatus.HIDDEN)); view.applyCss(); view.layout();
            assertNotNull(view.lookup("#shop-review-admin-restore-41"));
            view.show(page(ShopReviewStatus.DELETED)); view.applyCss(); view.layout();
            assertNull(view.lookup("#shop-review-admin-hide-41"));
            assertThrows(IllegalArgumentException.class,
                    () -> view.moderate(41, ShopReviewStatus.NORMAL, "短"));
            return null;
        });
    }

    @Test void paginationKeepsCurrentFiltersAndRequestsAdjacentPage() throws Exception {
        fx(() -> {
            Recording listener = new Recording();
            ShopReviewAdminView view = new ShopReviewAdminView(listener);
            new Scene(view, 1000, 700);
            view.show(new ShopData.AdminReviewPage(
                    page(ShopReviewStatus.NORMAL).rows(), 2, 10, 25));
            view.applyCss(); view.layout();

            Button previous = (Button) view.lookup("#shop-review-admin-previous");
            Button next = (Button) view.lookup("#shop-review-admin-next");
            assertNotNull(previous);
            assertNotNull(next);
            previous.fire();
            assertEquals(1, listener.query.page());
            next.fire();
            assertEquals(3, listener.query.page());
            return null;
        });
    }

    private static ShopData.AdminReviewPage page(ShopReviewStatus status) {
        ShopData.Review review = new ShopData.Review(41, 11, 31, "小林", 5, "评价内容符合长度",
                false, 2, false, Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), List.of());
        return new ShopData.AdminReviewPage(List.of(new ShopData.AdminReview(review, status)), 1, 20, 1);
    }

    private static final class Recording implements ShopReviewAdminView.Listener {
        String action;
        ShopReviewAdminView.Query query;
        @Override public void search(ShopReviewAdminView.Query query) { this.query = query; }
        @Override public void hide(long reviewId, String reason) { action = "hide:" + reviewId + ":" + reason; }
        @Override public void restore(long reviewId, String reason) { action = "restore:" + reviewId + ":" + reason; }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }
}
