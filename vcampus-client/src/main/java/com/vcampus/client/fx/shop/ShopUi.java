package com.vcampus.client.fx.shop;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

final class ShopUi {
    private ShopUi() { }

    static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    static Button button(String text, String style, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        button.setOnAction(event -> action.run());
        return button;
    }

    static HBox row(Node... nodes) {
        HBox row = new HBox(12, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static Region space() {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        return space;
    }

    static void grow(Pane node) { HBox.setHgrow(node, Priority.ALWAYS); }
}
