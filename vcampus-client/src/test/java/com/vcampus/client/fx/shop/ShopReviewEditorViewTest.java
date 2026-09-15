package com.vcampus.client.fx.shop;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ShopReviewEditorViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test void createRequiresStarsAndTrimmedFiveToThousandCharacters() throws Exception {
        fx(() -> {
            AtomicReference<ShopReviewEditorView.Submission> saved = new AtomicReference<>();
            ShopReviewEditorView view = new ShopReviewEditorView(new Listener(saved));
            new Scene(view, 900, 700);
            view.showCreate(31, 11, "校园笔记本");
            view.applyCss(); view.layout();
            TextArea content = (TextArea) view.lookup("#shop-review-editor-content");
            content.setText("  真的很好用  ");
            assertTrue(((Button) view.lookup("#shop-review-editor-submit")).isDisabled());
            ((Button) view.lookup("#shop-review-star-5")).fire();
            assertFalse(((Button) view.lookup("#shop-review-editor-submit")).isDisabled());
            ((Button) view.lookup("#shop-review-editor-submit")).fire();
            assertEquals(5, saved.get().rating());
            assertEquals("真的很好用", saved.get().content());
            assertTrue(((Label) view.lookup("#shop-review-editor-count")).getText().contains("5"));
            return null;
        });
    }

    @Test void editPrefillsAndExposesDelete() throws Exception {
        fx(() -> {
            ShopReviewEditorView view = new ShopReviewEditorView(new Listener(new AtomicReference<>()));
            new Scene(view, 900, 700);
            ShopData.Review review = new ShopData.Review(41, 11, 31, "小林", 4,
                    "原来的评价内容", false, 0, false, Instant.parse("2026-09-01T00:00:00Z"),
                    Instant.parse("2026-09-01T00:00:00Z"), List.of());
            view.showEdit(review, "校园笔记本");
            view.applyCss(); view.layout();
            assertEquals("原来的评价内容", ((TextArea) view.lookup("#shop-review-editor-content")).getText());
            assertTrue(((Button) view.lookup("#shop-review-star-4")).getStyleClass().contains("shop-review-star-selected"));
            assertTrue(((Button) view.lookup("#shop-review-editor-delete")).isVisible());
            return null;
        });
    }

    @Test void editTracksDirtyStateAndPersistsImageReordering() throws Exception {
        fx(() -> {
            ShopReviewEditorView view = new ShopReviewEditorView(new Listener(new AtomicReference<>()));
            new Scene(view, 900, 700);
            List<ShopData.ReviewImageRef> images = List.of(
                    image(71, 0), image(72, 1));
            ShopData.Review review = new ShopData.Review(41, 11, 31, "小林", 4,
                    "原来的评价内容", false, 0, false, Instant.parse("2026-09-01T00:00:00Z"),
                    Instant.parse("2026-09-01T00:00:00Z"), images);
            view.showEdit(review, "校园笔记本");
            view.applyCss(); view.layout();
            assertFalse(view.isDirty());

            ((Button) view.lookup("#shop-review-editor-existing-71-right")).fire();
            assertTrue(view.isDirty());
            assertEquals(List.of(72L, 71L), view.submission().imageOrder().stream()
                    .map(ShopReviewEditorView.ImageSelection::existingImageId).toList());
            view.markSaved();
            assertFalse(view.isDirty());
            return null;
        });
    }

    private static ShopData.ReviewImageRef image(long id, int position) {
        return new ShopData.ReviewImageRef(id, position, "image/png", 128,
                "%064x".formatted(id), 32, 32);
    }

    private record Listener(AtomicReference<ShopReviewEditorView.Submission> saved)
            implements ShopReviewEditorView.Listener {
        @Override public void save(ShopReviewEditorView.Submission submission) { saved.set(submission); }
        @Override public void delete(long reviewId) { }
        @Override public void cancel() { }
        @Override public void selectImage(java.nio.file.Path file) { }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }
}
