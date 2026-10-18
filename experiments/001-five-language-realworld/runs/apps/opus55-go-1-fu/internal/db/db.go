// Package db opens the SQLite database and defines the persistent models.
package db

import (
	"fmt"
	"log"
	"net/url"
	"runtime"
	"time"

	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"

	// Pure-Go SQLite driver registered under the name "sqlite".
	_ "modernc.org/sqlite"
)

type User struct {
	ID           uint   `gorm:"primaryKey"`
	Username     string `gorm:"uniqueIndex;not null"`
	Email        string `gorm:"uniqueIndex;not null"`
	PasswordHash string `gorm:"not null"`
	Bio          *string
	Image        *string
	CreatedAt    time.Time
	UpdatedAt    time.Time
}

type Follow struct {
	FollowerID uint `gorm:"primaryKey"`
	FolloweeID uint `gorm:"primaryKey;index"`
}

type Article struct {
	ID          uint      `gorm:"primaryKey"`
	Slug        string    `gorm:"uniqueIndex;not null"`
	Title       string    `gorm:"not null"`
	Description string    `gorm:"not null"`
	Body        string    `gorm:"not null"`
	AuthorID    uint      `gorm:"index;not null"`
	CreatedAt   time.Time `gorm:"index"`
	UpdatedAt   time.Time
}

type Tag struct {
	ID   uint   `gorm:"primaryKey"`
	Name string `gorm:"uniqueIndex;not null"`
}

// ArticleTag links an article to a tag; Position preserves the tagList order.
type ArticleTag struct {
	ArticleID uint `gorm:"primaryKey"`
	TagID     uint `gorm:"primaryKey;index"`
	Position  int  `gorm:"not null"`
}

type Favorite struct {
	UserID    uint `gorm:"primaryKey"`
	ArticleID uint `gorm:"primaryKey;index"`
}

type Comment struct {
	ID        uint   `gorm:"primaryKey"`
	Body      string `gorm:"not null"`
	ArticleID uint   `gorm:"index;not null"`
	AuthorID  uint   `gorm:"not null"`
	CreatedAt time.Time
	UpdatedAt time.Time
}

// Open opens (or creates) the SQLite database at path in WAL mode with a
// 5000 ms busy timeout and migrates the schema.
func Open(path string) (*gorm.DB, error) {
	q := url.Values{}
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "synchronous(NORMAL)")
	// BEGIN IMMEDIATE avoids lock-upgrade deadlocks between concurrent writers.
	q.Add("_txlock", "immediate")
	dsn := fmt.Sprintf("file:%s?%s", path, q.Encode())

	gdb, err := gorm.Open(sqlite.Dialector{DriverName: "sqlite", DSN: dsn}, &gorm.Config{
		Logger:                 logger.Default.LogMode(logger.Silent),
		SkipDefaultTransaction: true,
		PrepareStmt:            true,
		NowFunc:                func() time.Time { return time.Now().UTC() },
	})
	if err != nil {
		return nil, err
	}

	sqlDB, err := gdb.DB()
	if err != nil {
		return nil, err
	}
	conns := runtime.NumCPU() * 2
	if conns < 4 {
		conns = 4
	}
	sqlDB.SetMaxOpenConns(conns)
	sqlDB.SetMaxIdleConns(conns)
	sqlDB.SetConnMaxLifetime(0)

	var journal string
	var busy int
	if err := gdb.Raw("PRAGMA journal_mode").Scan(&journal).Error; err != nil {
		return nil, err
	}
	if err := gdb.Raw("PRAGMA busy_timeout").Scan(&busy).Error; err != nil {
		return nil, err
	}
	log.Printf("sqlite: journal_mode=%s busy_timeout=%dms max_conns=%d", journal, busy, conns)

	if err := gdb.AutoMigrate(&User{}, &Follow{}, &Article{}, &Tag{}, &ArticleTag{}, &Favorite{}, &Comment{}); err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return gdb, nil
}
