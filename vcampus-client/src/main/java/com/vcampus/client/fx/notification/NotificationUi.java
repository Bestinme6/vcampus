package com.vcampus.client.fx.notification;

import com.vcampus.common.model.NotificationSource;
import com.vcampus.common.model.NotificationTarget;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.net.URL;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class NotificationUi {
    private static final DateTimeFormatter LIST_TIME=DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final DateTimeFormatter DETAIL_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private NotificationUi(){}

    static void install(Parent root){
        URL css=NotificationUi.class.getResource("/com/vcampus/client/fx/notification/notification.css");
        if(css!=null&&!root.getStylesheets().contains(css.toExternalForm()))root.getStylesheets().add(css.toExternalForm());
    }
    static Label label(String text,String... styles){Label value=new Label(text);value.getStyleClass().addAll(styles);return value;}
    static Button button(String text,String style,Runnable action){Button value=new Button(text);value.getStyleClass().add(style);value.setOnAction(e->action.run());return value;}
    static String sourceLabel(NotificationSource source){return switch(source){
        case ACADEMIC->"教务";case STUDENT_STATUS->"学籍";case ACCOUNT_SECURITY->"账号安全";case LIBRARY->"图书馆";
        case FORUM->"论坛";case BANK->"银行";case SHOP->"商店";};}
    static String listTime(java.time.Instant value){return LIST_TIME.format(value);}
    static String detailTime(java.time.Instant value){return DETAIL_TIME.format(value);}
    static String navigationLabel(NotificationTarget target){return switch(target){
        case TEACHER_SCHEDULE->"查看教师课表";case ACADEMIC_SCHEDULE->"查看相关课表";case STUDENT_GRADES->"查看成绩";
        case STUDENT_PROFILE->"查看学籍信息";case LIBRARY_LOANS->"查看我的借阅";case LIBRARY_CATALOG->"查看预约图书";case LIBRARY_FEES->"查看图书费用";
        case FORUM_POST->"查看帖子";case BANK_LEDGER->"查看银行流水";case SHOP_ORDERS->"查看订单详情";case NONE->"";};}
}
