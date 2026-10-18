package main

import (
	"crypto/rand"
	"encoding/hex"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

type server struct {
	db     *gorm.DB
	secret []byte
}

type errs map[string][]string

func fail(c *gin.Context, status int, field, msg string) {
	c.AbortWithStatusJSON(status, gin.H{"errors": errs{field: {msg}}})
}

func failMulti(c *gin.Context, status int, e errs) {
	c.AbortWithStatusJSON(status, gin.H{"errors": e})
}

const timeFmt = "2006-01-02T15:04:05.000Z"

func ts(t time.Time) string { return t.UTC().Format(timeFmt) }

func newRouter(s *server) *gin.Engine {
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Headers", "Authorization, Content-Type")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	})
	api := r.Group("/api")
	api.POST("/users", s.register)
	api.POST("/users/login", s.login)
	api.GET("/user", s.requireAuth, s.currentUser)
	api.PUT("/user", s.requireAuth, s.updateUser)

	api.GET("/profiles/:username", s.optionalAuth, s.getProfile)
	api.POST("/profiles/:username/follow", s.requireAuth, s.follow)
	api.DELETE("/profiles/:username/follow", s.requireAuth, s.unfollow)

	api.GET("/articles", s.optionalAuth, s.listArticles)
	api.GET("/articles/feed", s.requireAuth, s.feed)
	api.POST("/articles", s.requireAuth, s.createArticle)
	api.GET("/articles/:slug", s.optionalAuth, s.getArticle)
	api.PUT("/articles/:slug", s.requireAuth, s.updateArticle)
	api.DELETE("/articles/:slug", s.requireAuth, s.deleteArticle)
	api.POST("/articles/:slug/favorite", s.requireAuth, s.favorite)
	api.DELETE("/articles/:slug/favorite", s.requireAuth, s.unfavorite)
	api.GET("/articles/:slug/comments", s.optionalAuth, s.listComments)
	api.POST("/articles/:slug/comments", s.requireAuth, s.addComment)
	api.PUT("/articles/:slug/comments/:id", s.requireAuth, s.updateComment)
	api.DELETE("/articles/:slug/comments/:id", s.requireAuth, s.deleteComment)

	api.GET("/tags", s.tags)
	return r
}

// ---------- auth ----------

func (s *server) token(u *User) string {
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"id":  u.ID,
		"exp": time.Now().Add(7 * 24 * time.Hour).Unix(),
	})
	str, _ := t.SignedString(s.secret)
	return str
}

func (s *server) parseAuth(c *gin.Context) (*User, bool) {
	h := c.GetHeader("Authorization")
	if h == "" {
		return nil, false
	}
	parts := strings.SplitN(h, " ", 2)
	if len(parts) != 2 || (parts[0] != "Token" && parts[0] != "Bearer") {
		return nil, false
	}
	tok, err := jwt.Parse(parts[1], func(t *jwt.Token) (any, error) { return s.secret, nil },
		jwt.WithValidMethods([]string{"HS256"}))
	if err != nil || !tok.Valid {
		return nil, false
	}
	claims, ok := tok.Claims.(jwt.MapClaims)
	if !ok {
		return nil, false
	}
	id, ok := claims["id"].(float64)
	if !ok {
		return nil, false
	}
	var u User
	if err := s.db.First(&u, uint(id)).Error; err != nil {
		return nil, false
	}
	return &u, true
}

func (s *server) requireAuth(c *gin.Context) {
	if c.GetHeader("Authorization") == "" {
		fail(c, http.StatusUnauthorized, "token", "is missing")
		return
	}
	u, ok := s.parseAuth(c)
	if !ok {
		fail(c, http.StatusUnauthorized, "token", "is invalid")
		return
	}
	c.Set("user", u)
}

func (s *server) optionalAuth(c *gin.Context) {
	if u, ok := s.parseAuth(c); ok {
		c.Set("user", u)
	}
}

func me(c *gin.Context) *User {
	if v, ok := c.Get("user"); ok {
		return v.(*User)
	}
	return nil
}

// ---------- users ----------

func (s *server) userJSON(u *User) gin.H {
	return gin.H{"user": gin.H{
		"email": u.Email, "username": u.Username, "bio": u.Bio, "image": u.Image, "token": s.token(u),
	}}
}

func str(v any) (string, bool) {
	s, ok := v.(string)
	return s, ok
}

