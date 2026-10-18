package main

import (
	"database/sql"
	"fmt"
	"log"
	"time"

	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	_ "modernc.org/sqlite" // pure-Go SQLite driver, registered as "sqlite"
)

type User struct {
	ID        uint   `gorm:"primaryKey"`
	Username  string `gorm:"uniqueIndex;not null"`
	Email     string `gorm:"uniqueIndex;not null"`
	Password  string `gorm:"not null"`
	Bio       *string
	Image     *string
	CreatedAt time.Time
	UpdatedAt time.Time
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

// openDB opens the SQLite database with the modernc driver, applies the
// required pragmas on every pooled connection, and migrates the schema.
func openDB(path string) (*gorm.DB, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)&_txlock=immediate", path)
	sqlDB, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db, err := gorm.Open(sqlite.Dialector{Conn: sqlDB}, &gorm.Config{
		Logger:                 logger.Default.LogMode(logger.Silent),
		SkipDefaultTransaction: true,
		NowFunc:                func() time.Time { return time.Now().UTC() },
	})
	if err != nil {
		return nil, err
	}
	if err := db.AutoMigrate(&User{}, &Follow{}, &Article{}, &Tag{}, &ArticleTag{}, &Favorite{}, &Comment{}); err != nil {
		return nil, err
	}

	var journalMode string
	var busyTimeout int
	db.Raw("PRAGMA journal_mode").Scan(&journalMode)
	db.Raw("PRAGMA busy_timeout").Scan(&busyTimeout)
	log.Printf("sqlite: path=%s journal_mode=%s busy_timeout=%dms", path, journalMode, busyTimeout)
	return db, nil
}
