package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

const minPasswordLen = 8

func (a *App) router() *gin.Engine {
	r := gin.New()
	r.Use(gin.Recovery())

	api := r.Group("/api")
	api.POST("/users", a.register)
	api.POST("/users/login", a.login)
	api.GET("/user", a.requireAuth, a.currentUser)
	api.PUT("/user", a.requireAuth, a.updateUser)

	api.GET("/profiles/:username", a.optionalAuth, a.getProfile)
	api.POST("/profiles/:username/follow", a.requireAuth, a.follow)
	api.DELETE("/profiles/:username/follow", a.requireAuth, a.unfollow)

	api.GET("/articles", a.optionalAuth, a.listArticles)
	api.GET("/articles/feed", a.requireAuth, a.feed)
	api.POST("/articles", a.requireAuth, a.createArticle)
	api.GET("/articles/:slug", a.optionalAuth, a.getArticle)
	api.PUT("/articles/:slug", a.requireAuth, a.updateArticle)
	api.DELETE("/articles/:slug", a.requireAuth, a.deleteArticle)
	api.POST("/articles/:slug/favorite", a.requireAuth, a.favorite)
	api.DELETE("/articles/:slug/favorite", a.requireAuth, a.unfavorite)

	api.GET("/articles/:slug/comments", a.optionalAuth, a.listComments)
	api.POST("/articles/:slug/comments", a.requireAuth, a.addComment)
	api.DELETE("/articles/:slug/comments/:id", a.requireAuth, a.deleteComment)

	api.GET("/tags", a.listTags)
	return r
}

func (a *App) userJSON(u *User, token string) gin.H {
	return gin.H{"user": gin.H{
		"email": u.Email, "token": token, "username": u.Username,
		"bio": u.Bio, "image": u.Image,
	}}
}

func isUniqueViolation(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}

// bindWrapped decodes {"<key>": {...}} into a map of raw fields.
func bindWrapped(c *gin.Context, key string) (map[string]json.RawMessage, bool) {
	var body map[string]json.RawMessage
	if err := c.ShouldBindJSON(&body); err != nil {
		fail(c, http.StatusUnprocessableEntity, "body", "is invalid")
		return nil, false
	}
	var fields map[string]json.RawMessage
	if raw, ok := body[key]; !ok || json.Unmarshal(raw, &fields) != nil || fields == nil {
		fail(c, http.StatusUnprocessableEntity, key, "can't be blank")
		return nil, false
	}
	return fields, true
}

// strField reports whether the field is present, whether it is a non-null
// string, and its (trimmed-for-blank-check) value.
func strField(f map[string]json.RawMessage, name string) (val string, present, isString bool) {
	raw, ok := f[name]
	if !ok {
		return "", false, false
	}
	var s *string
	if json.Unmarshal(raw, &s) != nil || s == nil {
		return "", true, false
	}
	return *s, true, true
}

func blank(s string) bool { return strings.TrimSpace(s) == "" }

