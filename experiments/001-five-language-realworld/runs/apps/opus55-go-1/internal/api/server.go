// Package api implements the RealWorld (Conduit) HTTP API.
package api

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

type Server struct {
	db     *gorm.DB
	secret []byte
}

func NewServer(gdb *gorm.DB, jwtSecret []byte) *Server {
	return &Server{db: gdb, secret: jwtSecret}
}

// Router builds the Gin engine with all API routes.
func (s *Server) Router() *gin.Engine {
	r := gin.New()
	r.Use(gin.Recovery())
	r.HandleMethodNotAllowed = true

	optional := s.authenticate(false)
	required := s.authenticate(true)

	api := r.Group("/api")
	api.POST("/users", s.register)
	api.POST("/users/login", s.login)
	api.GET("/user", required, s.getCurrentUser)
	api.PUT("/user", required, s.updateUser)

	api.GET("/profiles/:username", optional, s.getProfile)
	api.POST("/profiles/:username/follow", required, s.followUser)
	api.DELETE("/profiles/:username/follow", required, s.unfollowUser)

	api.GET("/articles", optional, s.listArticles)
	api.GET("/articles/feed", required, s.feedArticles)
	api.POST("/articles", required, s.createArticle)
	api.GET("/articles/:slug", optional, s.getArticle)
	api.PUT("/articles/:slug", required, s.updateArticle)
	api.DELETE("/articles/:slug", required, s.deleteArticle)
	api.POST("/articles/:slug/favorite", required, s.favoriteArticle)
	api.DELETE("/articles/:slug/favorite", required, s.unfavoriteArticle)

	api.GET("/articles/:slug/comments", optional, s.listComments)
	api.POST("/articles/:slug/comments", required, s.addComment)
	api.DELETE("/articles/:slug/comments/:id", required, s.deleteComment)

	api.GET("/tags", s.listTags)

	r.GET("/health", func(c *gin.Context) { c.String(http.StatusOK, "ok") })
	r.NoRoute(func(c *gin.Context) { abortError(c, http.StatusNotFound, "route", "not found") })
	return r
}
