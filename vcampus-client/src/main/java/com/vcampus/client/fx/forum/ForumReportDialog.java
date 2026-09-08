package com.vcampus.client.fx.forum;

import com.vcampus.common.model.ForumReportReason;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.Collection;
import java.util.Optional;

final class ForumReportDialog {
    record Submission(ForumReportReason reason, String detail) { }

    private ForumReportDialog() { }

    static Optional<Submission> show(Window owner, Collection<String> stylesheets) {
        Dialog<Submission> dialog = new Dialog<>();
        dialog.setTitle("举报内容");
        dialog.setHeaderText("请选择举报原因");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getStyleClass().add("forum-root");
        dialog.getDialogPane().getStylesheets().addAll(stylesheets);
        ButtonType submit = new ButtonType("提交举报", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(submit, ButtonType.CANCEL);

        ComboBox<ForumReportReason> reason = new ComboBox<>();
        reason.getItems().setAll(ForumReportReason.values());
        reason.setValue(ForumReportReason.SPAM);
        reason.setConverter(new StringConverter<>() {
            @Override public String toString(ForumReportReason value) {
                if (value == null) return "";
                return switch (value) {
                    case SPAM -> "垃圾广告";
                    case HARASSMENT -> "辱骂或骚扰";
                    case MISLEADING -> "虚假或误导信息";
                    case PRIVACY -> "泄露隐私";
                    case INAPPROPRIATE -> "不当内容";
                    case OTHER -> "其他";
                };
            }
            @Override public ForumReportReason fromString(String text) { return null; }
        });
        TextArea detail = new TextArea();
        detail.setPromptText("补充说明（选填，最多300字）");
        detail.setWrapText(true);
        detail.setPrefRowCount(4);
        Label counter = ForumStyles.label("0 / 300", "forum-caption");
        detail.textProperty().addListener((ignored, before, value) -> {
            if (value.length() > 300) detail.setText(value.substring(0, 300));
            else counter.setText(value.length() + " / 300");
        });
        dialog.getDialogPane().setContent(new VBox(10, reason, detail, counter));
        dialog.setResultConverter(button -> button == submit
                ? new Submission(reason.getValue(), detail.getText().strip()) : null);
        return dialog.showAndWait();
    }
}