func bindWrapped(c *gin.Context, key string) (map[string]any, bool) {
	var body map[string]any
	if err := c.ShouldBindJSON(&body); err != nil {
		fail(c, http.StatusUnprocessableEntity, "body", "is invalid")
		return nil, false
	}
	inner, ok := body[key].(map[string]any)
	if !ok {
		fail(c, http.StatusUnprocessableEntity, key, "can't be blank")
		return nil, false
	}
	return inner, true
}

func blank(m map[string]any, k string) bool {
	s, ok := str(m[k])
	return !ok || strings.TrimSpace(s) == ""
}

func passwordProblem(p string) string {
	if utf8.RuneCountInString(p) < 8 {
		return "is too short (minimum is 8 characters)"
	}
	return ""
}

func (s *server) register(c *gin.Context) {
	in, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	e := errs{}
	for _, k := range []string{"username", "email", "password"} {
		if blank(in, k) {
			e[k] = []string{"can't be blank"}
		}
	}
	if len(e) == 0 {
		if m := passwordProblem(in["password"].(string)); m != "" {
			e["password"] = []string{m}
		}
	}
	if len(e) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, e)
		return
	}
	username, email, pw := in["username"].(string), in["email"].(string), in["password"].(string)
	conflict := errs{}
	var n int64
	s.db.Model(&User{}).Where("username = ?", username).Count(&n)
	if n > 0 {
		conflict["username"] = []string{"has already been taken"}
	}
	s.db.Model(&User{}).Where("email = ?", email).Count(&n)
	if n > 0 {
		conflict["email"] = []string{"has already been taken"}
	}
	if len(conflict) > 0 {
		failMulti(c, http.StatusConflict, conflict)
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(pw), bcrypt.DefaultCost)
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	u := User{Username: username, Email: email, PasswordHash: string(hash)}
	if err := s.db.Create(&u).Error; err != nil {
		// Lost a race against a concurrent registration.
		failMulti(c, http.StatusConflict, errs{"username": {"has already been taken"}})
		return
	}
	c.JSON(http.StatusCreated, s.userJSON(&u))
}

func (s *server) login(c *gin.Context) {
	in, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	e := errs{}
	for _, k := range []string{"email", "password"} {
		if blank(in, k) {
			e[k] = []string{"can't be blank"}
		}
	}
	if len(e) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, e)
		return
	}
	var u User
	err := s.db.Where("email = ?", in["email"].(string)).First(&u).Error
	if err != nil || bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(in["password"].(string))) != nil {
		fail(c, http.StatusUnauthorized, "credentials", "invalid")
		return
	}
	c.JSON(http.StatusOK, s.userJSON(&u))
}

func (s *server) currentUser(c *gin.Context) { c.JSON(http.StatusOK, s.userJSON(me(c))) }

func (s *server) updateUser(c *gin.Context) {
	u := me(c)
	in, ok := bindWrapped(c, "user")
	if !ok {
		return
	}
	e := errs{}
	updates := map[string]any{}
	for _, k := range []string{"username", "email"} {
		if v, present := in[k]; present {
			if blank(in, k) {
				e[k] = []string{"can't be blank"}
				continue
			}
			updates[k] = v.(string)
		}
	}
	if v, present := in["password"]; present {
		p, isStr := str(v)
		switch {
		case !isStr || strings.TrimSpace(p) == "":
			e["password"] = []string{"can't be blank"}
		case passwordProblem(p) != "":
			e["password"] = []string{passwordProblem(p)}
		default:
			h, _ := bcrypt.GenerateFromPassword([]byte(p), bcrypt.DefaultCost)
			updates["password_hash"] = string(h)
		}
	}
	for _, k := range []string{"bio", "image"} {
		if v, present := in[k]; present {
			if v == nil {
				updates[k] = nil
			} else if sv, isStr := str(v); isStr {
				if sv == "" {
					updates[k] = nil // empty strings are normalized to null
				} else {
					updates[k] = sv
				}
			} else {
				e[k] = []string{"is invalid"}
			}
		}
	}
	if len(e) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, e)
		return
	}
	conflict := errs{}
	for _, k := range []string{"username", "email"} {
		if v, present := updates[k]; present {
			var n int64
			s.db.Model(&User{}).Where(k+" = ? AND id <> ?", v, u.ID).Count(&n)
			if n > 0 {
				conflict[k] = []string{"has already been taken"}
			}
		}
	}
	if len(conflict) > 0 {
		failMulti(c, http.StatusConflict, conflict)
		return
	}
	if len(updates) > 0 {
		if err := s.db.Model(u).Updates(updates).Error; err != nil {
			failMulti(c, http.StatusConflict, errs{"user": {"has already been taken"}})
			return
		}
	}
	s.db.First(u, u.ID)
	c.JSON(http.StatusOK, s.userJSON(u))
}

