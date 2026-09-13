package com.vcampus.client.fx.bank;

import com.vcampus.common.model.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.*;
import java.util.function.Consumer;

final class BankView extends BorderPane {
    private static final List<NavItem> PERSONAL_ROUTES=List.of(
            new NavItem("bank","账户总览","home"),
            new NavItem("bank-transfer","校园转账","transfer"),
            new NavItem("bank-ledger","资金流水","ledger"));
    private static final List<NavItem> MANAGEMENT_ROUTES=List.of(
            new NavItem("bank-admin","账户管理","admin"),
            new NavItem("bank-admin-ledger","全量流水","admin-ledger"));
    private final Label status=BankUi.label("","bank-status");
    private final VBox body=new VBox();
    private final Map<String,Button> navigation=new LinkedHashMap<>();
    private final Map<String,Button> groups=new LinkedHashMap<>();
    private final Map<String,List<NavItem>> visibleGroups=new LinkedHashMap<>();
    private final HBox primaryNavigation=new HBox(8),secondaryNavigation=new HBox(8);
    BankView(Set<UserRole> roles,Consumer<String> open,Runnable back){
        setId("bank-view");getStyleClass().add("bank-root");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("bank.css")).toExternalForm());
        HBox heading=BankUi.row(new VBox(4,BankUi.label("VCAMPUS BANK","bank-small"),
                BankUi.label("校园银行","bank-brand")),BankUi.spacer(),
                BankUi.button("返回工作台","bank-back",back));
        primaryNavigation.setId("bank-primary-navigation");primaryNavigation.getStyleClass().add("bank-primary-navigation");
        secondaryNavigation.setId("bank-secondary-navigation");secondaryNavigation.getStyleClass().add("bank-secondary-navigation");
        addGroup("personal","个人银行",PERSONAL_ROUTES,open);
        if(BankAccessPolicy.canManage(roles))addGroup("management","银行管理",MANAGEMENT_ROUTES,open);
        showGroup("personal");
        VBox header=new VBox(0,heading,primaryNavigation,secondaryNavigation);header.getStyleClass().add("bank-header");setTop(header);
        body.getStyleClass().add("bank-body");
        VBox center=new VBox(10,status,body);center.getStyleClass().add("bank-center");VBox.setVgrow(body,Priority.ALWAYS);
        status.setVisible(false);status.setManaged(false);setCenter(center);
    }
    private void addGroup(String key,String title,List<NavItem> routes,Consumer<String> open){
        visibleGroups.put(key,routes);
        Button group=BankUi.button(title,"bank-group-"+key,()->open.accept(routes.getFirst().route()));
        group.getStyleClass().add("bank-group");groups.put(key,group);primaryNavigation.getChildren().add(group);
        for(NavItem route:routes)nav(route,open);
    }
    private void nav(NavItem item,Consumer<String> open){
        String route=item.route(),title=item.title(),id=item.id();
        Button b=BankUi.button(title,"bank-nav-"+id,()->open.accept(route));b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("bank-nav");navigation.put(route,b);
    }
    private void showGroup(String key){
        List<NavItem> routes=visibleGroups.get(key);if(routes==null)return;
        secondaryNavigation.getChildren().setAll(routes.stream().map(item->navigation.get(item.route())).toList());
        groups.forEach((name,button)->{button.getStyleClass().remove("bank-group-selected");
            if(name.equals(key))button.getStyleClass().add("bank-group-selected");});
    }
    void page(Node page,String route){
        if(route.startsWith("bank-admin"))showGroup("management");else showGroup("personal");
        body.getChildren().setAll(page);VBox.setVgrow(page,Priority.ALWAYS);
        navigation.forEach((key,b)->{b.getStyleClass().remove("bank-selected");if(key.equals(route))b.getStyleClass().add("bank-selected");});
    }
    void status(String text,boolean error){
        status.setText(text);status.setVisible(!text.isBlank());status.setManaged(!text.isBlank());
        status.getStyleClass().remove("bank-error");if(error)status.getStyleClass().add("bank-error");
    }
    void busy(boolean busy){primaryNavigation.setDisable(busy);secondaryNavigation.setDisable(busy);body.setDisable(busy);}
    private record NavItem(String route,String title,String id){}
}
