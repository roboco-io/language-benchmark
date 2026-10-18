package api

import (
	"errors"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"realworld/internal/db"
)

const minPasswordLen = 8

type userResponse struct {
	Email    string  `json:"email"`
	Token    string  `json:"token"`
	Username string  `json:"username"`
	Bio      *string `json:"bio"`
	Image    *string `json:"image"`
}

type profileResponse struct {
	Username  string  `json:"username"`
	Bio       *string `json:"bio"`
	Image     *string `json:"image"`
	Following bool    `json:"following"`
}

func toProfile(u *db.User, following bool) profileResponse {
	return profileResponse{Username: u.Username, Bio: nullable(u.Bio), Image: nullable(u.Image), Following: following}
}

func (s *Server) writeUser(c *gin.Context, status int, u *db.User) {
	token, err := s.signToken(u.ID)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(status, gin.H{"user": userResponse{
		Email: u.Email, Token: token, Username: u.Username, Bio: nullable(u.Bio), Image: nullable(u.Image),
	}})
}

func validatePassword(errs fieldErrors, o Optional[string]) {
	if !o.Set || o.Null || o.Value == "" {
		errs.add("password", "can't be blank")
	} else if len([]rune(o.Value)) < minPasswordLen {
		errs.add("password", "is too short (minimum is 8 characters)")
	}
}

// checkTaken reports username/email conflicts with users other than selfID.
func (s *Server) checkTaken(username, email *string, selfID uint) (fieldErrors, error) {
	errs := fieldErrors{}
	check := func(field, value string) error {
		var n int64
		if err := s.db.Model(&db.User{}).Where(field+" = ? AND id <> ?", value, selfID).Count(&n).Error; err != nil {
			return err
		}
		if n > 0 {
			errs.add(field, "has already been taken")
		}
		return nil
	}
	if username != nil {
		if err := check("username", *username); err != nil {
			return nil, err
		}
	}
	if email != nil {
		if err := check("email", *email); err != nil {
			return nil, err
		}
	}
	return errs, nil
}

func isUniqueViolation(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}

func (s *Server) register(c *gin.Context) {
	var req struct {
		User struct {
			Username Optional[string] `json:"username"`
			Email    Optional[string] `json:"email"`
			Password Optional[string] `json:"password"`
		} `json:"user"`
	}
	if !bindJSON(c, &req) {
		return
	}
	in := req.User
	errs := fieldErrors{}
	requireString(errs, "username", in.Username)
	requireString(errs, "email", in.Email)
	validatePassword(errs, in.Password)
	if len(errs) > 0 {
		abortErrors(c, http.StatusUnprocessableEntity, errs)
		return
	}
	username, email := strings.TrimSpace(in.Username.Value), strings.TrimSpace(in.Email.Value)

	taken, err := s.checkTaken(&username, &email, 0)
	if err != nil {
		internalError(c, err)
		return
	}
	if len(taken) > 0 {
		abortErrors(c, http.StatusConflict, taken)
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(in.Password.Value), bcrypt.DefaultCost)
	if err != nil {
		internalError(c, err)
		return
	}
	u := db.User{Username: username, Email: email, PasswordHash: string(hash)}
	if err := s.db.Create(&u).Error; err != nil {
		if isUniqueViolation(err) {
			abortError(c, http.StatusConflict, "username", "has already been taken")
			return
		}
		internalError(c, err)
		return
	}
	s.writeUser(c, http.StatusCreated, &u)
}

func (s *Server) login(c *gin.Context) {
	var req struct {
		User struct {
			Email    Optional[string] `json:"email"`
			Password Optional[string] `json:"password"`
		} `json:"user"`
	}
	if !bindJSON(c, &req) {
		return
	}
	errs := fieldErrors{}
	requireString(errs, "email", req.User.Email)
	if !req.User.Password.Set || req.User.Password.Null || req.User.Password.Value == "" {
		errs.add("password", "can't be blank")
	}
	if len(errs) > 0 {
		abortErrors(c, http.StatusUnprocessableEntity, errs)
		return
	}

	var u db.User
	err := s.db.Where("email = ?", strings.TrimSpace(req.User.Email.Value)).Take(&u).Error
	if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		internalError(c, err)
		return
	}
	if err != nil || bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(req.User.Password.Value)) != nil {
		abortError(c, http.StatusUnauthorized, "credentials", "invalid")
		return
	}
	s.writeUser(c, http.StatusOK, &u)
}

