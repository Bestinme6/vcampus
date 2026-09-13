package com.vcampus.client.fx;

import com.vcampus.client.network.VCampusClient;
import com.vcampus.client.fx.forum.ForumController;
import com.vcampus.client.fx.forum.SocketForumGateway;
import com.vcampus.client.fx.library.LibraryController;
import com.vcampus.client.fx.shop.ShopController;
import com.vcampus.client.fx.shop.ShopImageCache;
import com.vcampus.client.fx.shop.ShopImageCacheConfig;
import com.vcampus.client.fx.shop.ShopRoute;
import com.vcampus.client.fx.shop.SocketShopGateway;
import com.vcampus.client.fx.bank.BankController;
import com.vcampus.client.fx.bank.SocketBankGateway;
import com.vcampus.client.fx.academic.AcademicController;
import com.vcampus.client.fx.academic.SocketAcademicGateway;
import com.vcampus.client.fx.identity.AccountAdminController;
import com.vcampus.client.fx.identity.SocketAccountGateway;
import com.vcampus.client.fx.identity.SocketStudentGateway;
import com.vcampus.client.fx.identity.SocketTeacherGateway;
import com.vcampus.client.fx.identity.StudentAdminController;
import com.vcampus.client.fx.identity.StudentProfileController;
import com.vcampus.client.fx.identity.TeacherProfileController;
import com.vcampus.client.fx.notification.NotificationController;
import com.vcampus.client.fx.system.SocketSystemGateway;
import com.vcampus.client.fx.system.SystemData;
import com.vcampus.client.fx.system.SystemManagementController;
import com.vcampus.client.fx.system.SystemManagementView;
import com.vcampus.client.ui.*;
import com.vcampus.common.model.UserRole;
import com.vcampus.common.model.SystemAccessPolicy;
import com.vcampus.common.protocol.ResponseMessage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns the desktop session. Business requests never run on either UI thread. */
public final class CampusApplication extends Application {
    private static final ZoneId CAMPUS_ZONE=ZoneId.of("Asia/Shanghai");
    private final ExecutorService requests=Executors.newFixedThreadPool(4, runnable->{
        Thread thread=new Thread(runnable,"vcampus-client-request"); thread.setDaemon(true); return thread;
    });
    private Stage stage;
    private Scene scene;
    private ServerConnection connection;
    private VCampusClient client;
    private ClientSession session;
    private BorderPane shell;
    private FxCampusView campus;
    private LocalDate selectedDate;
    private long dateGeneration;
    private ExecutorService dashboardRequests;
    private Future<?> dashboardLoad;
    private volatile long sessionGeneration;
    private volatile boolean closing;
    private UnreadNotificationPoller unreadPoll;
    private Label badge;
    private SwingNode legacyNode;
    private LegacyModuleBridge legacy;
    private ForumController forum;
    private LibraryController library;
    private ExecutorService libraryRequests;
    private ShopController shop;
    private ExecutorService shopRequests;
    private BankController bank;
    private ExecutorService bankRequests;
    private AcademicController academic;
    private ExecutorService academicRequests;
    private ExecutorService identityRequests;
    private StudentProfileController studentProfile;
    private StudentAdminController studentAdmin;
    private TeacherProfileController teacherProfile;
    private AccountAdminController accountAdmin;
    private NotificationController notifications;
    private SystemManagementController systemManagement;
    private SystemManagementView systemManagementView;
    private final Map<String,Button> navigation=new LinkedHashMap<>();

