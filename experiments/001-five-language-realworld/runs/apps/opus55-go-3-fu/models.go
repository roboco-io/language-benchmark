package main

import "time"

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

type Tag struct {
	ID   uint   `gorm:"primaryKey"`
	Name string `gorm:"uniqueIndex;not null"`
}

// ArticleTag links articles to tags. Position keeps the tag order given by the author.
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
