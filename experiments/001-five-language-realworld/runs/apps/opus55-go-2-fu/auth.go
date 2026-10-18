package main

import (
	"errors"
	"os"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

var jwtSecret = func() []byte {
	if s := os.Getenv("JWT_SECRET"); s != "" {
		return []byte(s)
	}
	return []byte("realworld-dev-secret-change-me")
}()

const ctxUserKey = "currentUser"

func signToken(userID uint) (string, error) {
	claims := jwt.MapClaims{
		"sub": float64(userID),
		"iat": time.Now().Unix(),
		"exp": time.Now().Add(30 * 24 * time.Hour).Unix(),
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(jwtSecret)
}

func parseToken(tokenStr string) (uint, error) {
	tok, err := jwt.Parse(tokenStr, func(t *jwt.Token) (any, error) {
		return jwtSecret, nil
	}, jwt.WithValidMethods([]string{"HS256"}))
	if err != nil || !tok.Valid {
		return 0, errors.New("invalid token")
	}
	claims, ok := tok.Claims.(jwt.MapClaims)
	if !ok {
		return 0, errors.New("invalid claims")
	}
	sub, ok := claims["sub"].(float64)
	if !ok || sub <= 0 {
		return 0, errors.New("invalid subject")
	}
	return uint(sub), nil
}

// extractToken returns the token from an "Authorization: Token <jwt>" (or
// "Bearer <jwt>") header, and whether a header was present at all.
func extractToken(c *gin.Context) (string, bool) {
	h := strings.TrimSpace(c.GetHeader("Authorization"))
	if h == "" {
		return "", false
	}
	for _, prefix := range []string{"Token ", "Bearer "} {
		if strings.HasPrefix(h, prefix) {
			return strings.TrimSpace(h[len(prefix):]), true
		}
	}
	return "", true
}

// authenticate resolves the current user. With required=false, a missing
// header is allowed, but a present and invalid token is still rejected.
func (s *Server) authenticate(required bool) gin.HandlerFunc {
	return func(c *gin.Context) {
		tokenStr, present := extractToken(c)
		if !present {
			if required {
				abortErr(c, 401, "token", "is missing")
				return
			}
			c.Next()
			return
		}
		userID, err := parseToken(tokenStr)
		if err != nil {
			abortErr(c, 401, "token", "is invalid")
			return
		}
		var u User
		if err := s.db.First(&u, userID).Error; err != nil {
			abortErr(c, 401, "token", "is invalid")
			return
		}
		c.Set(ctxUserKey, &u)
		c.Next()
	}
}

func currentUser(c *gin.Context) *User {
	if v, ok := c.Get(ctxUserKey); ok {
		return v.(*User)
	}
	return nil
}
