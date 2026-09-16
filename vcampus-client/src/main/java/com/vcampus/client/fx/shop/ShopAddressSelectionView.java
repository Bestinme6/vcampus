package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShippingAddressValue;
import com.vcampus.common.model.ShopAddress;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class ShopAddressSelectionView extends VBox {
    private final Runnable changed;
    private final ToggleGroup choices = new ToggleGroup();
    private final VBox savedRows = new VBox(8);
    private final RadioButton inline = new RadioButton("填写新地址（仅本次使用）");
    private final TextField recipient = field("收件人", "shop-address-recipient");
    private final TextField phone = field("联系电话", "shop-address-phone");
    private final TextField address = field("详细地址", "shop-address-text");
    private final CheckBox save = new CheckBox("保存到共享地址簿");
    private final ComboBox<Integer> replacement = new ComboBox<>();
    private final CheckBox replaceConfirmed = new CheckBox("确认覆盖所选地址");
    private final VBox replacementBox;
    private List<ShopAddress> rows = List.of();

    ShopAddressSelectionView(Runnable changed) {
        super(12);
        this.changed = changed;
        getStyleClass().add("shop-address-selection");
        inline.setId("shop-address-inline");
        inline.setToggleGroup(choices);
        save.setId("shop-address-save-choice");
        replacement.setId("shop-address-replace");
        replacement.setPromptText("选择要覆盖的地址");
        replaceConfirmed.setId("shop-address-replace-confirm");
        replacementBox = new VBox(8,
                ShopUi.label("地址簿已满，请选择一条覆盖", "shop-hint"),
                replacement, replaceConfirmed);
        replacementBox.getStyleClass().add("shop-address-replacement");
        VBox form = new VBox(9, new HBox(10, recipient, phone), address, save, replacementBox);
        form.getStyleClass().add("shop-address-form");
        getChildren().addAll(ShopUi.label("收货地址", "shop-section-title"), savedRows,
                inline, form);
        choices.selectedToggleProperty().addListener((ignored, before, after) -> update());
        recipient.textProperty().addListener((ignored, before, after) -> update());
        phone.textProperty().addListener((ignored, before, after) -> update());
        address.textProperty().addListener((ignored, before, after) -> update());
        save.selectedProperty().addListener((ignored, before, after) -> update());
        replacement.valueProperty().addListener((ignored, before, after) -> update());
        replaceConfirmed.selectedProperty().addListener((ignored, before, after) -> update());
        update();
    }

    void show(List<ShopAddress> value) {
        rows = List.copyOf(value);
        savedRows.getChildren().clear();
        choices.getToggles().removeIf(toggle -> toggle != inline);
        for (ShopAddress row : rows) {
            RadioButton option = new RadioButton(row.value().recipient() + "  "
                    + row.value().phone() + "\n" + row.value().address());
            option.setUserData(row);
            option.setToggleGroup(choices);
            option.getStyleClass().add("shop-address-option");
            option.setId("shop-address-slot-" + row.slot());
            savedRows.getChildren().add(option);
        }
        replacement.getItems().setAll(rows.stream().map(ShopAddress::slot).toList());
        replacement.setValue(null);
        replaceConfirmed.setSelected(false);
        save.setSelected(false);
        recipient.clear();
        phone.clear();
        address.clear();
        if (savedRows.getChildren().isEmpty()) inline.setSelected(true);
        else ((RadioButton) savedRows.getChildren().getFirst()).setSelected(true);
        update();
    }

    Optional<ShippingAddressChoice> choice() {
        if (choices.getSelectedToggle() == null) return Optional.empty();
        Object selected = choices.getSelectedToggle().getUserData();
        if (selected instanceof ShopAddress row) {
            return Optional.of(new ShippingAddressChoice(row.slot(), row.version(), null));
        }
        try {
            return Optional.of(new ShippingAddressChoice(null, null,
                    new ShippingAddressValue(recipient.getText(), phone.getText(), address.getText())));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    boolean valid() {
        if (choice().isEmpty()) return false;
        return !saveRequested() || rows.size() < 3
                || replacement.getValue() != null && replaceConfirmed.isSelected();
    }

    boolean saveRequested() {
        return inline.isSelected() && save.isSelected();
    }

    Integer replacementSlot() {
        return rows.size() >= 3 && saveRequested() ? replacement.getValue() : null;
    }

    private void update() {
        boolean editing = inline.isSelected();
        recipient.setDisable(!editing);
        phone.setDisable(!editing);
        address.setDisable(!editing);
        save.setDisable(!editing);
        boolean replacing = editing && save.isSelected() && rows.size() >= 3;
        replacementBox.setManaged(replacing);
        replacementBox.setVisible(replacing);
        changed.run();
    }

    private static TextField field(String prompt, String id) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setId(id);
        field.getStyleClass().add("shop-address-field");
        return field;
    }
}