    @Override public void start(Stage stage) {
        this.stage=stage;
        String configurationWarning=null;
        try { connection=ServerConnection.defaults(System.getenv()); }
        catch(IllegalArgumentException invalidEnvironment) {
            connection=new ServerConnection("127.0.0.1",9090);
            configurationWarning="连接环境变量无效，已恢复本机默认地址。请在连接设置中检查地址和端口。";
        }
        SwingUtilities.invokeLater(()->{
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch(Exception ignored) { }
            Theme.install();
        });
        stage.setTitle("VCampus · 虚拟校园"); stage.setMinWidth(1000); stage.setMinHeight(720);
        stage.setWidth(1280); stage.setHeight(860);
        showLogin();
        if(configurationWarning!=null) loginView().status(configurationWarning,true);
        stage.show();
        stage.setOnCloseRequest(event->shutdown());
    }
    private void root(javafx.scene.Parent root) {
        FxStyles.install(root);
        if(scene==null) { scene=new Scene(root); stage.setScene(scene); } else scene.setRoot(root);
    }
    private void showLogin() {
        FxLoginView view=new FxLoginView(connection,this::login,this::testConnection); root(view);
    }
    private FxLoginView loginView() { return (FxLoginView)scene.getRoot(); }
    private void testConnection(ServerConnection target) {
        FxLoginView view=loginView(); view.busy(true); view.status("正在测试连接…",false);
        request(()->new VCampusClient(target.host(),target.port()).ping(), response->{
            view.busy(false); view.status(response.message(),!response.success());
        }, error->{view.busy(false); view.status(error,true);});
    }
    private void login(ServerConnection target,FxLoginView.Credentials credentials) {
        connection=target;
        FxLoginView view=loginView(); view.busy(true); view.status("正在登录…",false);
        VCampusClient attempt=new VCampusClient(target.host(),target.port());
        long generation=sessionGeneration;
        CompletableFuture.supplyAsync(()->{
            try { return attempt.login(credentials.username(),credentials.password()); }
            catch(Exception error) { throw new CompletionException(error); }
            finally { Arrays.fill(credentials.password(),'\0'); }
        },requests).whenComplete((response,error)->{
            if(closing || generation!=sessionGeneration) { revokeResponse(attempt,response); return; }
            Platform.runLater(()->{
                if(closing || generation!=sessionGeneration) { revokeResponse(attempt,response); return; }
                view.busy(false);
                if(error!=null) { view.status(errorText(error),true); return; }
                if(!response.success()) { view.status(response.message(),true); return; }
                try {
                    session=ClientSession.from(response); client=attempt;
                    if(session.requiresPasswordChange()) showPasswordChange(); else enterCampus();
                } catch(IllegalArgumentException malformed) {
                    revokeResponse(attempt,response); session=null; view.status("登录响应无效："+malformed.getMessage(),true);
                }
            });
        });
    }
    private void showPasswordChange() {
        FxPasswordInput current=new FxPasswordInput("current-password");
        FxPasswordInput next=new FxPasswordInput("new-password");
        FxPasswordInput repeat=new FxPasswordInput("repeat-password");
        Label status=FxStyles.label("新密码长度为 8—128 位","muted"); status.setWrapText(true);
        Button submit=new Button("修改密码并进入校园"); submit.getStyleClass().add("primary"); submit.setId("change-password-submit");
        submit.setMaxWidth(Double.MAX_VALUE); submit.setDefaultButton(true);
        Button cancel=FxStyles.button("取消并退出登录",null,this::logout,"text-button");
        submit.setOnAction(event->{
            String value=next.hidden.getText();
            if(current.hidden.getText().isEmpty() || value.length()<8 || value.length()>128 || !value.equals(repeat.hidden.getText())) {
                status.setText("请填写当前密码，确保新密码为 8—128 位且两次输入一致"); return;
            }
            char[] old=current.value(), fresh=next.value();
            current.clear(); next.clear(); repeat.clear(); submit.setDisable(true); cancel.setDisable(true);
            VCampusClient activeClient=client; String token=session.token();
            request(()->{try{return activeClient.changePassword(token,old,fresh);}
                finally {Arrays.fill(old,'\0'); Arrays.fill(fresh,'\0');}},response->{
                submit.setDisable(false); cancel.setDisable(false);
                if(response.success()) {session=session.passwordChanged(); enterCampus();} else status.setText(response.message());
            },error->{submit.setDisable(false); cancel.setDisable(false); status.setText(error);});
        });
        VBox form=new VBox(14,FxStyles.label("VCampus","brand"),FxStyles.label("设置你的新密码","page-title"),
                FxStyles.label("首次登录，请先修改初始密码以保护账号安全","muted"),
                FxStyles.label("当前密码","field-label"),current,FxStyles.label("新密码","field-label"),next,
                FxStyles.label("再次输入新密码","field-label"),repeat,status,submit,cancel);
        form.setMaxWidth(460); StackPane page=new StackPane(form); page.setId("required-password-change");
        page.getStyleClass().add("password-page"); root(page);
    }
    private void enterCampus() {
        if(session==null || session.requiresPasswordChange()) throw new IllegalStateException("请先完成首次改密");
        dashboardRequests=Executors.newSingleThreadExecutor(runnable->{
            Thread thread=new Thread(runnable,"vcampus-campus-overview"); thread.setDaemon(true); return thread;
        });
        shell=new BorderPane(); shell.setId("campus-shell"); navigation.clear();
        VBox sidebar=new VBox(10); sidebar.setPrefWidth(220); sidebar.setMinWidth(220); sidebar.getStyleClass().add("sidebar");
        Label brand=FxStyles.label("VCampus","brand"); VBox.setMargin(brand,new Insets(0,14,30,14));
        sidebar.getChildren().addAll(brand,FxStyles.label("校园门户","helper"));
        sidebar.getChildren().add(nav("workspace","工作台",Feather.HOME));
        if(session.identity()==UserRole.SUPER_ADMIN) sidebar.getChildren().add(nav("accounts","账号管理",Feather.USERS));
        if(canOpenSystemManagement(session.roles())) sidebar.getChildren().add(
                nav("system-management", "系统管理", Feather.SETTINGS));
        Button messages=nav("notifications","消息中心",Feather.MESSAGE_SQUARE);
        badge=FxStyles.label("","nav-badge"); badge.setVisible(false); badge.setManaged(false);
        HBox messageRow=new HBox(2,messages,badge); messageRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(messages,Priority.ALWAYS);
        sidebar.getChildren().addAll(messageRow,nav("campus","我的校园",Feather.CALENDAR));
        Region filler=new Region(); VBox.setVgrow(filler,Priority.ALWAYS); sidebar.getChildren().add(filler);
        Button logout=FxStyles.button("退出登录",Feather.LOG_OUT,this::logout,"nav-button"); logout.setId("logout");
        logout.setMaxWidth(Double.MAX_VALUE); sidebar.getChildren().add(logout); shell.setLeft(sidebar);
        legacyNode=new SwingNode();
        final long generation=sessionGeneration;
        VCampusClient activeClient=client; ClientSession activeSession=session;
        SwingNode node=legacyNode;
        SwingUtilities.invokeLater(()->{
            if(closing || generation!=sessionGeneration) return;
            legacy=new LegacyModuleBridge(activeClient,activeSession.token(),activeSession.roles(),
                    ()->onFx(generation,()->openRoute("workspace")),()->refreshUnread(generation),
                    ()->onFx(generation,()->selectNav("workspace")),
                    postId->onFx(generation,()->showForumPost(postId)),
                    ()->onFx(generation,()->openRoute("library-loans")),
                    bookId->onFx(generation,()->showLibraryBook(bookId)),
                    orderId->onFx(generation,()->showShopOrder(orderId)),
                    ()->onFx(generation,()->openRoute("bank-ledger")),
                    route->onFx(generation,()->openRoute(route)));
            node.setContent(legacy.content());
        });
        root(shell); showCampus();
        unreadPoll=new UnreadNotificationPoller(()->CompletableFuture.supplyAsync(()->{
            try {
                ResponseMessage response=activeClient.unreadNotificationCount(activeSession.token());
                if(!response.success()) throw new IllegalStateException(response.message());
                return NotificationViewData.unreadCount(response);
            } catch(Exception error) {throw new CompletionException(error);}
        },requests),count->onFx(generation,()->setBadge(UnreadBadgeFormatter.format(count))),
                error->onFx(generation,()->setBadge("!")),Duration.ofSeconds(10));
        unreadPoll.start();
    }
    private Button nav(String route,String text,Feather icon) {
        Button button=FxStyles.button(text,icon,()->openRoute(route),"nav-button");
        button.setMaxWidth(Double.MAX_VALUE); button.setId("nav-"+route); navigation.put(route,button); return button;
    }
    private void selectNav(String route) {
        navigation.forEach((key,button)->{button.getStyleClass().remove("nav-selected");
            if(key.equals(route)) button.getStyleClass().add("nav-selected");});
    }
    private void openRoute(String route) {
        if(closing || session==null || session.requiresPasswordChange()) return;
        if(forum!=null && shell.getCenter()==forum.view()) {
            if(!forum.canLeave())return;
            forum.deactivate();
        }
        if(library!=null && shell.getCenter()==library.view()) library.deactivate();
        if(shop!=null && shell.getCenter()==shop.view()) shop.deactivate();
        if(bank!=null && shell.getCenter()==bank.view()) bank.deactivate();
        if(academic!=null && shell.getCenter()==academic.view()) academic.deactivate();
        if(studentProfile!=null) studentProfile.deactivate();
        if(studentAdmin!=null) studentAdmin.deactivate();
        if(teacherProfile!=null) teacherProfile.deactivate();
        if(accountAdmin!=null) accountAdmin.deactivate();
        if(notifications!=null) notifications.deactivate();
        if(systemManagement!=null) systemManagement.deactivate();
        if("notifications".equals(route)) {showNotifications();return;}
        if("system-management".equals(route)) {showSystemManagement();return;}
        if(isIdentityRoute(route)) {showIdentity(route);return;}
        if(isAcademicRoute(route)) {
            AcademicController controller=showAcademic();
            Long sectionId=academicSectionId(route);
            if(sectionId!=null) controller.openSection(sectionId);
            else controller.open(academicSubroute(route));
            return;
        }
        if(route.equals("bank")||route.startsWith("bank-")) {showBank().open(route);return;}
        String shopRoute=ShopRoute.fromCampus(route);
        if(shopRoute!=null) {showShop().open(shopRoute);return;}
        if(route.equals("library")||route.equals("library-loans")||route.equals("library-reservations")) {showLibrary().open(route);return;}
        if(route.equals("forum")) {showForum().openHome();return;}
        if(route.equals("campus")) {showCampus(); return;}
        if(route.equals("workspace")) {showWorkspace(); return;}
        selectNav(route.equals("notifications")||route.equals("accounts")
                ||route.equals("system-management")?route:"workspace");
        shell.setCenter(legacyNode);
        long generation=sessionGeneration;
        SwingUtilities.invokeLater(()->{if(generation==sessionGeneration && legacy!=null) legacy.open(route);});
    }
    private ForumController showForum() {
        if(forum==null)forum=new ForumController(new SocketForumGateway(client,session.token()),session.roles(),requests,()->openRoute("workspace"));
        selectNav("workspace");shell.setCenter(forum.view());return forum;
    }
    private LibraryController showLibrary() {
        if(library==null){
            libraryRequests=Executors.newFixedThreadPool(2,runnable->{Thread thread=new Thread(runnable,"vcampus-library");thread.setDaemon(true);return thread;});
            long generation=sessionGeneration;
            library=new LibraryController(client,session.token(),session.roles(),libraryRequests,
                    ()->openRoute("workspace"),()->refreshUnread(generation));
        }
        selectNav("workspace");shell.setCenter(library.view());return library;
    }
    private ShopController showShop() {
        if(shop==null) {
            shopRequests=Executors.newFixedThreadPool(3,runnable->{
                Thread thread=new Thread(runnable,"vcampus-shop");thread.setDaemon(true);return thread;
            });
            long generation=sessionGeneration;
            var gateway=new SocketShopGateway(client,session.token());
            var cache=new ShopImageCache(gateway,shopRequests,ShopImageCacheConfig.defaults());
            shop=new ShopController(gateway,session.roles(),shopRequests,cache,
                    ()->openRoute("workspace"),()->refreshUnread(generation));
        }
        selectNav("workspace");shell.setCenter(shop.view());return shop;
    }
    private BankController showBank() {
        if(bank==null) {
            bankRequests=Executors.newFixedThreadPool(2,runnable->{
                Thread thread=new Thread(runnable,"vcampus-bank");thread.setDaemon(true);return thread;
            });
            long generation=sessionGeneration;
            bank=new BankController(new SocketBankGateway(client,session.token()),session.roles(),bankRequests,
                    ()->openRoute("workspace"),this::openRoute,()->refreshUnread(generation));
        }
        selectNav("workspace");shell.setCenter(bank.view());return bank;
    }
    private AcademicController showAcademic() {
        if(academic==null) {
            academicRequests=Executors.newFixedThreadPool(2,runnable->{
                Thread thread=new Thread(runnable,"vcampus-academic");thread.setDaemon(true);return thread;
            });
            long generation=sessionGeneration;
            academic=new AcademicController(new SocketAcademicGateway(client,session.token()),session.roles(),
                    academicRequests,()->openRoute("workspace"),()->refreshUnread(generation));
        }
        selectNav("workspace");shell.setCenter(academic.view());return academic;
    }