func (a *App) register(c *gin.Context) {
	f, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	username, _, _ := strField(f, "username")
	email, _, _ := strField(f, "email")
	password, _, _ := strField(f, "password")
	username, email = strings.TrimSpace(username), strings.TrimSpace(email)

	errs := fieldErrors{}
	if blank(username) {
		errs.add("username", "can't be blank")
	}
	if blank(email) {
		errs.add("email", "can't be blank")
	}
	if password == "" {
		errs.add("password", "can't be blank")
	} else if len(password) < minPasswordLen {
		errs.add("password", "is too short (minimum is 8 characters)")
	}
	if len(errs) > 0 {
		failMany(c, http.StatusUnprocessableEntity, errs)
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	u := User{Username: username, Email: email, PasswordHash: string(hash)}

	err = a.db.Transaction(func(tx *gorm.DB) error {
		var n int64
		tx.Model(&User{}).Where("username = ?", username).Count(&n)
		if n > 0 {
			errs.add("username", "has already been taken")
		}
		tx.Model(&User{}).Where("email = ?", email).Count(&n)
		if n > 0 {
			errs.add("email", "has already been taken")
		}
		if len(errs) > 0 {
			return nil
		}
		return tx.Create(&u).Error
	})
	if len(errs) > 0 || isUniqueViolation(err) {
		if len(errs) == 0 {
			errs.add("username", "has already been taken")
		}
		failMany(c, http.StatusConflict, errs)
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	token, _ := a.issueToken(u.ID)
	c.JSON(http.StatusCreated, a.userJSON(&u, token))
}

func (a *App) login(c *gin.Context) {
	f, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	email, _, _ := strField(f, "email")
	password, _, _ := strField(f, "password")
	errs := fieldErrors{}
	if blank(email) {
		errs.add("email", "can't be blank")
	}
	if password == "" {
		errs.add("password", "can't be blank")
	}
	if len(errs) > 0 {
		failMany(c, http.StatusUnprocessableEntity, errs)
		return
	}
	var u User
	err := a.db.Where("email = ?", strings.TrimSpace(email)).Take(&u).Error
	if errors.Is(err, gorm.ErrRecordNotFound) ||
		(err == nil && bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)) != nil) {
		fail(c, http.StatusUnauthorized, "credentials", "invalid")
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	token, _ := a.issueToken(u.ID)
	c.JSON(http.StatusOK, a.userJSON(&u, token))
}

func (a *App) currentUser(c *gin.Context) {
	tok, _ := c.Get(ctxToken)
	c.JSON(http.StatusOK, a.userJSON(viewer(c), tok.(string)))
}

func (a *App) updateUser(c *gin.Context) {
	f, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	u := *viewer(c)
	errs := fieldErrors{}

	if v, present, isStr := strField(f, "username"); present {
		if !isStr || blank(v) {
			errs.add("username", "can't be blank")
		} else {
			u.Username = strings.TrimSpace(v)
		}
	}
	if v, present, isStr := strField(f, "email"); present {
		if !isStr || blank(v) {
			errs.add("email", "can't be blank")
		} else {
			u.Email = strings.TrimSpace(v)
		}
	}
	newPassword := ""
	if v, present, isStr := strField(f, "password"); present {
		switch {
		case !isStr || v == "":
			errs.add("password", "can't be blank")
		case len(v) < minPasswordLen:
			errs.add("password", "is too short (minimum is 8 characters)")
		default:
			newPassword = v
		}
	}
	// bio and image are nullable; an empty string is stored as null.
	for name, dst := range map[string]**string{"bio": &u.Bio, "image": &u.Image} {
		if v, present, isStr := strField(f, name); present {
			if !isStr || v == "" {
				*dst = nil
			} else {
				s := v
				*dst = &s
			}
		}
	}
	if len(errs) > 0 {
		failMany(c, http.StatusUnprocessableEntity, errs)
		return
	}
	if newPassword != "" {
		hash, err := bcrypt.GenerateFromPassword([]byte(newPassword), bcrypt.DefaultCost)
		if err != nil {
			fail(c, http.StatusInternalServerError, "server", "error")
			return
		}
		u.PasswordHash = string(hash)
	}

	conflict := fieldErrors{}
	err := a.db.Transaction(func(tx *gorm.DB) error {
		var n int64
		tx.Model(&User{}).Where("username = ? AND id <> ?", u.Username, u.ID).Count(&n)
		if n > 0 {
			conflict.add("username", "has already been taken")
		}
		tx.Model(&User{}).Where("email = ? AND id <> ?", u.Email, u.ID).Count(&n)
		if n > 0 {
			conflict.add("email", "has already been taken")
		}
		if len(conflict) > 0 {
			return nil
		}
		// Save writes nil pointers as NULL as well.
		return tx.Save(&u).Error
	})
	if len(conflict) > 0 || isUniqueViolation(err) {
		if len(conflict) == 0 {
			conflict.add("username", "has already been taken")
		}
		failMany(c, http.StatusConflict, conflict)
		return
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	// Issue a fresh token so it reflects the updated account.
	token, _ := a.issueToken(u.ID)
	c.JSON(http.StatusOK, a.userJSON(&u, token))
}

// ---- profiles ----

func (a *App) profileJSON(u *User, following bool) gin.H {
	return gin.H{"profile": gin.H{
		"username": u.Username, "bio": u.Bio, "image": u.Image, "following": following,
	}}
}

func (a *App) findUserByName(c *gin.Context) (*User, bool) {
	var u User
	res := a.db.Where("username = ?", c.Param("username")).Limit(1).Find(&u)
	if res.Error != nil || res.RowsAffected == 0 {
		fail(c, http.StatusNotFound, "profile", "not found")
		return nil, false
	}
	return &u, true
}

func (a *App) isFollowing(follower, followee uint) bool {
	if follower == 0 {
		return false
	}
	var n int64
	a.db.Model(&Follow{}).Where("follower_id = ? AND followee_id = ?", follower, followee).Count(&n)
	return n > 0
}

func (a *App) getProfile(c *gin.Context) {
	u, ok := a.findUserByName(c)
	if !ok {
		return
	}
	c.JSON(http.StatusOK, a.profileJSON(u, a.isFollowing(viewerID(c), u.ID)))
}

func (a *App) follow(c *gin.Context) {
	u, ok := a.findUserByName(c)
	if !ok {
		return
	}
	me := viewerID(c)
	if err := a.db.Exec("INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)", me, u.ID).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, a.profileJSON(u, true))
}

func (a *App) unfollow(c *gin.Context) {
	u, ok := a.findUserByName(c)
	if !ok {
		return
	}
	if err := a.db.Where("follower_id = ? AND followee_id = ?", viewerID(c), u.ID).Delete(&Follow{}).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, a.profileJSON(u, false))
}
