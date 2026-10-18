package main

import (
	"log"
	"os"
	"runtime"

	"github.com/gin-gonic/gin"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type Server struct {
	db        *gorm.DB
	jwtSecret []byte
}

func openDB(path string) (*gorm.DB, error) {
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(ON)"
	db, err := gorm.Open(sqlite.Open(dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	if err != nil {
		return nil, err
	}
	if err := db.AutoMigrate(&User{}, &Follow{}, &Article{}, &Tag{}, &ArticleTag{}, &Favorite{}, &Comment{}); err != nil {
		return nil, err
	}
	return db, nil
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	// Use one P per available CPU core.
	runtime.GOMAXPROCS(runtime.NumCPU())

	db, err := openDB(getenv("DB_PATH", "realworld.db"))
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	s := &Server{db: db, jwtSecret: []byte(getenv("JWT_SECRET", "realworld-dev-secret-change-me"))}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	s.routes(r)

	addr := ":" + getenv("PORT", "8000")
	log.Printf("listening on %s (GOMAXPROCS=%d)", addr, runtime.GOMAXPROCS(0))
	if err := r.Run(addr); err != nil {
		log.Fatal(err)
	}
}