    private void showIdentity(String route) {
        String page=identityPage(route,session.roles());
        if("unauthorized".equals(page)) {
            VBox message=new VBox(10,FxStyles.label("无权访问","page-title"),FxStyles.label("当前账号没有访问该页面的权限。","muted"));
            message.setAlignment(Pos.CENTER);shell.setCenter(message);selectNav("workspace");return;
        }
        ExecutorService executor=identityExecutor();
        switch(page) {
            case "student-profile" -> {if(studentProfile==null)studentProfile=new StudentProfileController(new SocketStudentGateway(client,session.token()),executor,()->openRoute("workspace"));selectNav("workspace");shell.setCenter(studentProfile.view());studentProfile.activate();}
            case "teacher-profile" -> {if(teacherProfile==null)teacherProfile=new TeacherProfileController(new SocketTeacherGateway(client,session.token()),executor,()->openRoute("workspace"));selectNav("workspace");shell.setCenter(teacherProfile.view());teacherProfile.activate();}
            case "student-admin" -> {if(studentAdmin==null)studentAdmin=new StudentAdminController(new SocketStudentGateway(client,session.token()),executor,()->openRoute("workspace"));selectNav("workspace");shell.setCenter(studentAdmin.view());studentAdmin.activate();}
            case "accounts" -> {if(accountAdmin==null)accountAdmin=new AccountAdminController(new SocketAccountGateway(client,session.token()),executor,()->openRoute("workspace"));selectNav("accounts");shell.setCenter(accountAdmin.view());accountAdmin.activate();}
            default -> throw new IllegalStateException("未知身份资料页面");
        }
    }

