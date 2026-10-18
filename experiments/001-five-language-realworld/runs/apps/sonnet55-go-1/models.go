package main

import "time"

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
	ID          uint   `gorm:"primaryKey"`
	Slug        string `gorm:"uniqueIndex;not null"`
	Title       string `gorm:"not null"`
	Description string `gorm:"not null"`
	Body        string `gorm:"not null"`
	AuthorID    uint   `gorm:"index;not null"`
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

type Tag struct {
	ID   uint   `gorm:"primaryKey"`
	Name string `gorm:"uniqueIndex;not null"`
}

// ArticleTag links an article to a tag. Its auto-increment ID preserves the
// order in which tags were attached to the article.
type ArticleTag struct {
	ID        uint `gorm:"primaryKey"`
	ArticleID uint `gorm:"index;not null"`
	TagID     uint `gorm:"index;not null"`
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
