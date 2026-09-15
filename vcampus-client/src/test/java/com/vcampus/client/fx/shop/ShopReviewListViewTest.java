package com.vcampus.client.fx.shop;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopReviewListViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test void preservesServerOrderAndRendersLabelsImagesLikesAndPaging() throws Exception {
        fx(() -> {
            RecordingListener listener = new RecordingListener();
            ShopReviewListView view = new ShopReviewListView(listener);
            new Scene(view, 900, 700);
            ShopData.Review first = review(41, 9, true, true, 2,
                    Instant.parse("2026-09-02T00:00:00Z"));
            ShopData.Review second = review(42, 3, false, false, 0,
                    Instant.parse("2026-09-01T00:00:00Z"));
            view.show(11, new ShopData.ReviewPage(List.of(first, second), 1, 10, 12,
                    new ShopData.RatingSummary(new BigDecimal("4.0"), 12, List.of(0, 0, 3, 6, 3))));
            view.applyCss(); view.layout();

            assertEquals(List.of("shop-review-card-41", "shop-review-card-42"),
                    view.cards().stream().map(javafx.scene.Node::getId).toList());
            assertTrue(((Label) view.lookup("#shop-review-badges-41")).getText().contains("回购"));
            assertTrue(((Label) view.lookup("#shop-review-edited-41")).isVisible());
            assertEquals(2, view.lookupAll(".shop-review-image").size());
            Button like = (Button) view.lookup("#shop-review-like-41");
            assertTrue(like.getStyleClass().contains("shop-review-like-selected"));
            like.fire();
            assertEquals(List.of("41:false"), listener.likes);
            ((Button) view.lookup("#shop-review-next")).fire();
            assertEquals(2, listener.page);
            return null;
        });
    }

    @Test void rendersLoadingEmptyAndLocalFailure() throws Exception {
        fx(() -> {
            ShopReviewListView view = new ShopReviewListView(new RecordingListener());
            new Scene(view, 800, 600);
            view.showLoading(11);
            assertNotNull(view.lookup("#shop-review-loading"));
            view.show(11, new ShopData.ReviewPage(List.of(), 1, 10, 0, ShopData.RatingSummary.empty()));
            assertNotNull(view.lookup("#shop-review-empty"));
            view.showFailure(11, "评价暂不可用", () -> { });
            assertEquals("评价暂不可用", ((Label) view.lookup("#shop-review-error")).getText());
            return null;
        });
    }

    private static ShopData.Review review(long id, int likes, boolean liked, boolean repeat,
                                          int imageCount, Instant updatedAt) {
        List<ShopData.ReviewImageRef> images = new ArrayList<>();
        for (int index = 0; index < imageCount; index++) {
            images.add(new ShopData.ReviewImageRef(70 + id + index, index, "image/png", 100,
                    String.valueOf(index + 1).repeat(64), 20, 20));
        }
        return new ShopData.Review(id, 11, 31 + id, "小林", id == 41 ? 5 : 3,
                "商品质量不错，符合预期", repeat, likes, liked,
                Instant.parse("2026-09-01T00:00:00Z"), updatedAt, images);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static final class RecordingListener implements ShopReviewListView.Listener {
        int page;
        final List<String> likes = new ArrayList<>();
        @Override public void page(long productId, int page) { this.page = page; }
        @Override public void like(long reviewId, boolean enabled) { likes.add(reviewId + ":" + enabled); }
        @Override public void openImage(ShopData.ReviewImageRef image) { }
    }
}
