package main

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

const timeFormat = "2006-01-02T15:04:05.000Z"

func fmtTime(t time.Time) string { return t.UTC().Format(timeFormat) }

func fail(c *gin.Context, status int, field, msg string) {
	c.AbortWithStatusJSON(status, gin.H{"errors": gin.H{field: []string{msg}}})
}

func failMulti(c *gin.Context, status int, errs map[string][]string) {
	c.AbortWithStatusJSON(status, gin.H{"errors": errs})
}

var nonSlug = regexp.MustCompile(`[^a-z0-9]+`)

func slugify(title string) string {
	s := strings.Trim(nonSlug.ReplaceAllString(strings.ToLower(title), "-"), "-")
	if s == "" {
		s = "article"
	}
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return s + "-" + hex.EncodeToString(b)
}

func (s *Server) makeToken(userID uint) (string, error) {
	claims := jwt.MapClaims{"sub": userID, "exp": time.Now().Add(30 * 24 * time.Hour).Unix(), "iat": time.Now().Unix()}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(s.jwtSecret)
}

func (s *Server) parseToken(raw string) (uint, bool) {
	tok, err := jwt.Parse(raw, func(t *jwt.Token) (any, error) { return s.jwtSecret, nil }, jwt.WithValidMethods([]string{"HS256"}))
	if err != nil || !tok.Valid {
		return 0, false
	}
	claims, ok := tok.Claims.(jwt.MapClaims)
	if !ok {
		return 0, false
	}
	sub, ok := claims["sub"].(float64)
	if !ok {
		return 0, false
	}
	return uint(sub), true
}

// authenticate reads the Authorization header. When required is false, a missing
// header is allowed and the viewer ID is 0; an invalid token is always rejected.
func (s *Server) authenticate(required bool) gin.HandlerFunc {
	return func(c *gin.Context) {
		h := c.GetHeader("Authorization")
		if h == "" {
			if required {
				fail(c, http.StatusUnauthorized, "token", "is missing")
			}
			return
		}
		parts := strings.SplitN(h, " ", 2)
		if len(parts) != 2 || (parts[0] != "Token" && parts[0] != "Bearer") {
			fail(c, http.StatusUnauthorized, "token", "is invalid")
			return
		}
		id, ok := s.parseToken(strings.TrimSpace(parts[1]))
		if !ok {
			fail(c, http.StatusUnauthorized, "token", "is invalid")
			return
		}
		var n int64
		s.db.Model(&User{}).Where("id = ?", id).Count(&n)
		if n == 0 {
			fail(c, http.StatusUnauthorized, "token", "is invalid")
			return
		}
		c.Set("uid", id)
	}
}

func viewerID(c *gin.Context) uint {
	if v, ok := c.Get("uid"); ok {
		return v.(uint)
	}
	return 0
}

func isUnique(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE")
}

func (s *Server) routes(r *gin.Engine) {
	api := r.Group("/api")
	opt := s.authenticate(false)
	req := s.authenticate(true)

	api.POST("/users", s.register)
	api.POST("/users/login", s.login)
	api.GET("/user", req, s.currentUser)
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
	api.POST("/articles/:slug/comments", req, s.addComment)
	api.DELETE("/articles/:slug/comments/:id", req, s.deleteComment)

	api.GET("/tags", s.listTags)
}
