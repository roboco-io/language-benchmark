package main

import (
	"database/sql"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	_ "modernc.org/sqlite" // pure-Go SQLite driver, registered as "sqlite"
)

// openDB opens the SQLite database with the modernc.org/sqlite driver,
// enables WAL mode and a 5000 ms busy timeout on every pooled connection,
// and creates the schema if it does not exist yet.
func openDB(path string) (*gorm.DB, error) {
	if dir := filepath.Dir(path); dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, err
		}
	}
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)&_txlock=immediate", path)
	sqlDB, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	sqlDB.SetMaxOpenConns(runtime.NumCPU())
	sqlDB.SetMaxIdleConns(runtime.NumCPU())

	db, err := gorm.Open(sqlite.Dialector{DriverName: "sqlite", Conn: sqlDB}, &gorm.Config{
		Logger: logger.New(log.New(os.Stderr, "", log.LstdFlags), logger.Config{
			SlowThreshold:             200 * time.Millisecond,
			LogLevel:                  logger.Warn,
			IgnoreRecordNotFoundError: true,
		}),
		SkipDefaultTransaction: true,
	})
	if err != nil {
		return nil, err
	}

	var mode string
	var timeout int
	if err := db.Raw("PRAGMA journal_mode").Scan(&mode).Error; err != nil {
		return nil, err
	}
	if err := db.Raw("PRAGMA busy_timeout").Scan(&timeout).Error; err != nil {
		return nil, err
	}
	if mode != "wal" || timeout != 5000 {
		return nil, fmt.Errorf("unexpected pragmas: journal_mode=%s busy_timeout=%d", mode, timeout)
	}

	if err := db.AutoMigrate(&User{}, &Follow{}, &Article{}, &Tag{}, &ArticleTag{}, &Favorite{}, &Comment{}); err != nil {
		return nil, err
	}
	return db, nil
}
