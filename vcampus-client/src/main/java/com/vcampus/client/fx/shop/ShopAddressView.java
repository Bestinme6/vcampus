package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class ShopAddressView extends BorderPane {
    interface Listener {
        void back();
        void save(Integer replaceSlot, ShippingAddressValue value);
        void delete(int slot);
    }

    private final Listener listener;
    private final VBox body = new VBox(16);
    private final VBox savedRows = new VBox(10);
    private final TextField recipient = field("收件人", "shop-address-recipient");
    private final TextField phone = field("联系电话", "shop-address-phone");
    private final TextField address = field("详细地址", "shop-address-text");
    private final ComboBox<Integer> replacement = new ComboBox<>();
    private final CheckBox replaceConfirmed = new CheckBox("确认覆盖所选地址");
    private final VBox replacementBox;
    private final Button save;
    private final Label status = ShopUi.label("", "shop-status");
    private List<ShopAddress> rows = List.of();

    ShopAddressView(Listener listener) {
        this.listener = listener;
        setId("shop-address-book");
        getStyleClass().add("shop-page");
        body.getStyleClass().add("shop-address-page");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("shop-scroll");
        setCenter(scroll);
        replacement.setId("shop-address-replace");
        replacement.setPromptText("选择要覆盖的地址");
        replaceConfirmed.setId("shop-address-replace-confirm");
        replacementBox = new VBox(8,
                ShopUi.label("已保存三条地址，请明确选择一条覆盖", "shop-hint"),
                replacement, replaceConfirmed);
        replacementBox.getStyleClass().add("shop-address-replacement");
        save = ShopUi.button("保存地址", "shop-primary", this::submit);
        save.setId("shop-address-save");
        status.setManaged(false);
        status.setVisible(false);
        VBox form = new VBox(10, ShopUi.label("新增收货地址", "shop-section-title"),
                new HBox(10, recipient, phone), address, replacementBox, status, save);
        form.getStyleClass().add("shop-address-form");
        body.getChildren().addAll(ShopUi.button("← 返回商店", "shop-quiet", listener::back),
                ShopUi.label("收货地址簿", "shop-detail-title"),
                ShopUi.label("普通商店与二手商店共用，最多保存三条。", "shop-muted"),
                savedRows, form);
        recipient.textProperty().addListener((ignored, before, after) -> update());
        phone.textProperty().addListener((ignored, before, after) -> update());
        address.textProperty().addListener((ignored, before, after) -> update());
        replacement.valueProperty().addListener((ignored, before, after) -> update());
        replaceConfirmed.selectedProperty().addListener((ignored, before, after) -> update());
        update();
    }

    void show(List<ShopAddress> value) {
        rows = List.copyOf(value);
        savedRows.getChildren().clear();
        for (ShopAddress row : rows) {
            VBox copy = new VBox(4,
                    ShopUi.label(row.value().recipient() + "  " + row.value().phone(),
                            "shop-card-title"),
                    ShopUi.label(row.value().address(), "shop-muted"));
            Button delete = ShopUi.button("删除", "shop-quiet",
                    () -> listener.delete(row.slot()));
            delete.setId("shop-address-delete-" + row.slot());
            HBox card = ShopUi.row(copy, ShopUi.space(), delete);
            card.getStyleClass().add("shop-address-card");
            savedRows.getChildren().add(card);
        }
        if (rows.isEmpty()) {
            savedRows.getChildren().add(ShopUi.label("还没有保存地址", "shop-note"));
        }
        replacement.getItems().setAll(rows.stream().map(ShopAddress::slot).toList());
        replacement.setValue(null);
        replaceConfirmed.setSelected(false);
        clearForm();
        update();
    }

    void busy(boolean value) {
        body.setDisable(value);
    }

    void failure(String message) {
        status.setText(message);
        status.setManaged(true);
        status.setVisible(true);
        status.getStyleClass().add("shop-error");
        busy(false);
    }

    private void submit() {
        ShippingAddressValue value = value();
        if (value == null || save.isDisabled()) return;
        listener.save(rows.size() >= 3 ? replacement.getValue() : null, value);
    }

    private void update() {
        boolean full = rows.size() >= 3;
        replacementBox.setManaged(full);
        replacementBox.setVisible(full);
        save.setDisable(value() == null || full
                && (replacement.getValue() == null || !replaceConfirmed.isSelected()));
    }

    private ShippingAddressValue value() {
        try {
            return new ShippingAddressValue(
                    recipient.getText(), phone.getText(), address.getText());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private void clearForm() {
        recipient.clear();
        phone.clear();
        address.clear();
        status.setText("");
        status.setManaged(false);
        status.setVisible(false);
    }

    private static TextField field(String prompt, String id) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setId(id);
        field.getStyleClass().add("shop-address-field");
        return field;
    }
}
