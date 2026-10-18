package main

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

const minPasswordLen = 8

type profileJSON struct {
	Username  string  `json:"username"`
	Bio       *string `json:"bio"`
	Image     *string `json:"image"`
	Following bool    `json:"following"`
}

func (s *Server) userJSON(u *User) (gin.H, error) {
	tok, err := s.makeToken(u.ID)
	if err != nil {
		return nil, err
	}
	return gin.H{"user": gin.H{"email": u.Email, "token": tok, "username": u.Username, "bio": u.Bio, "image": u.Image}}, nil
}

func (s *Server) isFollowing(viewer, target uint) bool {
	if viewer == 0 {
		return false
	}
	var n int64
	s.db.Model(&Follow{}).Where("follower_id = ? AND followee_id = ?", viewer, target).Count(&n)
	return n > 0
}

func (s *Server) profileOf(u *User, viewer uint) profileJSON {
	return profileJSON{Username: u.Username, Bio: u.Bio, Image: u.Image, Following: s.isFollowing(viewer, u.ID)}
}

// bindWrapped decodes {"<key>": {...}} into a generic map.
func bindWrapped(c *gin.Context, key string) (map[string]json.RawMessage, bool) {
	var outer map[string]json.RawMessage
	if err := c.ShouldBindJSON(&outer); err != nil {
		fail(c, http.StatusUnprocessableEntity, "body", "is invalid")
		return nil, false
	}
	raw, ok := outer[key]
	if !ok {
		fail(c, http.StatusUnprocessableEntity, key, "can't be blank")
		return nil, false
	}
	var inner map[string]json.RawMessage
	if err := json.Unmarshal(raw, &inner); err != nil || inner == nil {
		fail(c, http.StatusUnprocessableEntity, key, "is invalid")
		return nil, false
	}
	return inner, true
}

// strField returns the string value of a field. present is false when the key
// is absent; isNull is true when the value is JSON null; ok is false when the
// value is neither a string nor null.
func strField(m map[string]json.RawMessage, key string) (val string, present, isNull, ok bool) {
	raw, present := m[key]
	if !present {
		return "", false, false, true
	}
	if string(raw) == "null" {
		return "", true, true, true
	}
	if err := json.Unmarshal(raw, &val); err != nil {
		return "", true, false, false
	}
	return val, true, false, true
}

func (s *Server) register(c *gin.Context) {
	in, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	username, _, _, _ := strField(in, "username")
	email, _, _, _ := strField(in, "email")
	password, _, _, _ := strField(in, "password")
	errs := map[string][]string{}
	if strings.TrimSpace(username) == "" {
		errs["username"] = []string{"can't be blank"}
	}
	if strings.TrimSpace(email) == "" {
		errs["email"] = []string{"can't be blank"}
	}
	if password == "" {
		errs["password"] = []string{"can't be blank"}
	} else if len(password) < minPasswordLen {
		errs["password"] = []string{"is too short (minimum is 8 characters)"}
	}
	if len(errs) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, errs)
		return
	}
	conflicts := map[string][]string{}
	var n int64
	s.db.Model(&User{}).Where("username = ?", username).Count(&n)
	if n > 0 {
		conflicts["username"] = []string{"has already been taken"}
	}
	s.db.Model(&User{}).Where("email = ?", email).Count(&n)
	if n > 0 {
		conflicts["email"] = []string{"has already been taken"}
	}
	if len(conflicts) > 0 {
		failMulti(c, http.StatusConflict, conflicts)
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	u := &User{Username: username, Email: email, PasswordHash: string(hash)}
	if err := s.db.Create(u).Error; err != nil {
		if isUnique(err) {
			fail(c, http.StatusConflict, "username", "has already been taken")
			return
		}
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	out, err := s.userJSON(u)
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusCreated, out)
}