func (s *Server) getCurrentUser(c *gin.Context) {
	s.writeUser(c, http.StatusOK, currentUser(c))
}

func (s *Server) updateUser(c *gin.Context) {
	var req struct {
		User struct {
			Username Optional[string] `json:"username"`
			Email    Optional[string] `json:"email"`
			Password Optional[string] `json:"password"`
			Bio      Optional[string] `json:"bio"`
			Image    Optional[string] `json:"image"`
		} `json:"user"`
	}
	if !bindJSON(c, &req) {
		return
	}
	in := req.User
	u := *currentUser(c)

	errs := fieldErrors{}
	var newUsername, newEmail *string
	if in.Username.Set {
		requireString(errs, "username", in.Username)
		v := strings.TrimSpace(in.Username.Value)
		newUsername = &v
	}
	if in.Email.Set {
		requireString(errs, "email", in.Email)
		v := strings.TrimSpace(in.Email.Value)
		newEmail = &v
	}
	if in.Password.Set {
		validatePassword(errs, in.Password)
	}
	if len(errs) > 0 {
		abortErrors(c, http.StatusUnprocessableEntity, errs)
		return
	}

	taken, err := s.checkTaken(newUsername, newEmail, u.ID)
	if err != nil {
		internalError(c, err)
		return
	}
	if len(taken) > 0 {
		abortErrors(c, http.StatusConflict, taken)
		return
	}

	if newUsername != nil {
		u.Username = *newUsername
	}
	if newEmail != nil {
		u.Email = *newEmail
	}
	if in.Password.Set {
		hash, err := bcrypt.GenerateFromPassword([]byte(in.Password.Value), bcrypt.DefaultCost)
		if err != nil {
			internalError(c, err)
			return
		}
		u.PasswordHash = string(hash)
	}
	// Empty strings and nulls both clear the nullable profile fields.
	if in.Bio.Set {
		u.Bio = nullable(&in.Bio.Value)
	}
	if in.Image.Set {
		u.Image = nullable(&in.Image.Value)
	}

	if err := s.db.Select("username", "email", "password_hash", "bio", "image", "updated_at").Save(&u).Error; err != nil {
		if isUniqueViolation(err) {
			abortError(c, http.StatusConflict, "username", "has already been taken")
			return
		}
		internalError(c, err)
		return
	}
	s.writeUser(c, http.StatusOK, &u)
}

func (s *Server) isFollowing(followerID, followeeID uint) (bool, error) {
	var n int64
	err := s.db.Model(&db.Follow{}).Where("follower_id = ? AND followee_id = ?", followerID, followeeID).Count(&n).Error
	return n > 0, err
}

func (s *Server) findUserByUsername(c *gin.Context) (*db.User, bool) {
	var u db.User
	err := s.db.Where("username = ?", c.Param("username")).Take(&u).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		notFound(c, "profile")
		return nil, false
	}
	if err != nil {
		internalError(c, err)
		return nil, false
	}
	return &u, true
}

func (s *Server) getProfile(c *gin.Context) {
	target, ok := s.findUserByUsername(c)
	if !ok {
		return
	}
	following := false
	if me := currentUser(c); me != nil {
		var err error
		if following, err = s.isFollowing(me.ID, target.ID); err != nil {
			internalError(c, err)
			return
		}
	}
	c.JSON(http.StatusOK, gin.H{"profile": toProfile(target, following)})
}

func (s *Server) followUser(c *gin.Context) {
	target, ok := s.findUserByUsername(c)
	if !ok {
		return
	}
	me := currentUser(c)
	if me.ID != target.ID {
		f := db.Follow{FollowerID: me.ID, FolloweeID: target.ID}
		if err := s.db.Clauses(clause.OnConflict{DoNothing: true}).Create(&f).Error; err != nil {
			internalError(c, err)
			return
		}
	}
	c.JSON(http.StatusOK, gin.H{"profile": toProfile(target, me.ID != target.ID)})
}

func (s *Server) unfollowUser(c *gin.Context) {
	target, ok := s.findUserByUsername(c)
	if !ok {
		return
	}
	me := currentUser(c)
	if err := s.db.Where("follower_id = ? AND followee_id = ?", me.ID, target.ID).Delete(&db.Follow{}).Error; err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"profile": toProfile(target, false)})
}
