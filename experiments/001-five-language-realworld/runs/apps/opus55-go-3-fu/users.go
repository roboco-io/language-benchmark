package main

import (
	"errors"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

const minPasswordLength = 8

type userJSON struct {
	Username string  `json:"username"`
	Email    string  `json:"email"`
	Token    string  `json:"token"`
	Bio      *string `json:"bio"`
	Image    *string `json:"image"`
}

type profileJSON struct {
	Username  string  `json:"username"`
	Bio       *string `json:"bio"`
	Image     *string `json:"image"`
	Following bool    `json:"following"`
}

func (a *App) respondUser(c *gin.Context, status int, u *User) {
	token, err := a.issueToken(u)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(status, gin.H{"user": userJSON{
		Username: u.Username, Email: u.Email, Token: token, Bio: u.Bio, Image: u.Image,
	}})
}

// nullIfEmpty turns "" into nil so optional profile fields are stored as NULL.
func nullIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// checkTaken reports which unique fields already belong to another user.
func (a *App) checkTaken(username, email string, exceptID uint) (map[string][]string, error) {
	errs := map[string][]string{}
	var n int64
	if username != "" {
		if err := a.DB.Model(&User{}).Where("username = ? AND id <> ?", username, exceptID).Count(&n).Error; err != nil {
			return nil, err
		}
		if n > 0 {
			errs["username"] = []string{"has already been taken"}
		}
	}
	if email != "" {
		if err := a.DB.Model(&User{}).Where("email = ? AND id <> ?", email, exceptID).Count(&n).Error; err != nil {
			return nil, err
		}
		if n > 0 {
			errs["email"] = []string{"has already been taken"}
		}
	}
	return errs, nil
}

func (a *App) register(c *gin.Context) {
	props, ok := readEnvelope(c, "user")
	if !ok {
		return
	}
	errs := map[string][]string{}
	values := map[string]string{}
	for _, k := range []string{"username", "email", "password"} {
		s, valid := props[k].str()
		switch {
		case !valid:
			errs[k] = []string{"must be a string"}
		case strings.TrimSpace(s) == "":
			errs[k] = []string{"can't be blank"}
		}
		values[k] = s
	}
	if _, bad := errs["password"]; !bad && len(values["password"]) < minPasswordLength {
		errs["password"] = []string{"is too short (minimum is 8 characters)"}
	}
	if len(errs) > 0 {
		errorsJSON(c, http.StatusUnprocessableEntity, errs)
		return
	}
	username, email := strings.TrimSpace(values["username"]), strings.TrimSpace(values["email"])
	taken, err := a.checkTaken(username, email, 0)
	if err != nil {
		internalError(c, err)
		return
	}
	if len(taken) > 0 {
		errorsJSON(c, http.StatusConflict, taken)
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(values["password"]), bcrypt.DefaultCost)
	if err != nil {
		internalError(c, err)
		return
	}
	u := User{Username: username, Email: email, PasswordHash: string(hash)}
	if err := a.DB.Create(&u).Error; err != nil {
		// A concurrent request may have taken the name between check and insert.
		errorJSON(c, http.StatusConflict, "username", "has already been taken")
		return
	}
	a.respondUser(c, http.StatusCreated, &u)
}

func (a *App) login(c *gin.Context) {
	props, ok := readEnvelope(c, "user")
	if !ok {
		return
	}
	email, _ := props["email"].str()
	password, _ := props["password"].str()
	errs := map[string][]string{}
	if strings.TrimSpace(email) == "" {
		errs["email"] = []string{"can't be blank"}
	}
	if password == "" {
		errs["password"] = []string{"can't be blank"}
	}
	if len(errs) > 0 {
		errorsJSON(c, http.StatusUnprocessableEntity, errs)
		return
	}
	var u User
	err := a.DB.Where("email = ?", strings.TrimSpace(email)).First(&u).Error
	if err == nil {
		err = bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password))
	}
	if err != nil {
		errorJSON(c, http.StatusUnauthorized, "credentials", "invalid")
		return
	}
	a.respondUser(c, http.StatusOK, &u)
}

