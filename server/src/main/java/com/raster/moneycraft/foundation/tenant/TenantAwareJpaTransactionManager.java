package com.raster.moneycraft.foundation.tenant;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf, DataSource dataSource) {
        super(emf);
        setDataSource(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        DataSource ds = getDataSource();
        if (ds == null) {
            return;
        }
        Connection connection = DataSourceUtils.getConnection(ds);
        try {
            TenantAwareBusinessDataSource.applyTransactionLocalScope(
                connection,
                TenantContextHolder.current()
            );
        } catch (SQLException ex) {
            throw new CannotCreateTransactionException(
                "Failed to bind PostgreSQL 18 RLS tenant context on Business DB transaction",
                ex
            );
        } finally {
            DataSourceUtils.releaseConnection(connection, ds);
        }
    }
}
