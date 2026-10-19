package io.realworld.common;

import java.util.concurrent.locks.ReentrantLock;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * SQLite allows only one writer at a time. Instead of letting concurrent write
 * transactions race for the database lock (and fail with SQLITE_BUSY when a read
 * lock cannot be upgraded), read-write transactions are serialized in-process with
 * a fair lock taken before the transaction begins. Read-only transactions never take
 * the lock and run concurrently thanks to WAL mode. busy_timeout still covers
 * contention with other processes and checkpoints.
 */
public class SqliteTransactionManager extends JpaTransactionManager {

    private final transient ReentrantLock writeLock = new ReentrantLock(true);

    public SqliteTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        boolean write = !definition.isReadOnly();
        if (write) {
            writeLock.lock();
        }
        try {
            super.doBegin(transaction, definition);
        } catch (RuntimeException | Error e) {
            if (write) {
                writeLock.unlock();
            }
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
