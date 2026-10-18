package api

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"

	"realworld/internal/db"
)

const (
	ctxUserKey = "currentUser"
	tokenTTL   = 30 * 24 * time.Hour
)

type claims struct {
	UserID uint `json:"uid"`
	jwt.RegisteredClaims
}

func (s *Server) signToken(userID uint) (string, error) {
	now := time.Now()
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, claims{
		UserID: userID,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(tokenTTL)),
		},
	})
	return t.SignedString(s.secret)
}

func (s *Server) parseToken(raw string) (uint, error) {
	var cl claims
	_, err := jwt.ParseWithClaims(raw, &cl, func(*jwt.Token) (any, error) { return s.secret, nil },
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
	if err != nil {
		return 0, err
	}
	if cl.UserID == 0 {
		return 0, errors.New("missing user id")
	}
	return cl.UserID, nil
}

// extractToken reads "Authorization: Token <jwt>" (also accepting "Bearer").
func extractToken(c *gin.Context) string {
	h := c.GetHeader("Authorization")
	if h == "" {
		return ""
	}
	for _, prefix := range []string{"Token ", "Bearer "} {
		if strings.HasPrefix(h, prefix) {
			return strings.TrimSpace(h[len(prefix):])
		}
	}
	return ""
}

// authenticate resolves the current user. When required is false, a missing
// token is allowed, but a present invalid token is still rejected.
func (s *Server) authenticate(required bool) gin.HandlerFunc {
	return func(c *gin.Context) {
		raw := extractToken(c)
		if raw == "" {
			if c.GetHeader("Authorization") != "" {
				abortError(c, http.StatusUnauthorized, "token", "is invalid")
				return
			}
			if required {
				abortError(c, http.StatusUnauthorized, "token", "is missing")
				return
			}
			c.Next()
			return
		}
		uid, err := s.parseToken(raw)
		if err != nil {
			abortError(c, http.StatusUnauthorized, "token", "is invalid")
			return
		}
		var u db.User
		if err := s.db.Take(&u, uid).Error; err != nil {
			abortError(c, http.StatusUnauthorized, "token", "is invalid")
			return
		}
		c.Set(ctxUserKey, &u)
		c.Next()
	}
}

func currentUser(c *gin.Context) *db.User {
	if v, ok := c.Get(ctxUserKey); ok {
		return v.(*db.User)
	}
	return nil
}
