package com.vcampus.client.fx.identity;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityUiTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        try{Platform.startup(ready::countDown);}catch(IllegalStateException started){ready.countDown();}
        assertTrue(ready.await(15, TimeUnit.SECONDS)); Platform.setImplicitExit(false);
    }
    @Test void statusChipIsSemanticAndStylesheetInstallIsIdempotent() throws Exception {
        FutureTask<Void> task=new FutureTask<>(()->{
            Label chip=IdentityUi.statusChip("在籍","positive");
            assertEquals("学籍状态：在籍",chip.getAccessibleText());
            assertTrue(chip.getStyleClass().contains("identity-status-positive"));
            VBox root=IdentityUi.card(chip); new Scene(root);
            IdentityUi.install(root); IdentityUi.install(root);
            assertEquals(1,root.getStylesheets().stream().filter(s->s.endsWith("identity.css")).count());
            assertTrue(root.getStyleClass().contains("identity-card")); return null;
        });
        Platform.runLater(task); task.get(15,TimeUnit.SECONDS);
    }
}
