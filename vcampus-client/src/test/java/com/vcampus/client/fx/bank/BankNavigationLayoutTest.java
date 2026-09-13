package com.vcampus.client.fx.bank;

import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankNavigationLayoutTest {
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
    void personalAndManagementGroupsUseTwoLevelTopNavigation() throws Exception {
        fx(() -> {
            List<String> requested = new ArrayList<>();
            BankView view = new BankView(Set.of(UserRole.TEACHER, UserRole.BANK_ADMIN),
                    requested::add, () -> { });
            new Scene(view, 1000, 720);
            view.applyCss();
            view.layout();

            assertNull(view.getLeft());
            assertNotNull(view.lookup("#bank-primary-navigation"));
            assertNotNull(view.lookup("#bank-secondary-navigation"));
            assertEquals("个人银行", ((Button) view.lookup("#bank-group-personal")).getText());
            assertEquals("银行管理", ((Button) view.lookup("#bank-group-management")).getText());
            assertNotNull(view.lookup("#bank-nav-home"));
            assertNull(view.lookup("#bank-nav-admin"));

            view.page(new Pane(), "bank-admin");
            assertNull(view.lookup("#bank-nav-home"));
            assertNotNull(view.lookup("#bank-nav-admin"));
            assertNotNull(view.lookup("#bank-nav-admin-ledger"));

            ((Button) view.lookup("#bank-group-personal")).fire();
            ((Button) view.lookup("#bank-group-management")).fire();
            assertEquals(List.of("bank", "bank-admin"), requested);
            return null;
        });
    }

    @Test
    void ordinaryUserDoesNotSeeManagementGroup() throws Exception {
        fx(() -> {
            BankView view = new BankView(Set.of(UserRole.STUDENT), route -> { }, () -> { });
            new Scene(view, 1000, 720);

            assertNotNull(view.lookup("#bank-group-personal"));
            assertNull(view.lookup("#bank-group-management"));
            assertNotNull(view.lookup("#bank-nav-transfer"));
            return null;
        });
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }
}
