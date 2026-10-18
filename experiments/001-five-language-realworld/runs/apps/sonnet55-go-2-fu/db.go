package main

import (
	"fmt"
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

// ArticleTag links an article to a tag. Position keeps the order of tagList.
type ArticleTag struct {
	ArticleID uint `gorm:"primaryKey"`
	TagID     uint `gorm:"primaryKey;index"`
	Position  int
}

type Favorite struct {
	UserID    uint `gorm:"primaryKey"`
	ArticleID uint `gorm:"primaryKey;index"`
}

type Follow struct {
	FollowerID uint `gorm:"primaryKey"`
	FolloweeID uint `gorm:"primaryKey;index"`
}

type Comment struct {
	ID        uint   `gorm:"primaryKey"`
	ArticleID uint   `gorm:"index;not null"`
	AuthorID  uint   `gorm:"not null"`
	Body      string `gorm:"not null"`
	CreatedAt time.Time
	UpdatedAt time.Time
}

// openDB opens the SQLite database in WAL mode with a 5000 ms busy timeout
// and creates the schema when it does not exist yet.
func openDB(path string, cores int) (*gorm.DB, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)"+
		"&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(1)&_txlock=immediate", path)
	db, err := gorm.Open(sqlite.Open(dsn), &gorm.Config{
		Logger:  logger.Default.LogMode(logger.Silent),
		NowFunc: func() time.Time { return time.Now().UTC() },
	})
	if err != nil {
		return nil, err
	}
	sqlDB, err := db.DB()
	if err != nil {
		return nil, err
	}
	sqlDB.SetMaxOpenConns(cores * 2)
	sqlDB.SetMaxIdleConns(cores * 2)

	if err := db.AutoMigrate(&User{}, &Article{}, &Tag{}, &ArticleTag{}, &Favorite{}, &Follow{}, &Comment{}); err != nil {
		return nil, err
	}
	return db, nil
}