func (a *App) currentUser(c *gin.Context) {
	a.respondUser(c, http.StatusOK, current(c))
}

func (a *App) updateUser(c *gin.Context) {
	props, ok := readEnvelope(c, "user")
	if !ok {
		return
	}
	u := *current(c)
	errs := map[string][]string{}

	// Required fields: when present they must be non-empty strings.
	for _, k := range []string{"username", "email", "password"} {
		f := props[k]
		if !f.Present {
			continue
		}
		s, valid := f.str()
		switch {
		case !valid:
			errs[k] = []string{"must be a string"}
		case f.Null || strings.TrimSpace(s) == "":
			errs[k] = []string{"can't be blank"}
		case k == "password" && len(s) < minPasswordLength:
			errs[k] = []string{"is too short (minimum is 8 characters)"}
		case k == "username":
			u.Username = strings.TrimSpace(s)
		case k == "email":
			u.Email = strings.TrimSpace(s)
		case k == "password":
			hash, err := bcrypt.GenerateFromPassword([]byte(s), bcrypt.DefaultCost)
			if err != nil {
				internalError(c, err)
				return
			}
			u.PasswordHash = string(hash)
		}
	}
	// Nullable fields: "" and null both clear the value.
	for _, k := range []string{"bio", "image"} {
		f := props[k]
		if !f.Present {
			continue
		}
		s, valid := f.str()
		if !valid {
			errs[k] = []string{"must be a string"}
			continue
		}
		if k == "bio" {
			u.Bio = nullIfEmpty(s)
		} else {
			u.Image = nullIfEmpty(s)
		}
	}
	if len(errs) > 0 {
		errorsJSON(c, http.StatusUnprocessableEntity, errs)
		return
	}
	taken, err := a.checkTaken(u.Username, u.Email, u.ID)
	if err != nil {
		internalError(c, err)
		return
	}
	if len(taken) > 0 {
		errorsJSON(c, http.StatusConflict, taken)
		return
	}
	if err := a.DB.Select("Username", "Email", "PasswordHash", "Bio", "Image").Save(&u).Error; err != nil {
		internalError(c, err)
		return
	}
	a.respondUser(c, http.StatusOK, &u)
}

// --- profiles ---

func (a *App) isFollowing(viewer *User, targetID uint) (bool, error) {
	if viewer == nil {
		return false, nil
	}
	var n int64
	err := a.DB.Model(&Follow{}).Where("follower_id = ? AND followee_id = ?", viewer.ID, targetID).Count(&n).Error
	return n > 0, err
}

func (a *App) findProfileUser(c *gin.Context) (*User, bool) {
	var u User
	err := a.DB.Where("username = ?", c.Param("username")).First(&u).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		errorJSON(c, http.StatusNotFound, "profile", "not found")
		return nil, false
	}
	if err != nil {
		internalError(c, err)
		return nil, false
	}
	return &u, true
}

func (a *App) respondProfile(c *gin.Context, u *User) {
	following, err := a.isFollowing(current(c), u.ID)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"profile": profileJSON{
		Username: u.Username, Bio: u.Bio, Image: u.Image, Following: following,
	}})
}

func (a *App) getProfile(c *gin.Context) {
	if u, ok := a.findProfileUser(c); ok {
		a.respondProfile(c, u)
	}
}

func (a *App) follow(c *gin.Context) {
	u, ok := a.findProfileUser(c)
	if !ok {
		return
	}
	f := Follow{FollowerID: current(c).ID, FolloweeID: u.ID}
	if err := a.DB.Where(&f).FirstOrCreate(&f).Error; err != nil {
		internalError(c, err)
		return
	}
	a.respondProfile(c, u)
}

func (a *App) unfollow(c *gin.Context) {
	u, ok := a.findProfileUser(c)
	if !ok {
		return
	}
	if err := a.DB.Where("follower_id = ? AND followee_id = ?", current(c).ID, u.ID).Delete(&Follow{}).Error; err != nil {
		internalError(c, err)
		return
	}
	a.respondProfile(c, u)
}
