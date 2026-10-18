// Command realworld serves the RealWorld ("Conduit") backend API.
package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"runtime"
	"time"

	"github.com/gin-gonic/gin"
)

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	// Use one P per available CPU core.
	cores := runtime.NumCPU()
	runtime.GOMAXPROCS(cores)

	db, err := openDB(env("DB_PATH", "realworld.db"), cores)
	if err != nil {
		log.Fatalf("database: %v", err)
	}

	gin.SetMode(gin.ReleaseMode)
	app := &App{db: db, jwtSecret: []byte(env("JWT_SECRET", "realworld-dev-secret-change-me"))}

	srv := &http.Server{
		Addr:              ":" + env("PORT", "8000"),
		Handler:           app.router(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("listening on %s (GOMAXPROCS=%d)", srv.Addr, runtime.GOMAXPROCS(0))
	if err := srv.ListenAndServe(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
