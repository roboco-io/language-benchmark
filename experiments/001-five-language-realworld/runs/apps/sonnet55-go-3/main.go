package main

import (
	"log"
	"os"
	"runtime"
	"strconv"

	"github.com/gin-gonic/gin"
)

func main() {
	// Use every available CPU core.
	runtime.GOMAXPROCS(runtime.NumCPU())

	port := os.Getenv("PORT")
	if port == "" {
		port = "8000"
	}
	dbPath := os.Getenv("DATABASE_PATH")
	if dbPath == "" {
		dbPath = "realworld.db"
	}
	secret := os.Getenv("JWT_SECRET")
	if secret == "" {
		secret = "realworld-dev-secret-change-me"
	}

	db, err := openDB(dbPath)
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	gin.SetMode(gin.ReleaseMode)
	r := newRouter(&server{db: db, secret: []byte(secret)})
	log.Printf("listening on :%s (GOMAXPROCS=%s)", port, strconv.Itoa(runtime.GOMAXPROCS(0)))
	if err := r.Run(":" + port); err != nil {
		log.Fatal(err)
	}
}