    private void showSystemManagement() {
        if (!canOpenSystemManagement(session.roles())) {
            VBox message = new VBox(10, FxStyles.label("无权访问", "page-title"),
                    FxStyles.label("当前账号没有系统管理权限。", "muted"));
            message.setAlignment(Pos.CENTER);
            shell.setCenter(message);
            selectNav("workspace");
            return;
        }
        if (systemManagement == null) {
            systemManagementView = new SystemManagementView(() -> openRoute("workspace"));
            systemManagement = new SystemManagementController(
                    new SocketSystemGateway(client, session.token()), identityExecutor(), systemManagementView);
            SystemManagementController controller = systemManagement;
            systemManagementView.setListener(new SystemManagementView.Listener() {
                @Override public void saveTerm(com.vcampus.common.model.SystemTermConfig term) {
                    controller.saveTerm(term);
                }
                @Override public void savePeriods(java.util.List<com.vcampus.common.model.ClassPeriodTime> periods) {
                    controller.savePeriods(periods);
                }
                @Override public void searchAudit(SystemData.AuditQuery query) {
                    controller.searchAudit(query);
                }
            });
        }
        selectNav("system-management");
        shell.setCenter(systemManagementView);
        systemManagement.activate();
    }

    private ExecutorService identityExecutor() {
        if(identityRequests==null)identityRequests=Executors.newFixedThreadPool(3,runnable->{Thread thread=new Thread(runnable,"vcampus-identity");thread.setDaemon(true);return thread;});
        return identityRequests;
    }