func (s *Server) login(c *gin.Context) {
	in, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	email, _, _, _ := strField(in, "email")
	password, _, _, _ := strField(in, "password")
	errs := map[string][]string{}
	if email == "" {
		errs["email"] = []string{"can't be blank"}
	}
	if password == "" {
		errs["password"] = []string{"can't be blank"}
	}
	if len(errs) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, errs)
		return
	}
	var u User
	if err := s.db.Where("email = ?", email).First(&u).Error; err != nil ||
		bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)) != nil {
		fail(c, http.StatusUnauthorized, "credentials", "invalid")
		return
	}
	out, err := s.userJSON(&u)
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) currentUser(c *gin.Context) {
	var u User
	if err := s.db.First(&u, viewerID(c)).Error; err != nil {
		fail(c, http.StatusUnauthorized, "token", "is invalid")
		return
	}
	out, err := s.userJSON(&u)
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) updateUser(c *gin.Context) {
	in, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	var u User
	if err := s.db.First(&u, viewerID(c)).Error; err != nil {
		fail(c, http.StatusUnauthorized, "token", "is invalid")
		return
	}
	errs := map[string][]string{}
	updates := map[string]any{}

	for _, f := range []string{"username", "email", "password"} {
		v, present, isNull, valid := strField(in, f)
		if !present {
			continue
		}
		if isNull || !valid || strings.TrimSpace(v) == "" {
			errs[f] = []string{"can't be blank"}
			continue
		}
		switch f {
		case "password":
			if len(v) < minPasswordLen {
				errs[f] = []string{"is too short (minimum is 8 characters)"}
				continue
			}
			hash, err := bcrypt.GenerateFromPassword([]byte(v), bcrypt.DefaultCost)
			if err != nil {
				fail(c, http.StatusInternalServerError, "server", "error")
				return
			}
			updates["password_hash"] = string(hash)
		default:
			updates[f] = v
		}
	}
	for _, f := range []string{"bio", "image"} {
		v, present, isNull, valid := strField(in, f)
		if !present {
			continue
		}
		if !valid {
			errs[f] = []string{"is invalid"}
			continue
		}
		if isNull || v == "" {
			updates[f] = nil
		} else {
			updates[f] = v
		}
	}
	if len(errs) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, errs)
		return
	}

	conflicts := map[string][]string{}
	for _, f := range []string{"username", "email"} {
		if v, ok := updates[f]; ok {
			var n int64
			s.db.Model(&User{}).Where(f+" = ? AND id <> ?", v, u.ID).Count(&n)
			if n > 0 {
				conflicts[f] = []string{"has already been taken"}
			}
		}
	}
	if len(conflicts) > 0 {
		failMulti(c, http.StatusConflict, conflicts)
		return
	}
	if len(updates) > 0 {
		if err := s.db.Model(&u).Updates(updates).Error; err != nil {
			if isUnique(err) {
				fail(c, http.StatusConflict, "username", "has already been taken")
				return
			}
			fail(c, http.StatusInternalServerError, "server", "error")
			return
		}
	}
	if err := s.db.First(&u, u.ID).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	out, err := s.userJSON(&u)
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) findUser(c *gin.Context, name string) (*User, bool) {
	var u User
	if err := s.db.Where("username = ?", name).First(&u).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			fail(c, http.StatusNotFound, "profile", "not found")
		} else {
			fail(c, http.StatusInternalServerError, "server", "error")
		}
		return nil, false
	}
	return &u, true
}

func (s *Server) getProfile(c *gin.Context) {
	u, ok := s.findUser(c, c.Param("username"))
	if !ok {
		return
	}
	c.JSON(http.StatusOK, gin.H{"profile": s.profileOf(u, viewerID(c))})
}

func (s *Server) follow(c *gin.Context) {
	u, ok := s.findUser(c, c.Param("username"))
	if !ok {
		return
	}
	me := viewerID(c)
	if me != u.ID {
		f := Follow{FollowerID: me, FolloweeID: u.ID}
		if err := s.db.Where(f).FirstOrCreate(&f).Error; err != nil && !isUnique(err) {
			fail(c, http.StatusInternalServerError, "server", "error")
			return
		}
	}
	c.JSON(http.StatusOK, gin.H{"profile": s.profileOf(u, me)})
}

func (s *Server) unfollow(c *gin.Context) {
	u, ok := s.findUser(c, c.Param("username"))
	if !ok {
		return
	}
	me := viewerID(c)
	s.db.Where("follower_id = ? AND followee_id = ?", me, u.ID).Delete(&Follow{})
	c.JSON(http.StatusOK, gin.H{"profile": s.profileOf(u, me)})
}
