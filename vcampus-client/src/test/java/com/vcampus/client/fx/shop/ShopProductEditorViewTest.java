package com.vcampus.client.fx.shop;

import com.vcampus.client.fx.shop.ShopData.ImageRef;
import com.vcampus.common.model.ShopCategory;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ShopProductEditorViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }

    @Test void deletingCoverPromotesFirstRemainingImage() {
        ShopImageDraft draft = new ShopImageDraft(List.of(
                ShopImageDraft.DraftImage.existing(new ImageRef(11, "a".repeat(64))),
                ShopImageDraft.DraftImage.existing(new ImageRef(12, "b".repeat(64)))));
        draft.setCover(11);
        draft.remove(11);
        assertEquals(12, draft.images().getFirst().existingImageId());
        assertTrue(draft.images().getFirst().cover());
    }

    @Test void draftCapsAtFiveAndPreservesOrderInPlan() {
        ShopImageDraft draft = new ShopImageDraft(List.of());
        for (int index = 0; index < 5; index++) draft.addUpload(Path.of("image-" + index + ".png"));
        assertThrows(IllegalStateException.class, () -> draft.addUpload(Path.of("too-many.png")));
        draft.move(4, 1);
        draft.setCover(draft.images().get(1).key());
        assertEquals(5, draft.images().size());
        assertTrue(draft.images().get(1).cover());
    }

    @Test void editorUsesFixedCategoriesReadOnlySkuAndValidatesFields() throws Exception {
        fx(() -> {
            ShopProductEditorView view = new ShopProductEditorView((input, images) -> { }, () -> { }, path -> { });
            new Scene(view, 1000, 760); view.showNew(); view.applyCss(); view.layout();
            @SuppressWarnings("unchecked") ComboBox<ShopCategory> category =
                    (ComboBox<ShopCategory>) view.lookup("#shop-editor-category");
            assertEquals(List.of(ShopCategory.values()), category.getItems());
            assertEquals("学习文具", category.getConverter().toString(ShopCategory.LEARNING_STATIONERY));
            TextField sku = (TextField) view.lookup("#shop-editor-sku");
            assertFalse(sku.isEditable());
            view.busy(true);
            assertTrue(((Button) view.lookup("#shop-editor-save")).isDisabled());
            assertTrue(((Button) view.lookup("#shop-editor-add-image")).isDisabled());
            assertFalse(view.lookup("#shop-editor-name").isDisabled());
            assertThrows(IllegalArgumentException.class, () -> view.values("", "9.99", "x"));
            assertThrows(IllegalArgumentException.class, () -> view.values("商品", "-1", "x"));
            assertThrows(IllegalArgumentException.class, () -> view.values("商品", "9.99", "x".repeat(1001)));
            return null;
        });
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }
}