    private void showNotifications() {
        if(notifications==null){
            long generation=sessionGeneration;
            notifications=new NotificationController(client,session.token(),requests,()->openRoute("workspace"),
                    this::openNotificationDestination,()->refreshUnread(generation));
        }
        selectNav("notifications");shell.setCenter(notifications.view());notifications.activate();
    }

    private void openNotificationDestination(NotificationDestination destination) {
        String route=notificationRoute(destination,session.roles());
        if(route==null)return;
        if(route.startsWith("library-book/")){showLibraryBook(Long.parseLong(route.substring("library-book/".length())));return;}
        if(route.startsWith("forum-post/")){showForumPost(Long.parseLong(route.substring("forum-post/".length())));return;}
        if(route.startsWith("shop-order/")){showShopOrder(Long.parseLong(route.substring("shop-order/".length())));return;}
        openRoute(route);
    }

    static String notificationRoute(NotificationDestination destination,Set<UserRole> roles) {
        if(destination==null||!destination.navigable())return null;
        Long id=destination.relatedEntityId();
        return switch(destination.target()){
            case TEACHER_SCHEDULE->"academic-teacher-schedule";
            case ACADEMIC_SCHEDULE->{
                if((roles.contains(UserRole.SUPER_ADMIN)||roles.contains(UserRole.ACADEMIC_ADMIN))&&id!=null&&id>0)yield "academic-section/"+id;
                if(roles.contains(UserRole.STUDENT))yield "academic-student-schedule";
                if(roles.contains(UserRole.TEACHER))yield "academic-teacher-schedule";
                yield null;
            }
            case STUDENT_GRADES->"academic-grades";
            case STUDENT_PROFILE->"personal-profile";
            case LIBRARY_LOANS->"library-loans";
            case LIBRARY_CATALOG->"library-book/"+id;
            case FORUM_POST->"forum-post/"+id;
            case BANK_LEDGER->"bank-ledger";
            case SHOP_ORDERS->"shop-order/"+id;
            case NONE->null;
        };
    }

