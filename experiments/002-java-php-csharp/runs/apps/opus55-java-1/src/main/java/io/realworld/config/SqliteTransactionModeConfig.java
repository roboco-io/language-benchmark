package io.realworld.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.sqlite.SQLiteConfig.TransactionMode;
import org.sqlite.SQLiteConnection;

/**
 * Chooses the SQLite transaction mode per transaction.
 *
 * <p>A DEFERRED transaction that reads first and writes later cannot wait for the write lock: SQLite
 * fails it with SQLITE_BUSY immediately instead of honouring busy_timeout. Write transactions therefore
 * start with BEGIN IMMEDIATE, which takes the write lock up front and waits up to busy_timeout for it.
 * Read-only transactions (Spring marks their connection read-only before BEGIN) stay DEFERRED so that
 * readers run concurrently under WAL.
 */
@Configuration(proxyBeanMethods = false)
public class SqliteTransactionModeConfig {

    @Bean
    static BeanPostProcessor sqliteTransactionModeDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource ds && !(bean instanceof TransactionModeDataSource)
                        ? new TransactionModeDataSource(ds)
                        : bean;
            }
        };
    }

    static final class TransactionModeDataSource extends DelegatingDataSource {

        TransactionModeDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return wrap(super.getConnection(username, password));
        }

        private static Connection wrap(Connection target) {
            boolean[] readOnly = {false};
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setReadOnly" -> readOnly[0] = (Boolean) args[0];
                            case "setAutoCommit" -> {
                                if (!((Boolean) args[0])) {
                                    target.unwrap(SQLiteConnection.class).getConnectionConfig().setTransactionMode(
                                            readOnly[0] ? TransactionMode.DEFERRED : TransactionMode.IMMEDIATE);
                                }
                            }
                            case "close" -> readOnly[0] = false;
                            default -> { }
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        }
    }
}
