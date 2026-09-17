package com.vcampus.server.network;

import com.vcampus.server.config.ServerConfig;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.config.ShopImageConfig;
import com.vcampus.server.config.ShopReviewImageConfig;
import com.vcampus.server.config.SecondhandImageConfig;
import com.vcampus.server.config.SecondhandReviewImageConfig;
import com.vcampus.server.database.AuditRepository;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.UserRepository;
import com.vcampus.server.database.StudentRepository;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.CurriculumRepository;
import com.vcampus.server.database.ScheduleRevisionRepository;
import com.vcampus.server.database.TeacherRepository;
import com.vcampus.server.database.AccountRepository;
import com.vcampus.server.database.NotificationRepository;
import com.vcampus.server.database.LibraryCatalogRepository;
import com.vcampus.server.database.LibraryLoanRepository;
import com.vcampus.server.database.LibraryNoticeRepository;
import com.vcampus.server.database.LibraryFeeRepository;
import com.vcampus.server.database.ForumRepository;
import com.vcampus.server.database.ForumReportRepository;
import com.vcampus.server.database.ForumPostImageRepository;
import com.vcampus.server.database.ForumBoostRepository;
import com.vcampus.server.database.BankRepository;
import com.vcampus.server.database.ShopRepository;
import com.vcampus.server.database.ShopAddressRepository;
import com.vcampus.server.database.ShopReviewRepository;
import com.vcampus.server.database.ShopReviewImageRepository;
import com.vcampus.server.database.SecondhandListingRepository;
import com.vcampus.server.database.SecondhandImageRepository;
import com.vcampus.server.database.SecondhandOrderRepository;
import com.vcampus.server.database.SecondhandReviewRepository;
import com.vcampus.server.database.SecondhandReviewImageRepository;
import com.vcampus.server.database.SystemManagementRepository;
import com.vcampus.server.database.AuditQueryRepository;
import com.vcampus.server.image.FileShopImageStore;
import com.vcampus.server.image.ShopImageStore;
import com.vcampus.server.image.FileShopReviewImageStore;
import com.vcampus.server.security.PasswordHasher;
import com.vcampus.server.security.SessionManager;
import com.vcampus.server.service.AuthService;
import com.vcampus.server.service.RequestRouter;
import com.vcampus.server.service.StudentService;
import com.vcampus.server.service.AcademicService;
import com.vcampus.server.service.TeacherProfileService;
import com.vcampus.server.service.AccountService;
import com.vcampus.server.service.NotificationService;
import com.vcampus.server.service.LibraryService;
import com.vcampus.server.service.LibraryOverdueNotifier;
import com.vcampus.server.service.ForumService;
import com.vcampus.server.service.ForumReportService;
import com.vcampus.server.service.ForumImageService;
import com.vcampus.server.config.ForumImageConfig;
import com.vcampus.server.image.FileForumImageStore;
import com.vcampus.server.service.BankService;
import com.vcampus.server.service.ShopService;
import com.vcampus.server.service.ShopAddressService;
import com.vcampus.server.service.ShopImageService;
import com.vcampus.server.service.ShopReviewService;
import com.vcampus.server.service.ShopReviewImageService;
import com.vcampus.server.service.SystemManagementService;
import com.vcampus.server.service.SecondhandListingService;
import com.vcampus.server.service.SecondhandImageService;
import com.vcampus.server.service.SecondhandOrderService;
import com.vcampus.server.service.SecondhandReviewService;
import com.vcampus.server.service.SecondhandReviewImageService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.time.Duration;
import java.time.Clock;

public final class VCampusServer implements AutoCloseable {
    private final ServerConfig config;
    private final RequestRouter router;
    private final ExecutorService clientExecutor;
    private final ShopImageService shopImageService;
    private final ShopReviewImageService shopReviewImageService;
    private final SecondhandImageService secondhandImageService;
    private final SecondhandReviewImageService secondhandReviewImageService;
    private ScheduledExecutorService shopImageCleanupExecutor;
    private ScheduledExecutorService shopReviewImageCleanupExecutor;
    private ScheduledExecutorService forumImageCleanupExecutor;
    private ScheduledExecutorService secondhandImageCleanupExecutor;
    private ScheduledExecutorService secondhandReviewImageCleanupExecutor;
    private final ForumImageService forumImageService;
    private final LibraryOverdueNotifier libraryNotifier;
    private final com.vcampus.server.service.LibraryPaperService paperService;
    private volatile boolean running;
    private boolean closed;
    private ServerSocket serverSocket;

