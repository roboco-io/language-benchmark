package main

import (
	"time"

	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type User struct {
	ID           uint   `gorm:"primaryKey"`
	Username     string `gorm:"uniqueIndex;not null"`
	Email        string `gorm:"uniqueIndex;not null"`
	PasswordHash string `gorm:"not null"`
	Bio          *string
	Image        *string
}

type Follow struct {
	FollowerID uint `gorm:"primaryKey"`
	FolloweeID uint `gorm:"primaryKey;index"`
}

type Article struct {
	ID          uint   `gorm:"primaryKey"`
	Slug        string `gorm:"uniqueIndex;not null"`
	Title       string `gorm:"not null"`
	Description string `gorm:"not null"`
	Body        string `gorm:"not null"`
	AuthorID    uint   `gorm:"index;not null"`
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

type ArticleTag struct {
	ID        uint   `gorm:"primaryKey"`
	ArticleID uint   `gorm:"index;not null"`
	Name      string `gorm:"index;not null"`
}

type Favorite struct {
	UserID    uint `gorm:"primaryKey"`
	ArticleID uint `gorm:"primaryKey;index"`
}

type Comment struct {
	ID        uint   `gorm:"primaryKey"`
	ArticleID uint   `gorm:"index;not null"`
	AuthorID  uint   `gorm:"not null"`
	Body      string `gorm:"not null"`
	CreatedAt time.Time
	UpdatedAt time.Time
}

// openDB opens SQLite in WAL mode with a 5000 ms busy timeout and migrates the schema.
func openDB(path string) (*gorm.DB, error) {
	dsn := "file:" + path + "?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(1)"
	db, err := gorm.Open(sqlite.Open(dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	if err != nil {
		return nil, err
	}
	if sqlDB, err := db.DB(); err == nil {
		sqlDB.SetMaxOpenConns(8)
	}
	if err := db.AutoMigrate(&User{}, &Follow{}, &Article{}, &ArticleTag{}, &Favorite{}, &Comment{}); err != nil {
		return nil, err
	}
	return db, nil
}
