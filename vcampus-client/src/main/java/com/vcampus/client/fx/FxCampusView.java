package com.vcampus.client.fx;

import com.vcampus.client.ui.CampusDashboardData;
import com.vcampus.common.model.UserRole;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.kordamp.ikonli.feather.Feather;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class FxCampusView extends ScrollPane {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA);
    private final Label name = FxStyles.label("", "person-name");
    private final Label profile = FxStyles.label("正在读取档案…", "muted");
    private final Label dateLabel = FxStyles.label("", "date-label");
    private final Label courseHeading = FxStyles.label("今日课程", "section-title");
    private final VBox courses = new VBox(18);
    private final VBox tasks = new VBox(0);
    private final VBox notices = new VBox(6);
    private final HBox summary = new HBox(10);
    private final Button refresh;
    private final Consumer<String> navigate;
    private final Map<LocalDate,Button> days = new LinkedHashMap<>();
    private final LocalDate today;

    FxCampusView(String displayName, Set<UserRole> roles, LocalDate today,
                 Consumer<LocalDate> selectDate, Runnable onRefresh, Consumer<String> navigate) {
        this.today=today; this.navigate=navigate;
        setId("campus-view"); setFitToWidth(true); setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("campus-scroll");
        VBox content = new VBox(28); content.getStyleClass().add("campus-content");
        content.setFillWidth(true);
        refresh=FxStyles.button("刷新",Feather.REFRESH_CW,onRefresh,"text-button"); refresh.setId("campus-refresh");
        content.getChildren().add(FxStyles.row(FxStyles.label("我的校园","page-title"),FxStyles.spacer(),refresh));
        String initial=displayName.isBlank()?"VC":displayName.substring(0,1);
        Label avatar=FxStyles.label(initial,"avatar"); avatar.setAlignment(Pos.CENTER);
        avatar.setMinSize(62,62); avatar.setPrefSize(62,62);
        String identity=roles.contains(UserRole.STUDENT)?"学生":roles.contains(UserRole.TEACHER)?"教师":"超级管理员";
        name.setText(displayName+" · "+identity); profile.setWrapText(true);
        VBox identityCopy=new VBox(9,name,profile);
        content.getChildren().add(FxStyles.row(avatar,identityCopy));
        boolean admin=roles.contains(UserRole.SUPER_ADMIN);
        content.getChildren().add(FxStyles.label(admin?"校园管理，从这里开始":"今天，安排得刚刚好","greeting"));
        LocalDate monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday=monday.plusDays(6);
        String month=monday.getMonthValue()==sunday.getMonthValue()?monday.getMonthValue()+"月":
                monday.getMonthValue()+"月 / "+sunday.getMonthValue()+"月";
        dateLabel.setText(DATE.format(today));
        content.getChildren().add(FxStyles.row(dateLabel,FxStyles.spacer(),FxStyles.label(month,"date-label")));
        HBox week = new HBox(10); week.setAlignment(Pos.CENTER_LEFT);
        String[] weekdays={"周一","周二","周三","周四","周五","周六","周日"};
        for(int i=0;i<7;i++) {
            LocalDate day=monday.plusDays(i);
            Label weekday=FxStyles.label(weekdays[i],"day-name");
            Label number=FxStyles.label(Integer.toString(day.getDayOfMonth()),"day-number");
            VBox copy=new VBox(7,weekday,number); copy.setAlignment(Pos.CENTER);
            Button button=new Button(); button.setGraphic(copy); button.setId("campus-day-"+day);
            button.getStyleClass().add("day-button"); button.setMaxWidth(Double.MAX_VALUE);
            button.setAccessibleText(DATE.format(day)); HBox.setHgrow(button,Priority.ALWAYS);
            button.prefWidthProperty().bind(week.widthProperty().subtract(60).divide(7));
            button.setOnAction(event->{markDate(day); selectDate.accept(day);});
            days.put(day,button); week.getChildren().add(button);
        }
        if(!admin) content.getChildren().add(week);
        markDate(today);
        Button full=FxStyles.button(admin?"打开工作台":"完整课表",Feather.ARROW_RIGHT,
                ()->navigate.accept(admin?"workspace":roles.contains(UserRole.TEACHER)?"teacher-schedule":"student-schedule"),"text-button");
        full.setId("full-schedule");
        if(admin) courseHeading.setText("管理入口");
        VBox agenda=new VBox(22,FxStyles.row(courseHeading,FxStyles.spacer(),full),courses);
        agenda.setMinWidth(300); agenda.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(agenda,Priority.ALWAYS);
        VBox pending=new VBox(24,FxStyles.label("需要处理","section-title"),tasks,summary);
        pending.getStyleClass().add("pending-column"); pending.setMinWidth(240); pending.setPrefWidth(340);
        HBox body=new HBox(28,agenda,pending); body.setAlignment(Pos.TOP_LEFT);
        pending.prefWidthProperty().bind(body.widthProperty().multiply(.36));
        content.getChildren().addAll(body,notices);
        setContent(content);
    }
    private void markDate(LocalDate date) {
        dateLabel.setText(DATE.format(date));
        days.forEach((day,button)->{
            button.getStyleClass().remove("selected-day");
            if(day.equals(date)) button.getStyleClass().add("selected-day");
        });
        courseHeading.setText(date.equals(today)?"今日课程":"当日课程");
    }
    void loading(LocalDate date) {
        markDate(date); refresh.setDisable(true); refresh.setText("加载中…");
        courses.getChildren().setAll(FxStyles.label("正在读取课程安排…","muted"));
        tasks.getChildren().setAll(FxStyles.label("正在读取待办…","muted"));
        notices.getChildren().clear(); summary.getChildren().clear();
    }
    void showData(CampusDashboardData data) {
        refresh.setDisable(false); refresh.setText("刷新"); markDate(data.date());
        name.setText(data.displayName()+" · "+data.identity()); profile.setText(data.profileLine());
        courses.getChildren().clear(); tasks.getChildren().clear(); notices.getChildren().clear();
        int index=0;
        for(var course:data.courses()) {
            Label periods=FxStyles.label(course.periods(),"period-label"); periods.setMinWidth(84); periods.setWrapText(true);
            Node marker=FxStyles.icon(Feather.CIRCLE,19); marker.getStyleClass().add(index%2==0?"blue-marker":"teal-marker");
            Label title=FxStyles.label(course.title(),"course-title"); title.setWrapText(true);
            Label location=FxStyles.label(course.location(),"course-location"); location.setWrapText(true);
            Label detail=FxStyles.label(course.detail(),"course-detail"); detail.setWrapText(true);
            VBox block=new VBox(10,title,location,detail); block.getStyleClass().add(index++%2==0?"course-blue":"course-white");
            block.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(block,Priority.ALWAYS);
            HBox row=FxStyles.row(periods,marker,block); row.setAlignment(Pos.TOP_LEFT); courses.getChildren().add(row);
        }
        if(courses.getChildren().isEmpty()) courses.getChildren().add(empty("暂无课程安排", "可通过完整课表查看教学安排。"));
        index=0;
        for(var task:data.tasks()) {
            Label title=FxStyles.label(task.title(),"task-title");
            Label detail=FxStyles.label(task.detail(),"muted"); detail.setWrapText(true); detail.setMaxWidth(300);
            title.setWrapText(true); title.setMinHeight(Region.USE_PREF_SIZE);
            detail.setMinHeight(Region.USE_PREF_SIZE);
            detail.prefWidthProperty().bind(tasks.widthProperty().subtract(75));
            if(task.urgent()) detail.getStyleClass().add("warning");
            VBox copy=new VBox(8,title,detail); copy.setMinWidth(0); HBox.setHgrow(copy,Priority.ALWAYS);
            HBox row=FxStyles.row(FxStyles.icon(task.route().equals("library-loans")?Feather.BOOK_OPEN:Feather.SHOPPING_BAG,24),copy,
                    FxStyles.icon(Feather.CHEVRON_RIGHT,17));
            Button action=new Button(); action.setGraphic(row); action.setMaxWidth(Double.MAX_VALUE);
            row.prefWidthProperty().bind(action.widthProperty().subtract(8));
            action.setId("task-"+index++); action.getStyleClass().add("task-row");
            action.setAccessibleText(task.title()+"，"+task.detail()); action.setOnAction(e->navigate.accept(task.route()));
            tasks.getChildren().add(action);
        }
        if(tasks.getChildren().isEmpty()) tasks.getChildren().add(empty("暂时没有待办", "需要处理的借阅和订单会显示在这里。"));
        summary.getChildren().setAll(FxStyles.label("在借 "+data.loanCount()+"  ·  订单 "+data.orderCount(),"helper"));
        Label balance=FxStyles.label("余额 "+data.balance(),"summary-balance"); balance.setWrapText(true);
        VBox amount=new VBox(6,summary.getChildren().remove(0),balance); summary.getChildren().setAll(amount);
        for(String notice:data.notices()) {
            Label line=FxStyles.label(notice,"notice"); line.setWrapText(true); notices.getChildren().add(line);
        }
        if(data.identity().equals("超级管理员")) {
            courseHeading.setText("管理入口");
            courses.getChildren().setAll(empty("使用工作台管理校园业务", "账号、学籍、教务及其他管理功能按权限开放。"));
        }
    }
    void showError(String message) {
        refresh.setDisable(false); refresh.setText("重试");
        courses.getChildren().setAll(empty("暂时无法加载",message)); tasks.getChildren().clear();
        summary.getChildren().clear(); notices.getChildren().clear();
    }
    private VBox empty(String title,String detail) {
        Label sub=FxStyles.label(detail,"muted"); sub.setWrapText(true);
        VBox box=new VBox(12,FxStyles.label(title,"empty-title"),sub); box.setPadding(new Insets(28,8,30,8)); return box;
    }
}
