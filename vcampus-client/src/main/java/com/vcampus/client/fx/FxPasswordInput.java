package com.vcampus.client.fx;

import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.feather.Feather;

final class FxPasswordInput extends HBox {
    final PasswordField hidden = new PasswordField();
    private final TextField shown = new TextField();
    FxPasswordInput(String id) {
        hidden.setId(id); shown.setId(id + "-visible");
        hidden.setPromptText("请输入密码"); shown.setPromptText("请输入密码");
        hidden.setAccessibleText("密码"); shown.setAccessibleText("密码（可见）");
        shown.textProperty().bindBidirectional(hidden.textProperty());
        shown.setVisible(false); shown.setManaged(false);
        StackPane input = new StackPane(hidden, shown); HBox.setHgrow(input, Priority.ALWAYS);
        Button eye = FxStyles.button("", Feather.EYE, () -> {
            boolean reveal = !shown.isVisible();
            shown.setVisible(reveal); shown.setManaged(reveal);
            hidden.setVisible(!reveal); hidden.setManaged(!reveal);
            if (reveal) shown.requestFocus(); else hidden.requestFocus();
        }, "eye-button");
        eye.setAccessibleText("显示或隐藏密码");
        getStyleClass().add("password-input"); getChildren().addAll(input, eye);
    }
    char[] value() { return hidden.getText().toCharArray(); }
    void clear() { hidden.clear(); }
}
