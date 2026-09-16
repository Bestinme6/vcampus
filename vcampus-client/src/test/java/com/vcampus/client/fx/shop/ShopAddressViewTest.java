package com.vcampus.client.fx.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ShopAddressViewTest {
    @BeforeAll static void toolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException started) { ready.countDown(); }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @Test void inlineSelectionBecomesValidOnlyAfterAllFieldsAreComplete() throws Exception {
        fx(() -> {
            ShopAddressSelectionView view = new ShopAddressSelectionView(() -> { });
            new Scene(view, 700, 500);
            view.show(List.of());
            layout(view);

            assertTrue(view.choice().isEmpty());
            ((TextField) view.lookup("#shop-address-recipient")).setText("张三");
            ((TextField) view.lookup("#shop-address-phone")).setText("13800000000");
            ((TextField) view.lookup("#shop-address-text")).setText("校内一号楼 101");

            assertEquals("校内一号楼 101",
                    view.choice().orElseThrow().inline().address());
            return null;
        });
    }

    @Test void fourthSavedAddressRequiresAnExplicitReplacementAndConfirmation() throws Exception {
        fx(() -> {
            AtomicInteger saves = new AtomicInteger();
            ShopAddressView view = new ShopAddressView(new ShopAddressView.Listener() {
                @Override public void back() { }
                @Override public void save(Integer replaceSlot, ShippingAddressValue value) {
                    assertEquals(2, replaceSlot);
                    saves.incrementAndGet();
                }
                @Override public void delete(int slot) { }
            });
            new Scene(view, 900, 700);
            view.show(List.of(address(1), address(2), address(3)));
            layout(view);
            fill(view);

            Button save = (Button) view.lookup("#shop-address-save");
            assertTrue(save.isDisabled());
            @SuppressWarnings("unchecked")
            ComboBox<Integer> replacement =
                    (ComboBox<Integer>) view.lookup("#shop-address-replace");
            replacement.setValue(2);
            assertTrue(save.isDisabled());
            ((CheckBox) view.lookup("#shop-address-replace-confirm")).setSelected(true);
            assertFalse(save.isDisabled());
            save.fire();
            assertEquals(1, saves.get());
            return null;
        });
    }

    private static void fill(javafx.scene.Parent view) {
        ((TextField) view.lookup("#shop-address-recipient")).setText("李四");
        ((TextField) view.lookup("#shop-address-phone")).setText("13900000000");
        ((TextField) view.lookup("#shop-address-text")).setText("校内二号楼 202");
    }

    private static ShopAddress address(int slot) {
        return new ShopAddress(slot, "version-" + slot,
                new ShippingAddressValue("收件人" + slot, "1380000000" + slot,
                        "测试校区" + slot + "号楼 101"));
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void layout(javafx.scene.Parent root) {
        root.applyCss();
        root.layout();
    }
}
