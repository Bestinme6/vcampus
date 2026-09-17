package com.vcampus.client.fx.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.SecondhandCategory;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SecondhandItemEditorViewTest {
    @BeforeAll
    static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test
    void newListingUsesRequiredCategoryChoicesAndSubmitsImageDraft() throws Exception {
        fx(() -> {
            AtomicReference<SecondhandData.ListingDraft> saved = new AtomicReference<>();
            AtomicReference<SecondhandImageDraft> images = new AtomicReference<>();
            SecondhandItemEditorView view = new SecondhandItemEditorView(2L,
                    (draft, imageDraft) -> { saved.set(draft); images.set(imageDraft); },
                    id -> { }, () -> { }, path -> { });
            new Scene(view, 900, 700);
            view.showNew();
            view.applyCss();
            view.layout();

            @SuppressWarnings("unchecked")
            ComboBox<SecondhandCategory> category =
                    (ComboBox<SecondhandCategory>) view.lookup("#used-item-category");
            assertNotNull(category);
            assertEquals(SecondhandCategory.OTHER, category.getValue());
            assertEquals(SecondhandCategory.values().length, category.getItems().size());
            category.setValue(SecondhandCategory.BOOK);
            ((TextField) view.lookup("#used-item-title")).setText("旧教材");
            ((TextArea) view.lookup("#used-item-description")).setText("含少量笔记");
            ((TextField) view.lookup("#used-item-condition")).setText("九成新");
            ((TextField) view.lookup("#used-item-price")).setText("12.00");
            view.addImage(Path.of("book.png"), new byte[]{1, 2, 3});
            ((Button) view.lookup("#used-item-save")).fire();

            assertEquals("BOOK", saved.get().category());
            assertSame(view.draft(), images.get());
            assertEquals(1, images.get().images().size());
            return null;
        });
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
}
