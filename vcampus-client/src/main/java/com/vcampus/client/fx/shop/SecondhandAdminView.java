package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShopAccessPolicy;
import com.vcampus.common.model.ShopOrderStatus;
import com.vcampus.common.model.UserRole;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class SecondhandAdminView extends VBox {
    interface Listener {void moderateItem(long id,boolean hide,String reason);void resolve(long id,SecondhandData.AdminResolution resolution,String operationId,String reason);void moderateReview(long id,boolean hide,String reason);default void orders(){}default void order(long id){}}
    private final Listener listener;private final TextArea reason=new TextArea();private final RadioButton refund=new RadioButton("退款并重新上架");private final RadioButton settle=new RadioButton("结算给卖家");private final CheckBox confirm=new CheckBox("我已核对订单并确认该处理不可撤销");private final Button apply;private SecondhandData.Order order;private String operationId;
    SecondhandAdminView(Set<UserRole>roles,Listener listener){super(12);if(!ShopAccessPolicy.canManage(roles))throw new IllegalStateException("当前账号没有二手市场管理权限");this.listener=Objects.requireNonNull(listener);getStyleClass().add("used-content");reason.setId("used-admin-reason");reason.setPromptText("处理原因（2–255字）");reason.setWrapText(true);ToggleGroup choices=new ToggleGroup();refund.setToggleGroup(choices);settle.setToggleGroup(choices);refund.setId("used-admin-refund");settle.setId("used-admin-settle");confirm.setId("used-admin-confirm");apply=ShopUi.button("确认处理","used-primary",this::resolve);apply.setId("used-admin-resolve");reason.textProperty().addListener((o,b,a)->update());choices.selectedToggleProperty().addListener((o,b,a)->update());confirm.selectedProperty().addListener((o,b,a)->update());showLanding();}
    void showLanding(){order=null;Button orders=ShopUi.button("待处理异常订单","used-primary",listener::orders);orders.setId("used-admin-orders");getChildren().setAll(ShopUi.label("二手市场管理","used-title"),orders,moderationBox("物品审核","物品ID",true),moderationBox("评价审核","评价ID",false));}
    void showOrders(SecondhandData.OrderPage page){order=null;getChildren().setAll(ShopUi.label("待处理异常订单","used-title"));if(page.rows().isEmpty())getChildren().add(ShopUi.label("当前没有待处理订单","shop-muted"));for(var row:page.rows()){Button button=ShopUi.button(row.orderNo()+" · "+row.title()+" · "+row.status(),"used-card",()->listener.order(row.id()));button.setId("used-admin-order-"+row.id());getChildren().add(button);}}
    void showOrder(SecondhandData.Order value){order=Objects.requireNonNull(value);operationId=UUID.randomUUID().toString();reason.clear();refund.setSelected(false);settle.setSelected(false);confirm.setSelected(false);getChildren().setAll(ShopUi.label("异常订单处理 · "+value.orderNo(),"used-title"),ShopUi.label(value.title()+" · "+value.status(),"used-status"),new HBox(12,refund,settle),reason,confirm,apply);update();}
    private VBox moderationBox(String title,String prompt,boolean item){TextField id=new TextField();id.setPromptText(prompt);TextArea moderationReason=new TextArea();moderationReason.setPromptText("审核原因（2–255字）");Button hide=ShopUi.button("隐藏","shop-quiet",()->moderate(id,moderationReason,true,item));Button restore=ShopUi.button("恢复","shop-quiet",()->moderate(id,moderationReason,false,item));VBox box=new VBox(8,ShopUi.label(title,"shop-section-title"),id,moderationReason,new HBox(8,hide,restore));box.getStyleClass().add("used-admin-box");return box;}
    private void moderate(TextField id,TextArea text,boolean hide,boolean item){String value=text.getText()==null?"":text.getText().trim();if(value.length()<2||value.length()>255)return;try{long target=Long.parseLong(id.getText());if(target<1)return;if(item)listener.moderateItem(target,hide,value);else listener.moderateReview(target,hide,value);}catch(NumberFormatException ignored){}}
    private void resolve(){if(order==null||apply.isDisabled())return;listener.resolve(order.id(),refund.isSelected()?SecondhandData.AdminResolution.REFUND:SecondhandData.AdminResolution.SETTLE,operationId,reason.getText().trim());}
    private void update(){String text=reason.getText()==null?"":reason.getText().trim();boolean valid=order!=null&&order.status()==ShopOrderStatus.SHIPPED&&(refund.isSelected()||settle.isSelected())&&text.length()>=2&&text.length()<=255&&confirm.isSelected();apply.setDisable(!valid);}
}
