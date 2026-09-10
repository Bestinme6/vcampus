package com.vcampus.client.fx.bank;

import com.vcampus.common.model.BankAccountStatus;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BankAdminLayoutTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); } catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test void verifiedAccountKeepsItsColumnAcrossRepeatedLayoutsAndResizes() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            BankAdminView view = new BankAdminView(new BankAdminView.Listener() {
                public void search(String k, BankAccountStatus s, int p) { }
                public void lookup(String u) { }
                public void topUp(BankData.Recipient r, String a) { }
                public void status(BankData.Recipient r, boolean f) { }
                public void ledger(String u) { }
            });
            new Scene(view, 901, 600);
            view.getStyleClass().add("bank-root");
            view.getStylesheets().add(BankAdminView.class.getResource("bank.css").toExternalForm());
            view.select(new BankData.Recipient("2026000001", "学生01", false), null);
            Pane columns = (Pane) ((VBox) view.getContent()).getChildren().getLast();
            var list = columns.getChildren().getFirst();
            var selection = columns.getChildren().getLast();
            for (int width : new int[]{901, 900, 1001, 800, 790, 780, 600, 901}) {
                view.resize(width, 600);
                for (int pass = 0; pass < 20; pass++) { view.applyCss(); view.layout(); }
                double x = selection.getLayoutX(), y = selection.getLayoutY();
                for (int pass = 0; pass < 20; pass++) {
                    view.requestLayout(); view.layout();
                    assertEquals(x, selection.getLayoutX(), .1, "unstable x at width " + width);
                    assertEquals(y, selection.getLayoutY(), .1, "unstable y at width " + width);
                }
                if (width >= 800) {
                    assertEquals(list.getLayoutY(), y, .1, "wide layout must keep account alongside list: " + width);
                    assertTrue(x > list.getLayoutX());
                } else if (width == 600) {
                    assertTrue(y > list.getLayoutY(), "narrow layout must stack cards");
                }
            }
            return null;
        });
        Platform.runLater(task); task.get(30, TimeUnit.SECONDS);
    }
}
