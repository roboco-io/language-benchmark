// Command server runs the RealWorld (Conduit) backend API.
package main

import (
	"log"
	"os"
	"runtime"

	"github.com/gin-gonic/gin"
)

func main() {
	// Use one OS thread per available CPU core.
	procs := runtime.NumCPU()
	runtime.GOMAXPROCS(procs)

	port := getenv("PORT", "8000")
	dbPath := getenv("DATABASE_PATH", "data/realworld.db")
	secret := os.Getenv("JWT_SECRET")
	if secret == "" {
		secret = "realworld-dev-secret-change-me"
		log.Println("warning: JWT_SECRET is not set; using an insecure default secret")
	}

	db, err := openDB(dbPath)
	if err != nil {
		log.Fatalf("open database: %v", err)
	}

	if os.Getenv("GIN_MODE") == "" {
		gin.SetMode(gin.ReleaseMode)
	}
	app := &App{DB: db, Secret: []byte(secret)}
	router := app.Router()

	log.Printf("listening on :%s (GOMAXPROCS=%d, database=%s)", port, runtime.GOMAXPROCS(0), dbPath)
	if err := router.Run(":" + port); err != nil {
		log.Fatalf("server stopped: %v", err)
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
