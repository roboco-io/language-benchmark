package main

import (
	"encoding/json"
	"time"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

type Server struct {
	db *gorm.DB
}

func newServer(db *gorm.DB) *Server {
	return &Server{db: db}
}

func (s *Server) routes(r *gin.Engine) {
	opt := s.authenticate(false)
	req := s.authenticate(true)

	api := r.Group("/api")
	api.POST("/users", s.register)
	api.POST("/users/login", s.login)
	api.GET("/user", req, s.getCurrentUser)
	api.PUT("/user", req, s.updateUser)

	api.GET("/profiles/:username", opt, s.getProfile)
	api.POST("/profiles/:username/follow", req, s.follow)
	api.DELETE("/profiles/:username/follow", req, s.unfollow)

	api.GET("/articles", opt, s.listArticles)
	api.GET("/articles/feed", req, s.feed)
	api.POST("/articles", req, s.createArticle)
	api.GET("/articles/:slug", opt, s.getArticle)
	api.PUT("/articles/:slug", req, s.updateArticle)
	api.DELETE("/articles/:slug", req, s.deleteArticle)
	api.POST("/articles/:slug/favorite", req, s.favorite)
	api.DELETE("/articles/:slug/favorite", req, s.unfavorite)

	api.GET("/articles/:slug/comments", opt, s.listComments)
	api.POST("/articles/:slug/comments", req, s.createComment)
	api.DELETE("/articles/:slug/comments/:id", req, s.deleteComment)

	api.GET("/tags", s.listTags)

	r.NoRoute(func(c *gin.Context) {
		c.JSON(404, gin.H{"errors": gin.H{"route": []string{"not found"}}})
	})
}

// errBody builds the RealWorld error envelope: {"errors": {field: [msg]}}.
func errBody(field, msg string) gin.H {
	return gin.H{"errors": gin.H{field: []string{msg}}}
}

func abortErr(c *gin.Context, status int, field, msg string) {
	c.AbortWithStatusJSON(status, errBody(field, msg))
}

// fields is a JSON object whose values are kept raw, so handlers can tell
// an absent key apart from an explicit null.
type fields map[string]json.RawMessage

// bindEnvelope decodes {"<key>": {...}} and returns the inner object.
func bindEnvelope(c *gin.Context, key string) (fields, bool) {
	var outer map[string]json.RawMessage
	if err := json.NewDecoder(c.Request.Body).Decode(&outer); err != nil {
		abortErr(c, 422, "body", "is invalid")
		return nil, false
	}
	var inner fields
	raw, ok := outer[key]
	if !ok || json.Unmarshal(raw, &inner) != nil || inner == nil {
		abortErr(c, 422, key, "is missing")
		return nil, false
	}
	return inner, true
}

// isNull reports whether the key is present with an explicit null value.
func (f fields) isNull(key string) bool {
	raw, ok := f[key]
	return ok && string(raw) == "null"
}

func (f fields) has(key string) bool {
	_, ok := f[key]
	return ok
}

// str returns the string value of key ("" if absent, null, or not a string).
func (f fields) str(key string) string {
	var s string
	if raw, ok := f[key]; ok {
		_ = json.Unmarshal(raw, &s)
	}
	return s
}

const timeLayout = "2006-01-02T15:04:05.000Z"

func fmtTime(t time.Time) string {
	return t.UTC().Format(timeLayout)
}
