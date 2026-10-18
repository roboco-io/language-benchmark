// Command realworld serves the RealWorld (Conduit) backend API.
package main

import (
	"log"
	"os"
	"runtime"

	"github.com/gin-gonic/gin"

	"realworld/internal/api"
	"realworld/internal/db"
)

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	// Use one OS thread per available CPU core.
	procs := runtime.NumCPU()
	runtime.GOMAXPROCS(procs)

	gin.SetMode(gin.ReleaseMode)

	port := getenv("PORT", "8080")
	dbPath := getenv("DATABASE_PATH", "realworld.db")
	secret := getenv("JWT_SECRET", "realworld-dev-secret-change-me")

	gdb, err := db.Open(dbPath)
	if err != nil {
		log.Fatalf("open database: %v", err)
	}

	srv := api.NewServer(gdb, []byte(secret))
	log.Printf("listening on :%s (GOMAXPROCS=%d, db=%s)", port, runtime.GOMAXPROCS(0), dbPath)
	if err := srv.Router().Run(":" + port); err != nil {
		log.Fatal(err)
	}
}
