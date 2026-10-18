package main

import (
	"log"
	"os"
	"runtime"

	"github.com/gin-gonic/gin"
)

func main() {
	// Use one OS thread per available CPU core.
	runtime.GOMAXPROCS(runtime.NumCPU())

	port := os.Getenv("PORT")
	if port == "" {
		port = "8000"
	}
	dbPath := os.Getenv("DATABASE_PATH")
	if dbPath == "" {
		dbPath = "realworld.db"
	}

	db, err := openDB(dbPath)
	if err != nil {
		log.Fatalf("database: %v", err)
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())
	newServer(db).routes(r)

	log.Printf("listening on :%s (GOMAXPROCS=%d, NumCPU=%d)", port, runtime.GOMAXPROCS(0), runtime.NumCPU())
	if err := r.Run(":" + port); err != nil {
		log.Fatal(err)
	}
}
