package com.vcampus.client.fx.bank;

import com.vcampus.common.model.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;
import java.util.function.Consumer;

final class BankView extends BorderPane {
    private final Label status=BankUi.label("","bank-status");
    private final VBox body=new VBox();
    private final Map<String,Button> navigation=new LinkedHashMap<>();
    BankView(Set<UserRole> roles,Consumer<String> open,Runnable back){
        setId("bank-view");getStyleClass().add("bank-root");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("bank.css")).toExternalForm());
        VBox sidebar=new VBox(9,BankUi.label("校园银行","bank-brand"),BankUi.label("VCAMPUS BANK","bank-small"));
        sidebar.getStyleClass().add("bank-sidebar");sidebar.setPrefWidth(154);sidebar.setMinWidth(154);
        nav(sidebar,"bank","账户总览","home",open);nav(sidebar,"bank-transfer","校园转账","transfer",open);
        nav(sidebar,"bank-ledger","资金流水","ledger",open);
        if(BankAccessPolicy.canManage(roles)){
            sidebar.getChildren().add(BankUi.label("银行管理","bank-nav-caption"));
            nav(sidebar,"bank-admin","账户管理","admin",open);
            nav(sidebar,"bank-admin-ledger","全量流水","admin-ledger",open);
        }
        Region space=new Region();VBox.setVgrow(space,Priority.ALWAYS);
        sidebar.getChildren().addAll(space,BankUi.button("返回工作台","bank-back",back));setLeft(sidebar);
        body.getStyleClass().add("bank-body");
        VBox center=new VBox(10,status,body);center.getStyleClass().add("bank-center");VBox.setVgrow(body,Priority.ALWAYS);
        status.setVisible(false);status.setManaged(false);setCenter(center);
    }
    private void nav(VBox box,String route,String title,String id,Consumer<String> open){
        Button b=BankUi.button(title,"bank-nav-"+id,()->open.accept(route));b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("bank-nav");box.getChildren().add(b);navigation.put(route,b);
    }
    void page(Node page,String route){
        body.getChildren().setAll(page);VBox.setVgrow(page,Priority.ALWAYS);
        navigation.forEach((key,b)->{b.getStyleClass().remove("bank-selected");if(key.equals(route))b.getStyleClass().add("bank-selected");});
    }
    void status(String text,boolean error){
        status.setText(text);status.setVisible(!text.isBlank());status.setManaged(!text.isBlank());
        status.getStyleClass().remove("bank-error");if(error)status.getStyleClass().add("bank-error");
    }
    void busy(boolean busy){body.setDisable(busy);}
}