// ---------- profiles ----------

func (s *server) isFollowing(viewer *User, id uint) bool {
	if viewer == nil {
		return false
	}
	var n int64
	s.db.Model(&Follow{}).Where("follower_id = ? AND followee_id = ?", viewer.ID, id).Count(&n)
	return n > 0
}

func (s *server) profileJSON(u *User, viewer *User) gin.H {
	return gin.H{"username": u.Username, "bio": u.Bio, "image": u.Image, "following": s.isFollowing(viewer, u.ID)}
}

func (s *server) findProfile(c *gin.Context) (*User, bool) {
	var u User
	if err := s.db.Where("username = ?", c.Param("username")).First(&u).Error; err != nil {
		fail(c, http.StatusNotFound, "profile", "not found")
		return nil, false
	}
	return &u, true
}

func (s *server) getProfile(c *gin.Context) {
	if u, ok := s.findProfile(c); ok {
		c.JSON(http.StatusOK, gin.H{"profile": s.profileJSON(u, me(c))})
	}
}

func (s *server) follow(c *gin.Context) {
	u, ok := s.findProfile(c)
	if !ok {
		return
	}
	v := me(c)
	if u.ID == v.ID {
		fail(c, http.StatusUnprocessableEntity, "profile", "cannot follow yourself")
		return
	}
	s.db.Where(Follow{FollowerID: v.ID, FolloweeID: u.ID}).FirstOrCreate(&Follow{})
	c.JSON(http.StatusOK, gin.H{"profile": s.profileJSON(u, v)})
}

func (s *server) unfollow(c *gin.Context) {
	u, ok := s.findProfile(c)
	if !ok {
		return
	}
	v := me(c)
	s.db.Where("follower_id = ? AND followee_id = ?", v.ID, u.ID).Delete(&Follow{})
	c.JSON(http.StatusOK, gin.H{"profile": s.profileJSON(u, v)})
}

// ---------- articles ----------

var nonSlug = regexp.MustCompile(`[^a-z0-9]+`)

func (s *server) makeSlug(title string) string {
	base := strings.Trim(nonSlug.ReplaceAllString(strings.ToLower(title), "-"), "-")
	if base == "" {
		base = "article"
	}
	for {
		b := make([]byte, 4)
		rand.Read(b)
		slug := base + "-" + hex.EncodeToString(b)
		var n int64
		s.db.Model(&Article{}).Where("slug = ?", slug).Count(&n)
		if n == 0 {
			return slug
		}
	}
}

func (s *server) articleJSON(a *Article, viewer *User, withBody bool) gin.H {
	var author User
	s.db.First(&author, a.AuthorID)
	var names []string
	s.db.Model(&ArticleTag{}).Where("article_id = ?", a.ID).Order("name").Pluck("name", &names)
	if names == nil {
		names = []string{}
	}
	var count int64
	s.db.Model(&Favorite{}).Where("article_id = ?", a.ID).Count(&count)
	favorited := false
	if viewer != nil {
		var n int64
		s.db.Model(&Favorite{}).Where("article_id = ? AND user_id = ?", a.ID, viewer.ID).Count(&n)
		favorited = n > 0
	}
	h := gin.H{
		"slug": a.Slug, "title": a.Title, "description": a.Description, "tagList": names,
		"createdAt": ts(a.CreatedAt), "updatedAt": ts(a.UpdatedAt),
		"favorited": favorited, "favoritesCount": count, "author": s.profileJSON(&author, viewer),
	}
	if withBody {
		h["body"] = a.Body
	}
	return h
}

func (s *server) findArticle(c *gin.Context) (*Article, bool) {
	var a Article
	if err := s.db.Where("slug = ?", c.Param("slug")).First(&a).Error; err != nil {
		fail(c, http.StatusNotFound, "article", "not found")
		return nil, false
	}
	return &a, true
}

func queryInt(c *gin.Context, key string, def, max int) int {
	v, err := strconv.Atoi(c.Query(key))
	if err != nil || v < 0 {
		return def
	}
	if max > 0 && v > max {
		return max
	}
	return v
}