    public VCampusServer(ServerConfig config) {
        this.config = config;
        ConnectionFactory connections = new ConnectionFactory(DatabaseConfig.fromEnvironment());
        PasswordHasher passwordHasher = new PasswordHasher();
        SessionManager sessionManager = new SessionManager();
        NotificationRepository notificationRepository = new NotificationRepository(connections);
        UserRepository userRepository = new UserRepository(connections);
        AuditRepository auditRepository = new AuditRepository(connections);
        AuditQueryRepository auditQueryRepository = new AuditQueryRepository(connections);
        AuthService authService = new AuthService(
                userRepository,
                auditRepository,
                passwordHasher,
                sessionManager,
                auditQueryRepository);
        StudentService studentService = new StudentService(
                new StudentRepository(connections, notificationRepository), sessionManager);
        AcademicService academicService = new AcademicService(
                new AcademicRepository(connections, notificationRepository),
                new CurriculumRepository(connections),
                new ScheduleRevisionRepository(connections, notificationRepository),
                sessionManager, auditRepository);
        TeacherProfileService teacherProfileService = new TeacherProfileService(
                new TeacherRepository(connections), sessionManager);
        AccountService accountService = new AccountService(
                new AccountRepository(connections, notificationRepository),
                auditRepository, passwordHasher, sessionManager);
        SystemManagementService systemManagementService = new SystemManagementService(
                new SystemManagementRepository(connections),
                auditQueryRepository, auditRepository, sessionManager);
        NotificationService notificationService = new NotificationService(
                notificationRepository, sessionManager);
        BankRepository bankRepository = new BankRepository(connections, notificationRepository);
        LibraryFeeRepository libraryFeeRepository = new LibraryFeeRepository(
                connections, notificationRepository, bankRepository);
        LibraryService libraryService = new LibraryService(
                new LibraryCatalogRepository(connections),
                new LibraryLoanRepository(connections, notificationRepository, libraryFeeRepository),
                new com.vcampus.server.database.LibraryReservationRepository(connections),
                libraryFeeRepository,
                sessionManager, auditRepository, Clock.systemUTC());
        FileForumImageStore forumImages=new FileForumImageStore(ForumImageConfig.fromEnvironment());
        ForumImageService forumImageService=new ForumImageService(forumImages,new ForumPostImageRepository(connections),sessionManager);
        this.forumImageService=forumImageService;
        ForumService forumService = new ForumService(
                new ForumRepository(connections, notificationRepository), sessionManager,
                new com.vcampus.server.database.ForumCommunityRepository(connections),
                forumImageService,
                new ForumBoostRepository(connections, bankRepository, notificationRepository),
                auditRepository);
        ForumReportService forumReportService = new ForumReportService(
                new ForumReportRepository(connections, notificationRepository), sessionManager, auditRepository);
        BankService bankService = new BankService(bankRepository, sessionManager, auditRepository);
        ShopAddressRepository shopAddressRepository = new ShopAddressRepository(connections);
        ShopRepository shopRepository = new ShopRepository(
                connections, bankRepository, notificationRepository, shopAddressRepository);
        ShopAddressService shopAddressService = new ShopAddressService(
                shopAddressRepository, sessionManager);
        ShopImageConfig shopImageConfig = ShopImageConfig.fromEnvironment();
        ShopImageStore shopImageStore = new FileShopImageStore(shopImageConfig);
        ShopImageService shopImageService = new ShopImageService(
                shopRepository, shopImageStore, sessionManager);
        this.shopImageService = shopImageService;
        ShopReviewRepository shopReviewRepository = new ShopReviewRepository(connections);
        ShopReviewImageService shopReviewImageService = new ShopReviewImageService(
                new FileShopReviewImageStore(ShopReviewImageConfig.fromEnvironment()),
                new ShopReviewImageRepository(connections), sessionManager);
        ShopReviewService shopReviewService = new ShopReviewService(
                shopReviewRepository, shopReviewImageService, sessionManager, auditRepository);
        ShopService shopService = new ShopService(
                shopRepository, shopReviewRepository, sessionManager, auditRepository);
        SecondhandListingRepository secondhandListings =
                new SecondhandListingRepository(connections);
        SecondhandImageRepository secondhandImages = new SecondhandImageRepository(connections);
        SecondhandListingService secondhandListingService =
                new SecondhandListingService(secondhandListings, secondhandImages, sessionManager);
        SecondhandImageService secondhandImageService = new SecondhandImageService(
                secondhandListings, secondhandImages,
                new FileShopImageStore(SecondhandImageConfig.fromEnvironment()), sessionManager);
        SecondhandOrderService secondhandOrderService = new SecondhandOrderService(
                new SecondhandOrderRepository(connections, bankRepository,
                        shopAddressRepository, notificationRepository), sessionManager);
        SecondhandReviewImageService secondhandReviewImageService =
                new SecondhandReviewImageService(
                        new FileShopReviewImageStore(
                                SecondhandReviewImageConfig.fromEnvironment()),
                        new SecondhandReviewImageRepository(connections), sessionManager);
        SecondhandReviewService secondhandReviewService = new SecondhandReviewService(
                new SecondhandReviewRepository(connections), secondhandReviewImageService,
                sessionManager);
        this.secondhandImageService = secondhandImageService;
        this.secondhandReviewImageService = secondhandReviewImageService;
        this.shopReviewImageService = shopReviewImageService;
        this.libraryNotifier = new LibraryOverdueNotifier(
                new LibraryNoticeRepository(connections, notificationRepository));
        try {
            this.paperService=new com.vcampus.server.service.LibraryPaperService(
                    new com.vcampus.server.database.LibraryPaperRepository(connections),
                    com.vcampus.server.paper.PaperFileStore.fromEnvironment(),sessionManager);
        } catch(IOException e) { throw new IllegalStateException("无法初始化论文 PDF 目录，请检查 VCAMPUS_PAPER_DIR",e); }
        this.router = new RequestRouter(
                authService, studentService, academicService, teacherProfileService,
                accountService, notificationService, libraryService, forumService,
                forumReportService, forumImageService, bankService,
                shopService, shopImageService, sessionManager, paperService,
                systemManagementService, shopReviewService, shopReviewImageService,
                shopAddressService, secondhandListingService, secondhandImageService,
                secondhandOrderService, secondhandReviewService, secondhandReviewImageService);
        this.clientExecutor = Executors.newFixedThreadPool(config.workerThreads());
    }

