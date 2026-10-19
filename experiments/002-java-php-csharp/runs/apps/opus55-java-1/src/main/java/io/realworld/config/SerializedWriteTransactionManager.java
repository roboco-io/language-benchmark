package io.realworld.config;

import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * SQLite allows a single writer at a time, and its busy handler retries at random intervals rather than
 * queueing waiters, so under load an unlucky writer can starve past busy_timeout. This transaction manager
 * admits read-write transactions one at a time through a fair (FIFO) lock inside the JVM; read-only
 * transactions are not affected and keep running concurrently under WAL.
 */
@Configuration(proxyBeanMethods = false)
public class SerializedWriteTransactionManager {

    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        Manager manager = new Manager();
        manager.setEntityManagerFactory(entityManagerFactory);
        return manager;
    }

    static final class Manager extends JpaTransactionManager {

        private final ReentrantLock writeLock = new ReentrantLock(true);

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (definition.isReadOnly()) {
                super.doBegin(transaction, definition);
                return;
            }
            writeLock.lock();
            try {
                super.doBegin(transaction, definition);
            } catch (RuntimeException | Error e) {
                writeLock.unlock();
                throw e;
            }
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            try {
                super.doCleanupAfterCompletion(transaction);
            } finally {
                if (writeLock.isHeldByCurrentThread()) {
                    writeLock.unlock();
                }
            }
        }
    }
}
