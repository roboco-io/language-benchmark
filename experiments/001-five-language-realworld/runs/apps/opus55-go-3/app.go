package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"gorm.io/gorm"
)

// App holds the shared dependencies of all HTTP handlers.
type App struct {
	DB     *gorm.DB
	Secret []byte
}

const ctxUserKey = "currentUser"

func (a *App) Router() *gin.Engine {
	r := gin.New()
	r.Use(gin.Recovery())

	api := r.Group("/api")
	auth := a.requireAuth
	opt := a.optionalAuth

	api.POST("/users", a.register)
	api.POST("/users/login", a.login)
	api.GET("/user", auth, a.currentUser)
	api.PUT("/user", auth, a.updateUser)

	api.GET("/profiles/:username", opt, a.getProfile)
	api.POST("/profiles/:username/follow", auth, a.follow)
	api.DELETE("/profiles/:username/follow", auth, a.unfollow)

	api.GET("/articles", opt, a.listArticles)
	api.GET("/articles/feed", auth, a.feed)
	api.POST("/articles", auth, a.createArticle)
	api.GET("/articles/:slug", opt, a.getArticle)
	api.PUT("/articles/:slug", auth, a.updateArticle)
	api.DELETE("/articles/:slug", auth, a.deleteArticle)
	api.POST("/articles/:slug/favorite", auth, a.favorite)
	api.DELETE("/articles/:slug/favorite", auth, a.unfavorite)

	api.GET("/articles/:slug/comments", opt, a.listComments)
	api.POST("/articles/:slug/comments", auth, a.createComment)
	api.DELETE("/articles/:slug/comments/:id", auth, a.deleteComment)

	api.GET("/tags", a.listTags)

	r.NoRoute(func(c *gin.Context) { errorJSON(c, http.StatusNotFound, "path", "not found") })
	return r
}

// errorJSON writes the RealWorld error envelope: {"errors": {field: [msg]}}.
func errorJSON(c *gin.Context, status int, field, msg string) {
	c.AbortWithStatusJSON(status, gin.H{"errors": gin.H{field: []string{msg}}})
}

func errorsJSON(c *gin.Context, status int, errs map[string][]string) {
	c.AbortWithStatusJSON(status, gin.H{"errors": errs})
}

func internalError(c *gin.Context, err error) {
	_ = c.Error(err)
	errorJSON(c, http.StatusInternalServerError, "server", err.Error())
}

// --- JWT authentication ---

func (a *App) issueToken(u *User) (string, error) {
	now := time.Now()
	claims := jwt.MapClaims{
		"sub":      u.ID,
		"username": u.Username,
		"iat":      now.Unix(),
		"exp":      now.Add(30 * 24 * time.Hour).Unix(),
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(a.Secret)
}

// userFromRequest returns (nil, nil) when no token is sent and an error when
// the token is present but invalid.
func (a *App) userFromRequest(c *gin.Context) (*User, error) {
	header := c.GetHeader("Authorization")
	if header == "" {
		return nil, nil
	}
	token, ok := strings.CutPrefix(header, "Token ")
	if !ok {
		token, ok = strings.CutPrefix(header, "Bearer ")
	}
	if !ok || token == "" {
		return nil, errors.New("is invalid")
	}
	parsed, err := jwt.Parse(token, func(t *jwt.Token) (any, error) { return a.Secret, nil },
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
	if err != nil || !parsed.Valid {
		return nil, errors.New("is invalid")
	}
	claims, _ := parsed.Claims.(jwt.MapClaims)
	sub, ok := claims["sub"].(float64)
	if !ok {
		return nil, errors.New("is invalid")
	}
	var u User
	if err := a.DB.First(&u, uint(sub)).Error; err != nil {
		return nil, errors.New("is invalid")
	}
	return &u, nil
}

func (a *App) requireAuth(c *gin.Context) {
	u, err := a.userFromRequest(c)
	if err != nil {
		errorJSON(c, http.StatusUnauthorized, "token", err.Error())
		return
	}
	if u == nil {
		errorJSON(c, http.StatusUnauthorized, "token", "is missing")
		return
	}
	c.Set(ctxUserKey, u)
}

func (a *App) optionalAuth(c *gin.Context) {
	u, err := a.userFromRequest(c)
	if err != nil {
		errorJSON(c, http.StatusUnauthorized, "token", err.Error())
		return
	}
	if u != nil {
		c.Set(ctxUserKey, u)
	}
}

func current(c *gin.Context) *User {
	if v, ok := c.Get(ctxUserKey); ok {
		return v.(*User)
	}
	return nil
}

// --- request body helpers ---

// field is one JSON property of a request object. Present is false when the
// property is absent; Null is true when it is explicitly null.
type field struct {
	Present bool
	Null    bool
	Raw     json.RawMessage
}

// readEnvelope parses {"<key>": {...}} into a map of raw properties.
func readEnvelope(c *gin.Context, key string) (map[string]field, bool) {
	data, err := io.ReadAll(c.Request.Body)
	if err != nil {
		errorJSON(c, http.StatusUnprocessableEntity, "body", "is invalid")
		return nil, false
	}
	var outer map[string]json.RawMessage
	if err := json.Unmarshal(data, &outer); err != nil {
		errorJSON(c, http.StatusUnprocessableEntity, "body", "is invalid")
		return nil, false
	}
	inner, ok := outer[key]
	if !ok || bytes.Equal(bytes.TrimSpace(inner), []byte("null")) {
		errorJSON(c, http.StatusUnprocessableEntity, key, "can't be blank")
		return nil, false
	}
	var props map[string]json.RawMessage
	if err := json.Unmarshal(inner, &props); err != nil {
		errorJSON(c, http.StatusUnprocessableEntity, key, "is invalid")
		return nil, false
	}
	out := make(map[string]field, len(props))
	for k, v := range props {
		out[k] = field{Present: true, Null: bytes.Equal(bytes.TrimSpace(v), []byte("null")), Raw: v}
	}
	return out, true
}

// str decodes a string property. It returns ok=false if the value is not a string.
func (f field) str() (string, bool) {
	if !f.Present || f.Null {
		return "", true
	}
	var s string
	if err := json.Unmarshal(f.Raw, &s); err != nil {
		return "", false
	}
	return s, true
}

// formatTime renders timestamps as ISO 8601 in UTC with millisecond precision.
func formatTime(t time.Time) string {
	return t.UTC().Format("2006-01-02T15:04:05.000Z")
}