    public void start() throws IOException {
        libraryNotifier.start();
        try {
            serverSocket = new ServerSocket(config.port());
            running = true;
            startShopImageCleanup();
            startShopReviewImageCleanup();
            startForumImageCleanup();
            startSecondhandImageCleanup();
            startSecondhandReviewImageCleanup();
            System.out.printf("VCampus server listening on port %d with %d worker threads%n",
                    config.port(), config.workerThreads());

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    client.setKeepAlive(true);
                    client.setTcpNoDelay(true);
                    clientExecutor.submit(new ClientHandler(client, router));
                } catch (IOException exception) {
                    if (running) {
                        throw exception;
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        libraryNotifier.close();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // The server is already shutting down.
            }
        }
        clientExecutor.shutdownNow();
        paperService.close();
        if (shopImageCleanupExecutor != null) shopImageCleanupExecutor.shutdownNow();
        if (shopReviewImageCleanupExecutor != null) shopReviewImageCleanupExecutor.shutdownNow();
        if (forumImageCleanupExecutor != null) forumImageCleanupExecutor.shutdownNow();
        if (secondhandImageCleanupExecutor != null) secondhandImageCleanupExecutor.shutdownNow();
        if (secondhandReviewImageCleanupExecutor != null) secondhandReviewImageCleanupExecutor.shutdownNow();
    }

    private synchronized void startShopImageCleanup() {
        if (closed) throw new IllegalStateException("Server is closed");
        if (shopImageCleanupExecutor == null) {
            shopImageCleanupExecutor = shopImageService.startCleanupScheduler(Duration.ofMinutes(30));
        }
    }
    private synchronized void startShopReviewImageCleanup() {
        if (closed) throw new IllegalStateException("Server is closed");
        if (shopReviewImageCleanupExecutor == null) {
            shopReviewImageCleanupExecutor = shopReviewImageService.startCleanupScheduler(
                    Duration.ofMinutes(30));
        }
    }
    private synchronized void startForumImageCleanup(){if(closed)throw new IllegalStateException("Server is closed");if(forumImageCleanupExecutor==null)forumImageCleanupExecutor=forumImageService.startCleanupScheduler(Duration.ofMinutes(30));}
    private synchronized void startSecondhandImageCleanup() {
        if (closed) throw new IllegalStateException("Server is closed");
        if (secondhandImageCleanupExecutor == null) {
            secondhandImageCleanupExecutor = secondhandImageService.startCleanupScheduler(
                    Duration.ofMinutes(30));
        }
    }
    private synchronized void startSecondhandReviewImageCleanup() {
        if (closed) throw new IllegalStateException("Server is closed");
        if (secondhandReviewImageCleanupExecutor == null) {
            secondhandReviewImageCleanupExecutor = secondhandReviewImageService
                    .startCleanupScheduler(Duration.ofMinutes(30));
        }
    }
}
