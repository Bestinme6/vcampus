package com.vcampus.client.fx;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

final class FxStyles {
    private FxStyles() { }
    static void install(Parent root) {
        root.getStylesheets().add(FxStyles.class.getResource("campus.css").toExternalForm());
    }
    static Label label(String text, String style) {
        Label label = new Label(text); label.getStyleClass().add(style); return label;
    }
    static FontIcon icon(Feather icon, int size) {
        return FontIcon.of(icon, size);
    }
    static Button button(String text, Feather glyph, Runnable action, String style) {
        Button button = new Button(text, glyph == null ? null : icon(glyph, 19));
        button.getStyleClass().add(style); button.setOnAction(e -> action.run());
        return button;
    }
    static Region spacer() { Region r = new Region(); HBox.setHgrow(r, Priority.ALWAYS); return r; }
    static HBox row(Node... children) { HBox box = new HBox(12, children); box.setAlignment(Pos.CENTER_LEFT); return box; }
}
