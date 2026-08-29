package com.vcampus.server.network;

import com.vcampus.server.config.ServerConfig;
import com.vcampus.server.config.DatabaseConfig;
import com.vcampus.server.database.AuditRepository;
import com.vcampus.server.database.ConnectionFactory;
import com.vcampus.server.database.UserRepository;
import com.vcampus.server.database.StudentRepository;
import com.vcampus.server.database.AcademicRepository;
import com.vcampus.server.database.TeacherRepository;
import com.vcampus.server.database.AccountRepository;
import com.vcampus.server.database.NotificationRepository;
import com.vcampus.server.database.LibraryCatalogRepository;
import com.vcampus.server.database.LibraryLoanRepository;
import com.vcampus.server.database.LibraryNoticeRepository;
import com.vcampus.server.database.ForumRepository;
import com.vcampus.server.database.BankRepository;
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
import com.vcampus.server.service.BankService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Clock;

public final class VCampusServer implements AutoCloseable {
    private final ServerConfig config;
    private final RequestRouter router;
    private final ExecutorService clientExecutor;
    private final LibraryOverdueNotifier libraryNotifier;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public VCampusServer(ServerConfig config) {
        this.config = config;
        ConnectionFactory connections = new ConnectionFactory(DatabaseConfig.fromEnvironment());
        PasswordHasher passwordHasher = new PasswordHasher();
        SessionManager sessionManager = new SessionManager();
        NotificationRepository notificationRepository = new NotificationRepository(connections);
        UserRepository userRepository = new UserRepository(connections);
        AuditRepository auditRepository = new AuditRepository(connections);
        AuthService authService = new AuthService(
                userRepository,
                auditRepository,
                passwordHasher,
                sessionManager);
        StudentService studentService = new StudentService(
                new StudentRepository(connections, notificationRepository), sessionManager);
        AcademicService academicService = new AcademicService(
                new AcademicRepository(connections, notificationRepository), sessionManager);
        TeacherProfileService teacherProfileService = new TeacherProfileService(
                new TeacherRepository(connections), sessionManager);
        AccountService accountService = new AccountService(
                new AccountRepository(connections, notificationRepository),
                auditRepository, passwordHasher, sessionManager);
        NotificationService notificationService = new NotificationService(
                notificationRepository, sessionManager);
        LibraryService libraryService = new LibraryService(
                new LibraryCatalogRepository(connections),
                new LibraryLoanRepository(connections, notificationRepository),
                sessionManager, auditRepository, Clock.systemUTC());
        ForumService forumService = new ForumService(
                new ForumRepository(connections, notificationRepository), sessionManager);
        BankRepository bankRepository = new BankRepository(connections, notificationRepository);
        BankService bankService = new BankService(bankRepository, sessionManager);
        this.libraryNotifier = new LibraryOverdueNotifier(
                new LibraryNoticeRepository(connections, notificationRepository));
        this.router = new RequestRouter(
                authService, studentService, academicService, teacherProfileService,
                accountService, notificationService, libraryService, forumService, bankService,
                sessionManager);
        this.clientExecutor = Executors.newFixedThreadPool(config.workerThreads());
    }

    public void start() throws IOException {
        libraryNotifier.start();
        serverSocket = new ServerSocket(config.port());
        running = true;
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
    }

    @Override
    public void close() {
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
    }
}