func (s *server) respondList(c *gin.Context, q *gorm.DB) {
	var total int64
	q.Session(&gorm.Session{}).Count(&total)
	var arts []Article
	q.Order("articles.created_at DESC, articles.id DESC").
		Limit(queryInt(c, "limit", 20, 100)).Offset(queryInt(c, "offset", 0, 0)).Find(&arts)
	out := make([]gin.H, 0, len(arts))
	for i := range arts {
		out = append(out, s.articleJSON(&arts[i], me(c), false))
	}
	c.JSON(http.StatusOK, gin.H{"articles": out, "articlesCount": total})
}

func (s *server) listArticles(c *gin.Context) {
	q := s.db.Model(&Article{})
	if tag := c.Query("tag"); tag != "" {
		q = q.Where("articles.id IN (?)", s.db.Model(&ArticleTag{}).Select("article_id").Where("name = ?", tag))
	}
	if au := c.Query("author"); au != "" {
		q = q.Where("articles.author_id IN (?)", s.db.Model(&User{}).Select("id").Where("username = ?", au))
	}
	if fav := c.Query("favorited"); fav != "" {
		q = q.Where("articles.id IN (?)", s.db.Model(&Favorite{}).Select("article_id").
			Where("user_id IN (?)", s.db.Model(&User{}).Select("id").Where("username = ?", fav)))
	}
	s.respondList(c, q)
}

func (s *server) feed(c *gin.Context) {
	q := s.db.Model(&Article{}).Where("articles.author_id IN (?)",
		s.db.Model(&Follow{}).Select("followee_id").Where("follower_id = ?", me(c).ID))
	s.respondList(c, q)
}

func parseTags(v any) ([]string, bool) {
	arr, ok := v.([]any)
	if !ok {
		return nil, false
	}
	seen := map[string]bool{}
	out := []string{}
	for _, x := range arr {
		t, ok := x.(string)
		if !ok {
			return nil, false
		}
		if t != "" && !seen[t] {
			seen[t] = true
			out = append(out, t)
		}
	}
	return out, true
}

func (s *server) createArticle(c *gin.Context) {
	in, ok := bindWrapped(c, "article")
	if !ok {
		return
	}
	e := errs{}
	for _, k := range []string{"title", "description", "body"} {
		if blank(in, k) {
			e[k] = []string{"can't be blank"}
		}
	}
	var tags []string
	if v, present := in["tagList"]; present {
		t, ok := parseTags(v)
		if !ok {
			e["tagList"] = []string{"is invalid"}
		}
		tags = t
	}
	if len(e) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, e)
		return
	}
	a := Article{
		Title: in["title"].(string), Description: in["description"].(string), Body: in["body"].(string),
		AuthorID: me(c).ID,
	}
	a.Slug = s.makeSlug(a.Title)
	err := s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(&a).Error; err != nil {
			return err
		}
		for _, t := range tags {
			if err := tx.Create(&ArticleTag{ArticleID: a.ID, Name: t}).Error; err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusCreated, gin.H{"article": s.articleJSON(&a, me(c), true)})
}

func (s *server) getArticle(c *gin.Context) {
	if a, ok := s.findArticle(c); ok {
		c.JSON(http.StatusOK, gin.H{"article": s.articleJSON(a, me(c), true)})
	}
}

func (s *server) updateArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if a.AuthorID != me(c).ID {
		fail(c, http.StatusForbidden, "article", "forbidden")
		return
	}
	in, ok := bindWrapped(c, "article")
	if !ok {
		return
	}
	e := errs{}
	updates := map[string]any{}
	for _, k := range []string{"title", "description", "body"} {
		if _, present := in[k]; present {
			if blank(in, k) {
				e[k] = []string{"can't be blank"}
			} else {
				updates[k] = in[k].(string)
			}
		}
	}
	var tags []string
	setTags := false
	if v, present := in["tagList"]; present {
		t, ok := parseTags(v)
		if !ok {
			e["tagList"] = []string{"is invalid"}
		}
		tags, setTags = t, ok
	}
	if len(e) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, e)
		return
	}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		updates["updated_at"] = time.Now()
		if err := tx.Model(a).Updates(updates).Error; err != nil {
			return err
		}
		if setTags {
			if err := tx.Where("article_id = ?", a.ID).Delete(&ArticleTag{}).Error; err != nil {
				return err
			}
			for _, t := range tags {
				if err := tx.Create(&ArticleTag{ArticleID: a.ID, Name: t}).Error; err != nil {
					return err
				}
			}
		}
		return nil
	})
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	s.db.First(a, a.ID)
	c.JSON(http.StatusOK, gin.H{"article": s.articleJSON(a, me(c), true)})
}

