package com.vcampus.client.fx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class FxLoginView extends HBox {
    record Credentials(String username, char[] password) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    private final TextField username = new TextField();
    private final FxPasswordInput password = new FxPasswordInput("login-password");
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final Label status = FxStyles.label("", "status");
    private final Button login;
    private final Button test;
    FxLoginView(ServerConnection connection, BiConsumer<ServerConnection, Credentials> onLogin,
                Consumer<ServerConnection> onTest) {
        setId("login-view"); getStyleClass().add("login-view");
        StackPane illustration = new StackPane(); illustration.getStyleClass().add("illustration");
        ImageView artwork = new ImageView(new Image(FxLoginView.class.getResource("seu-auditorium.png").toExternalForm()));
        artwork.setPreserveRatio(true); artwork.setSmooth(true);
        artwork.fitWidthProperty().bind(illustration.widthProperty());
        artwork.fitHeightProperty().bind(illustration.heightProperty());
        StackPane.setAlignment(artwork, Pos.BOTTOM_CENTER);
        Label brand = FxStyles.label("VCampus", "brand");
        Label headline = FxStyles.label("连接你的校园生活", "login-headline");
        Label subtitle = FxStyles.label("学习与生活，从这里开始", "login-subtitle");
        VBox copy = new VBox(14, brand, new Region(), headline, subtitle);
        ((Region)copy.getChildren().get(1)).setPrefHeight(72);
        copy.setPadding(new Insets(42, 44, 0, 44)); copy.setMouseTransparent(true);
        illustration.getChildren().addAll(artwork, copy);
        illustration.prefWidthProperty().bind(widthProperty().multiply(.56));
        illustration.setMinWidth(420); illustration.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(illustration, Priority.ALWAYS);

        username.setId("login-username"); username.setPromptText("请输入账号");
        username.setAccessibleText("学号、工号或管理员账号");
        host.setText(connection.host()); port.setText(Integer.toString(connection.port()));
        host.setId("server-host"); port.setId("server-port");
        host.setAccessibleText("服务器地址"); port.setAccessibleText("端口"); port.setPrefWidth(95);
        Runnable submit = () -> {
            try {
                ServerConnection target = ServerConnection.parse(host.getText(), port.getText());
                if (username.getText().isBlank() || password.hidden.getText().isEmpty())
                    throw new IllegalArgumentException("请输入账号和密码");
                Credentials credentials = new Credentials(username.getText().trim(), password.value());
                password.clear(); onLogin.accept(target, credentials);
            } catch (IllegalArgumentException error) { status(error.getMessage(), true); }
        };
        login = FxStyles.button("登录", null, submit, "primary"); login.setId("login-submit");
        login.setMaxWidth(Double.MAX_VALUE); login.setDefaultButton(true);
        test = FxStyles.button("测试连接", Feather.SERVER, () -> {
            try { onTest.accept(ServerConnection.parse(host.getText(), port.getText())); }
            catch (IllegalArgumentException error) { status(error.getMessage(), true); }
        }, "quiet"); test.setId("test-connection");
        HBox settingsFields = new HBox(10, host, port); HBox.setHgrow(host, Priority.ALWAYS);
        VBox settingsBody = new VBox(10, FxStyles.label("服务器地址 / 端口", "muted"), settingsFields, test);
        TitledPane settings = new TitledPane("连接设置", settingsBody); settings.setExpanded(false);
        settings.setAnimated(false); settings.getStyleClass().add("connection-settings");
        Label title = FxStyles.label("欢迎回到 VCampus", "login-title");
        Label hint = FxStyles.label("使用校园账号登录", "muted");
        VBox titles = new VBox(10, title, hint); titles.setAlignment(Pos.CENTER);
        VBox fields = new VBox(10, FxStyles.label("学号 / 工号", "field-label"), username,
                gap(10), FxStyles.label("密码", "field-label"), password, gap(14), login);
        Label firstLogin = FxStyles.label("首次登录后需修改初始密码", "helper");
        firstLogin.setMaxWidth(Double.MAX_VALUE); firstLogin.setAlignment(Pos.CENTER);
        status.setWrapText(true); status.setMinHeight(32);
        VBox form = new VBox(20, titles, gap(10), fields, firstLogin, settings, status);
        form.setMaxWidth(380); form.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane formArea = new StackPane(form); formArea.setPadding(new Insets(38, 40, 24, 40));
        ScrollPane formScroll = new ScrollPane(formArea);
        formScroll.setFitToWidth(true); formScroll.setFitToHeight(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.getStyleClass().add("campus-scroll");
        formScroll.prefWidthProperty().bind(widthProperty().multiply(.44)); formScroll.setMinWidth(400);
        HBox.setHgrow(formScroll, Priority.ALWAYS);
        getChildren().addAll(illustration, formScroll);
    }
    private Region gap(double height) { Region region = new Region(); region.setMinHeight(height); return region; }
    void busy(boolean value) {
        login.setDisable(value); test.setDisable(value); username.setDisable(value); password.setDisable(value);
        host.setDisable(value); port.setDisable(value); login.setText(value ? "正在连接…" : "登录");
    }
    void status(String text, boolean error) {
        status.setText(text); status.getStyleClass().remove("error");
        if (error) status.getStyleClass().add("error");
    }
}