    static boolean isIdentityRoute(String route) {
        return "personal-profile".equals(route)||"student-status".equals(route)||"accounts".equals(route);
    }

    static boolean canOpenSystemManagement(Set<UserRole> roles) {
        return SystemAccessPolicy.canManage(roles);
    }

    static String identityPage(String route,Set<UserRole> roles) {
        if("personal-profile".equals(route)) {
            if(roles.contains(UserRole.STUDENT))return "student-profile";
            if(roles.contains(UserRole.TEACHER))return "teacher-profile";
            return "unauthorized";
        }
        if("student-status".equals(route))return roles.contains(UserRole.SUPER_ADMIN)||roles.contains(UserRole.STUDENT_ADMIN)?"student-admin":"unauthorized";
        if("accounts".equals(route))return roles.contains(UserRole.SUPER_ADMIN)?"accounts":"unauthorized";
        throw new IllegalArgumentException("不是身份资料路由");
    }

    static boolean isAcademicRoute(String route) {
        return route!=null && (route.equals("academic") || route.startsWith("academic-")
                || route.equals("student-schedule") || route.equals("teacher-schedule"));
    }

    static String academicSubroute(String route) {
        if(route==null || route.equals("academic")) return "overview";
        return switch(route) {
            case "academic-enrollment" -> "enrollment";
            case "academic-student-schedule", "student-schedule" -> "student-schedule";
            case "academic-teacher-schedule", "teacher-schedule" -> "teacher-schedule";
            case "academic-grades" -> "grades";
            default -> route.substring("academic-".length());
        };
    }

