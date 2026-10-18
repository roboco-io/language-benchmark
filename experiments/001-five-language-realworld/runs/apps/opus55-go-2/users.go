package main

import (
	"errors"
	"strings"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

const minPasswordLen = 8

type userResponse struct {
	Username string  `json:"username"`
	Email    string  `json:"email"`
	Bio      *string `json:"bio"`
	Image    *string `json:"image"`
	Token    string  `json:"token"`
}

type profileResponse struct {
	Username  string  `json:"username"`
	Bio       *string `json:"bio"`
	Image     *string `json:"image"`
	Following bool    `json:"following"`
}

func (s *Server) respondUser(c *gin.Context, status int, u *User) {
	token, err := signToken(u.ID)
	if err != nil {
		abortErr(c, 500, "token", "could not be generated")
		return
	}
	c.JSON(status, gin.H{"user": userResponse{
		Username: u.Username, Email: u.Email, Bio: u.Bio, Image: u.Image, Token: token,
	}})
}

func toProfile(u *User, following bool) profileResponse {
	return profileResponse{Username: u.Username, Bio: u.Bio, Image: u.Image, Following: following}
}

// nullableStr converts "" to nil so empty optional fields are stored as NULL.
func nullableStr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// checkTaken returns a 409 field error if the username or email is used by
// another user.
func (s *Server) checkTaken(c *gin.Context, username, email string, exceptID uint) bool {
	var n int64
	if username != "" {
		s.db.Model(&User{}).Where("username = ? AND id <> ?", username, exceptID).Count(&n)
		if n > 0 {
			abortErr(c, 409, "username", "has already been taken")
			return true
		}
	}
	if email != "" {
		s.db.Model(&User{}).Where("email = ? AND id <> ?", email, exceptID).Count(&n)
		if n > 0 {
			abortErr(c, 409, "email", "has already been taken")
			return true
		}
	}
	return false
}

func (s *Server) register(c *gin.Context) {
	in, ok := bindEnvelope(c, "user")
	if !ok {
		return
	}
	username := strings.TrimSpace(in.str("username"))
	email := strings.TrimSpace(in.str("email"))
	password := in.str("password")
	switch {
	case username == "":
		abortErr(c, 422, "username", "can't be blank")
		return
	case email == "":
		abortErr(c, 422, "email", "can't be blank")
		return
	case password == "":
		abortErr(c, 422, "password", "can't be blank")
		return
	case len(password) < minPasswordLen:
		abortErr(c, 422, "password", "is too short (minimum is 8 characters)")
		return
	}
	if s.checkTaken(c, username, email, 0) {
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		abortErr(c, 500, "password", "could not be hashed")
		return
	}
	u := User{Username: username, Email: email, Password: string(hash)}
	if err := s.db.Create(&u).Error; err != nil {
		// A concurrent insert can still violate the unique indexes.
		abortErr(c, 409, "username", "has already been taken")
		return
	}
	s.respondUser(c, 201, &u)
}

func (s *Server) login(c *gin.Context) {
	in, ok := bindEnvelope(c, "user")
	if !ok {
		return
	}
	email := strings.TrimSpace(in.str("email"))
	password := in.str("password")
	if email == "" {
		abortErr(c, 422, "email", "can't be blank")
		return
	}
	if password == "" {
		abortErr(c, 422, "password", "can't be blank")
		return
	}
	var u User
	if err := s.db.Where("email = ?", email).First(&u).Error; err != nil ||
		bcrypt.CompareHashAndPassword([]byte(u.Password), []byte(password)) != nil {
		abortErr(c, 401, "credentials", "invalid")
		return
	}
	s.respondUser(c, 200, &u)
}

func (s *Server) getCurrentUser(c *gin.Context) {
	s.respondUser(c, 200, currentUser(c))
}

func (s *Server) updateUser(c *gin.Context) {
	in, ok := bindEnvelope(c, "user")
	if !ok {
		return
	}
	u := *currentUser(c)

	for _, key := range []string{"username", "email"} {
		if in.has(key) && strings.TrimSpace(in.str(key)) == "" {
			abortErr(c, 422, key, "can't be blank")
			return
		}
	}
	if in.has("username") {
		u.Username = strings.TrimSpace(in.str("username"))
	}
	if in.has("email") {
		u.Email = strings.TrimSpace(in.str("email"))
	}
	if in.has("password") {
		password := in.str("password")
		if password == "" {
			abortErr(c, 422, "password", "can't be blank")
			return
		}
		if len(password) < minPasswordLen {
			abortErr(c, 422, "password", "is too short (minimum is 8 characters)")
			return
		}
		hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
		if err != nil {
			abortErr(c, 500, "password", "could not be hashed")
			return
		}
		u.Password = string(hash)
	}
	if in.has("bio") {
		u.Bio = nullableStr(in.str("bio"))
	}
	if in.has("image") {
		u.Image = nullableStr(in.str("image"))
	}
	if s.checkTaken(c, u.Username, u.Email, u.ID) {
		return
	}
	err := s.db.Model(&User{ID: u.ID}).Select("Username", "Email", "Password", "Bio", "Image", "UpdatedAt").Updates(&u).Error
	if err != nil {
		abortErr(c, 409, "username", "has already been taken")
		return
	}
	s.respondUser(c, 200, &u)
}

func (s *Server) isFollowing(followerID, followeeID uint) bool {
	var n int64
	s.db.Model(&Follow{}).Where("follower_id = ? AND followee_id = ?", followerID, followeeID).Count(&n)
	return n > 0
}

func (s *Server) findProfileUser(c *gin.Context) (*User, bool) {
	var u User
	err := s.db.Where("username = ?", c.Param("username")).First(&u).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		abortErr(c, 404, "profile", "not found")
		return nil, false
	} else if err != nil {
		abortErr(c, 500, "profile", "could not be loaded")
		return nil, false
	}
	return &u, true
}

func (s *Server) getProfile(c *gin.Context) {
	target, ok := s.findProfileUser(c)
	if !ok {
		return
	}
	following := false
	if me := currentUser(c); me != nil {
		following = s.isFollowing(me.ID, target.ID)
	}
	c.JSON(200, gin.H{"profile": toProfile(target, following)})
}

func (s *Server) follow(c *gin.Context) {
	target, ok := s.findProfileUser(c)
	if !ok {
		return
	}
	me := currentUser(c)
	if me.ID != target.ID {
		s.db.Exec("INSERT OR IGNORE INTO follows (follower_id, followee_id) VALUES (?, ?)", me.ID, target.ID)
	}
	c.JSON(200, gin.H{"profile": toProfile(target, s.isFollowing(me.ID, target.ID))})
}

func (s *Server) unfollow(c *gin.Context) {
	target, ok := s.findProfileUser(c)
	if !ok {
		return
	}
	me := currentUser(c)
	s.db.Where("follower_id = ? AND followee_id = ?", me.ID, target.ID).Delete(&Follow{})
	c.JSON(200, gin.H{"profile": toProfile(target, false)})
}
