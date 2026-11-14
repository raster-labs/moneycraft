package com.raster.moneycraft.foundation.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

public class TenantAwareBusinessDataSource extends DelegatingDataSource {

    private static final String APPLY_SESSION_SCOPE_SQL =
        "SELECT set_config('app.current_company_id', ?, false), set_config('app.bypass_rls', ?, false)";

    public TenantAwareBusinessDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection raw = obtainTargetDataSource().getConnection();
        return decorateConnection(raw);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection raw = obtainTargetDataSource().getConnection(username, password);
        return decorateConnection(raw);
    }

    public Connection getPinnedRawConnection() throws SQLException {
        return obtainTargetDataSource().getConnection();
    }

    public static void applyTransactionLocalScope(Connection connection, TenantScope scope) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT set_config('app.current_company_id', ?, true), set_config('app.bypass_rls', ?, true)"
        )) {
            ps.setString(1, scope.companyIdSetting());
            ps.setString(2, scope.bypassRlsSetting());
            ps.execute();
        }
    }

    private Connection decorateConnection(Connection raw) throws SQLException {
        TenantScope scope = TenantContextHolder.current();
        try {
            applySessionScope(raw, scope.companyIdSetting(), scope.bypassRlsSetting());
        } catch (SQLException ex) {
            raw.close();
            throw ex;
        }
        InvocationHandler handler = new ResettingConnectionHandler(raw);
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            handler
        );
    }

    private static void applySessionScope(Connection conn, String companyId, String bypass) throws SQLException {
        if (conn.isClosed()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(APPLY_SESSION_SCOPE_SQL)) {
            ps.setString(1, companyId);
            ps.setString(2, bypass);
            ps.execute();
        }
    }

    private static final class ResettingConnectionHandler implements InvocationHandler {

        private final Connection delegate;
        private boolean closed;

        private ResettingConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("close".equals(name)) {
                if (!closed) {
                    closed = true;
                    try {
                        if (!delegate.isClosed() && delegate.getAutoCommit()) {
                            applySessionScope(delegate, "", "off");
                        }
                    } catch (SQLException ignored) {
                        // Connection broken or mid-rollback; HikariCP will evict unhealthy connections.
                    } finally {
                        delegate.close();
                    }
                }
                return null;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }
    }
}