    static Long academicSectionId(String route) {
        if(route==null || !route.startsWith("academic-section/")) return null;
        try {
            long id=Long.parseLong(route.substring("academic-section/".length()));
            return id>0?id:null;
        } catch(NumberFormatException invalid) {return null;}
    }
    private void showLibraryBook(long bookId) {
        if(closing||session==null||session.requiresPasswordChange())return;
        showLibrary().openBook(bookId);
    }
    private void showForumPost(long postId) {
        if(closing||session==null||session.requiresPasswordChange())return;
        showForum().openPost(postId);
    }
    private void showShopOrder(long orderId) {
        if(closing||session==null||session.requiresPasswordChange()||orderId<1)return;
        showShop().open("order/"+orderId);
    }
    private void showCampus() {
        selectNav("campus"); selectedDate=LocalDate.now(CAMPUS_ZONE);
        campus=new FxCampusView(session.displayName(),session.roles(),selectedDate,this::loadDate,
                ()->loadDate(selectedDate),this::openRoute);
        shell.setCenter(campus); loadDate(selectedDate);
    }
    private void loadDate(LocalDate date) {
        selectedDate=date; long version=++dateGeneration;
        FxCampusView view=campus; view.loading(date);
        CampusDashboardLoader loader=new CampusDashboardLoader(client,session.token(),session.roles(),session.displayName());
        if(dashboardLoad!=null) dashboardLoad.cancel(true);
        long generation=sessionGeneration;
        dashboardLoad=dashboardRequests.submit(()->{
            try {
                CampusDashboardData data=loader.load(date);
                onFx(generation,()->{if(version==dateGeneration) view.showData(data);});
            } catch(CancellationException ignored) {
                // A newer selected date or logout owns the next render.
            } catch(Exception error) {
                onFx(generation,()->{if(version==dateGeneration) view.showError(errorText(error));});
            }
        });
    }
    private void showWorkspace() {
        selectNav("workspace");
        VBox body=new VBox(26,FxStyles.label("工作台","page-title"),FxStyles.label("欢迎回来，"+session.displayName(),"muted"));
        body.getStyleClass().add("workspace-content");
        TilePane cards=new TilePane(18,18); cards.setPrefColumns(3); cards.setPrefTileWidth(260); cards.setPrefTileHeight(180);
        for(var card:WorkspaceCardResolver.resolve(session.roles())) {
            String route=switch(card.module()) {
                case PERSONAL_PROFILE->"personal-profile"; case STUDENT_STATUS->"student-status";
                case ACADEMIC->"academic"; case LIBRARY->"library"; case SHOP->"shop"; case BANK->"bank"; case FORUM->"forum";
                default->throw new IllegalStateException("未实现的工作台模块");
            };
            Feather icon=switch(card.module()) {
                case PERSONAL_PROFILE,STUDENT_STATUS->Feather.USER; case ACADEMIC->Feather.CALENDAR;
                case LIBRARY->Feather.BOOK_OPEN; case SHOP->Feather.SHOPPING_BAG; case BANK->Feather.CREDIT_CARD;
                default->Feather.MESSAGE_SQUARE;
            };
            Label description=FxStyles.label(card.description(),"muted"); description.setWrapText(true); description.setMaxWidth(220);
            VBox copy=new VBox(15,FxStyles.icon(icon,28),FxStyles.label(card.title(),"module-title"),description);
            Button button=new Button(); button.setGraphic(copy); button.getStyleClass().add("module-card");
            button.setPrefSize(260,180); button.setAccessibleText(card.title()); button.setOnAction(e->openRoute(route)); cards.getChildren().add(button);
        }
        body.getChildren().add(cards);
        ScrollPane scroll=new ScrollPane(body); scroll.setFitToWidth(true); scroll.getStyleClass().add("campus-scroll"); shell.setCenter(scroll);
    }
    private void refreshUnread(long generation) {
        onFx(generation,()->{if(unreadPoll!=null) unreadPoll.refreshNow();});
    }
    private void setBadge(String text) {
        badge.setText(text); badge.setVisible(!text.isEmpty()); badge.setManaged(!text.isEmpty());
    }
    private <T> void request(Callable<T> work,Consumer<T> success,Consumer<String> failure) {
        long generation=sessionGeneration;
        CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception error){throw new CompletionException(error);}},requests)
                .whenComplete((result,error)->onFx(generation,()->{if(error==null)success.accept(result);else failure.accept(errorText(error));}));
    }
    private void onFx(long generation,Runnable action) {
        if(closing || generation!=sessionGeneration) return;
        Platform.runLater(()->{if(!closing && generation==sessionGeneration) action.run();});
    }
    private static String errorText(Throwable error) {
        while((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause()!=null) error=error.getCause();
        if(error instanceof java.net.SocketTimeoutException) return "请求超时，请检查服务器连接后重试";
        if(error instanceof java.io.IOException) return "无法连接服务器，请检查地址、端口和网络后重试";
        return error.getMessage()==null?"请求失败，请稍后重试":error.getMessage();
    }
    private void logout() {clearSession(); showLogin();}
    private void clearSession() {
        ++sessionGeneration; ++dateGeneration;
        if(forum!=null){forum.close();forum=null;}
        if(library!=null){library.close();library=null;}
        if(shop!=null){shop.close();shop=null;}
        if(bank!=null){bank.close();bank=null;}
        if(academic!=null){academic.close();academic=null;}
        if(studentProfile!=null){studentProfile.close();studentProfile=null;}
        if(studentAdmin!=null){studentAdmin.close();studentAdmin=null;}
        if(teacherProfile!=null){teacherProfile.close();teacherProfile=null;}
        if(accountAdmin!=null){accountAdmin.close();accountAdmin=null;}
        if(notifications!=null){notifications.close();notifications=null;}
        if(systemManagement!=null){systemManagement.close();systemManagement=null;systemManagementView=null;}
        if(libraryRequests!=null){libraryRequests.shutdownNow();libraryRequests=null;}
        if(shopRequests!=null){shopRequests.shutdownNow();shopRequests=null;}
        if(bankRequests!=null){bankRequests.shutdownNow();bankRequests=null;}
        if(academicRequests!=null){academicRequests.shutdownNow();academicRequests=null;}
        if(identityRequests!=null){identityRequests.shutdownNow();identityRequests=null;}
        if(dashboardLoad!=null) {dashboardLoad.cancel(true); dashboardLoad=null;}
        if(dashboardRequests!=null) {dashboardRequests.shutdownNow(); dashboardRequests=null;}
        if(unreadPoll!=null) {unreadPoll.close(); unreadPoll=null;}
        ClientSession old=session; VCampusClient oldClient=client; session=null; client=null; campus=null;
        SwingNode oldNode=legacyNode; legacyNode=null;
        SwingUtilities.invokeLater(()->{
            if(legacy!=null) legacy.close();
            if(oldNode!=null) oldNode.setContent(null);
            legacy=null;
        });
        navigation.clear(); shell=null;
        if(old!=null) revoke(oldClient,old.token());
    }
    private static void revokeResponse(VCampusClient client,ResponseMessage response) {
        if(response!=null && response.success()) {String token=response.data().get("sessionToken"); if(token!=null) revoke(client,token);}
    }
    private static void revoke(VCampusClient client,String token) {
        Thread.ofPlatform().name("vcampus-session-logout").start(()->{try{client.logout(token);}catch(Exception ignored){}});
    }
    private void shutdown() {if(closing)return; closing=true; clearSession(); requests.shutdownNow();}
    @Override public void stop() {shutdown();}
}
