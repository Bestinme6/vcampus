package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShippingAddressChoice;
import com.vcampus.common.model.ShopAddress;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

final class SecondhandCheckoutView extends VBox {
    record Submission(long itemId,String operationId,ShippingAddressChoice address,
                      boolean saveAddress,Integer replaceSlot) { }
    private final Consumer<Submission> confirm;private final ShopAddressSelectionView addresses;
    private SecondhandData.Listing item;private String operationId;
    private final Button submit;
    SecondhandCheckoutView(Consumer<Submission> confirm,Runnable back){super(14);this.confirm=Objects.requireNonNull(confirm,"confirm");submit=ShopUi.button("确认购买","used-primary",()->submission().ifPresent(confirm));submit.setId("used-checkout-confirm");addresses=new ShopAddressSelectionView(this::update);Button cancel=ShopUi.button("返回","shop-quiet",back);getChildren().addAll(ShopUi.label("确认二手订单","used-title"),addresses,new HBox(8,cancel,submit));getStyleClass().add("used-content");}
    void show(SecondhandData.Listing item,List<ShopAddress> rows,String operationId){this.item=Objects.requireNonNull(item,"item");this.operationId=Objects.requireNonNull(operationId,"operationId");addresses.show(rows);getChildren().set(0,ShopUi.label(item.title()+" · ¥ "+item.price().toPlainString(),"used-title"));update();}
    Optional<Submission> submission(){if(item==null||operationId==null||!addresses.valid())return Optional.empty();return addresses.choice().map(choice->new Submission(item.id(),operationId,choice,addresses.saveRequested(),addresses.replacementSlot()));}
    void busy(boolean value){setDisable(value);}
    void showError(String message){setDisable(false);}
    private void update(){submit.setDisable(submission().isEmpty());}
}
