package com.example.realworld;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction helper. SQLite allows a single writer at a time, so write transactions are serialized with a
 * process-wide lock; this avoids SQLITE_BUSY on read-to-write lock upgrades. Reads run concurrently (WAL).
 */
@Component
public class Tx {
    private final ReentrantLock writeLock = new ReentrantLock();
    private final TransactionTemplate template;

    public Tx(PlatformTransactionManager tm) {
        this.template = new TransactionTemplate(tm);
    }

    public <T> T read(Supplier<T> work) {
        return template.execute(s -> work.get());
    }

    public <T> T write(Supplier<T> work) {
        writeLock.lock();
        try {
            return template.execute(s -> work.get());
        } finally {
            writeLock.unlock();
        }
    }
}
