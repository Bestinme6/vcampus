package com.vcampus.client.fx.academic;

import com.vcampus.common.model.RoleCompositionPolicy;
import com.vcampus.common.model.UserRole;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** Session-scoped coordinator for the native JavaFX academic module. */
public final class AcademicController implements AutoCloseable {
    private final AcademicGateway gateway;
    private final Set<UserRole> roles;
    private final AcademicAsync async;
    private final AcademicWorkspaceView workspace;
    private final Runnable unreadRefresh;
    private boolean active = true;
    private boolean closed;

    public AcademicController(AcademicGateway gateway, Set<UserRole> roles, Executor executor,
                              Runnable back, Runnable unreadRefresh) {
        requireFx();
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        RoleCompositionPolicy.requireValid(this.roles);
        this.async = new AcademicAsync(Objects.requireNonNull(executor, "executor"), Platform::runLater);
        this.unreadRefresh = Objects.requireNonNull(unreadRefresh, "unreadRefresh");
        this.workspace = new AcademicWorkspaceView(this.roles,
                () -> {
                    if (!closed) back.run();
                }, this::open);
    }

    public Parent view() {
        return workspace;
    }

    public void open(String route) {
        requireFx();
        if (closed) return;
        active = true;
        async.invalidate();
        workspace.open(route);
    }

    public String activeRoute() {
        return workspace.activeRoute();
    }

    public void deactivate() {
        requireFx();
        active = false;
        async.invalidate();
        workspace.closeDialogs();
        workspace.busy(false);
    }

    @Override
    public void close() {
        requireFx();
        if (closed) return;
        closed = true;
        active = false;
        async.close();
        workspace.closeDialogs();
        workspace.busy(false);
    }

    AcademicGateway gateway() {
        return gateway;
    }

    Set<UserRole> roles() {
        return roles;
    }

    AcademicAsync async() {
        return async;
    }

    AcademicWorkspaceView workspace() {
        return workspace;
    }

    Runnable unreadRefresh() {
        return unreadRefresh;
    }

    boolean active() {
        return active && !closed;
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("教务界面必须在 JavaFX 线程操作");
        }
    }
}