func (s *server) deleteArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if a.AuthorID != me(c).ID {
		fail(c, http.StatusForbidden, "article", "forbidden")
		return
	}
	s.db.Transaction(func(tx *gorm.DB) error {
		tx.Where("article_id = ?", a.ID).Delete(&ArticleTag{})
		tx.Where("article_id = ?", a.ID).Delete(&Favorite{})
		tx.Where("article_id = ?", a.ID).Delete(&Comment{})
		return tx.Delete(a).Error
	})
	c.Status(http.StatusNoContent)
}

func (s *server) favorite(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	s.db.Where(Favorite{UserID: me(c).ID, ArticleID: a.ID}).FirstOrCreate(&Favorite{})
	c.JSON(http.StatusOK, gin.H{"article": s.articleJSON(a, me(c), true)})
}

func (s *server) unfavorite(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	s.db.Where("user_id = ? AND article_id = ?", me(c).ID, a.ID).Delete(&Favorite{})
	c.JSON(http.StatusOK, gin.H{"article": s.articleJSON(a, me(c), true)})
}

// ---------- comments ----------

func (s *server) commentJSON(cm *Comment, viewer *User) gin.H {
	var author User
	s.db.First(&author, cm.AuthorID)
	return gin.H{"id": cm.ID, "createdAt": ts(cm.CreatedAt), "updatedAt": ts(cm.UpdatedAt),
		"body": cm.Body, "author": s.profileJSON(&author, viewer)}
}

func (s *server) listComments(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	var cms []Comment
	s.db.Where("article_id = ?", a.ID).Order("id DESC").Find(&cms)
	out := make([]gin.H, 0, len(cms))
	for i := range cms {
		out = append(out, s.commentJSON(&cms[i], me(c)))
	}
	c.JSON(http.StatusOK, gin.H{"comments": out})
}

func (s *server) addComment(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	in, ok := bindWrapped(c, "comment")
	if !ok {
		return
	}
	if blank(in, "body") {
		fail(c, http.StatusUnprocessableEntity, "body", "can't be blank")
		return
	}
	cm := Comment{ArticleID: a.ID, AuthorID: me(c).ID, Body: in["body"].(string)}
	if err := s.db.Create(&cm).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusCreated, gin.H{"comment": s.commentJSON(&cm, me(c))})
}

func (s *server) updateComment(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.Atoi(c.Param("id"))
	var cm Comment
	if err != nil || s.db.Where("id = ? AND article_id = ?", id, a.ID).First(&cm).Error != nil {
		fail(c, http.StatusNotFound, "comment", "not found")
		return
	}
	if cm.AuthorID != me(c).ID {
		fail(c, http.StatusForbidden, "comment", "forbidden")
		return
	}
	in, ok := bindWrapped(c, "comment")
	if !ok {
		return
	}
	if blank(in, "body") {
		fail(c, http.StatusUnprocessableEntity, "body", "can't be blank")
		return
	}
	cm.Body = in["body"].(string)
	cm.UpdatedAt = time.Now()
	if err := s.db.Model(&cm).Select("Body", "UpdatedAt").Updates(&cm).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, gin.H{"comment": s.commentJSON(&cm, me(c))})
}

func (s *server) deleteComment(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.Atoi(c.Param("id"))
	var cm Comment
	if err != nil || s.db.Where("id = ? AND article_id = ?", id, a.ID).First(&cm).Error != nil {
		fail(c, http.StatusNotFound, "comment", "not found")
		return
	}
	if cm.AuthorID != me(c).ID {
		fail(c, http.StatusForbidden, "comment", "forbidden")
		return
	}
	s.db.Delete(&cm)
	c.Status(http.StatusNoContent)
}

// ---------- tags ----------

func (s *server) tags(c *gin.Context) {
	var names []string
	s.db.Model(&ArticleTag{}).Distinct().Order("name").Pluck("name", &names)
	if names == nil {
		names = []string{}
	}
	c.JSON(http.StatusOK, gin.H{"tags": names})
}
