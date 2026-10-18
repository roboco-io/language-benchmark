package main

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"gorm.io/gorm"
)

type App struct {
	db        *gorm.DB
	jwtSecret []byte
}

// fieldErrors is the RealWorld error body: {"errors": {"field": ["message"]}}.
type fieldErrors map[string][]string

func (e fieldErrors) add(field, msg string) { e[field] = append(e[field], msg) }

func fail(c *gin.Context, status int, field, msg string) {
	c.AbortWithStatusJSON(status, gin.H{"errors": fieldErrors{field: {msg}}})
}

func failMany(c *gin.Context, status int, errs fieldErrors) {
	c.AbortWithStatusJSON(status, gin.H{"errors": errs})
}

const timeLayout = "2006-01-02T15:04:05.000Z"

func fmtTime(t time.Time) string { return t.UTC().Format(timeLayout) }

// ---- JWT ----

func (a *App) issueToken(userID uint) (string, error) {
	claims := jwt.MapClaims{
		"sub": userID,
		"iat": time.Now().Unix(),
		"exp": time.Now().Add(72 * time.Hour).Unix(),
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(a.jwtSecret)
}

var errNoToken = errors.New("missing")

func (a *App) parseToken(c *gin.Context) (uint, string, error) {
	h := c.GetHeader("Authorization")
	if h == "" {
		return 0, "", errNoToken
	}
	raw := h
	if i := strings.IndexByte(h, ' '); i >= 0 {
		raw = strings.TrimSpace(h[i+1:])
	}
	tok, err := jwt.Parse(raw, func(t *jwt.Token) (any, error) { return a.jwtSecret, nil },
		jwt.WithValidMethods([]string{"HS256"}))
	if err != nil || !tok.Valid {
		return 0, raw, errors.New("invalid")
	}
	claims, ok := tok.Claims.(jwt.MapClaims)
	if !ok {
		return 0, raw, errors.New("invalid")
	}
	id, ok := claims["sub"].(float64)
	if !ok {
		return 0, raw, errors.New("invalid")
	}
	return uint(id), raw, nil
}

const (
	ctxUser  = "user"
	ctxToken = "token"
)

// requireAuth rejects requests without a valid token.
func (a *App) requireAuth(c *gin.Context) {
	if !a.authenticate(c, true) {
		return
	}
	c.Next()
}

// optionalAuth loads the user when a token is present; anonymous otherwise.
func (a *App) optionalAuth(c *gin.Context) {
	if !a.authenticate(c, false) {
		return
	}
	c.Next()
}

func (a *App) authenticate(c *gin.Context, required bool) bool {
	id, raw, err := a.parseToken(c)
	if err != nil {
		if errors.Is(err, errNoToken) && !required {
			return true
		}
		msg := "is invalid"
		if errors.Is(err, errNoToken) {
			msg = "is missing"
		}
		fail(c, http.StatusUnauthorized, "token", msg)
		return false
	}
	var u User
	if res := a.db.Limit(1).Find(&u, id); res.Error != nil || res.RowsAffected == 0 {
		fail(c, http.StatusUnauthorized, "token", "is invalid")
		return false
	}
	c.Set(ctxUser, &u)
	c.Set(ctxToken, raw)
	return true
}

// viewer returns the authenticated user or nil.
func viewer(c *gin.Context) *User {
	if v, ok := c.Get(ctxUser); ok {
		return v.(*User)
	}
	return nil
}

func viewerID(c *gin.Context) uint {
	if u := viewer(c); u != nil {
		return u.ID
	}
	return 0
}
