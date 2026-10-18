package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"unicode"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

type articleResponse struct {
	Slug           string          `json:"slug"`
	Title          string          `json:"title"`
	Description    string          `json:"description"`
	Body           *string         `json:"body,omitempty"`
	TagList        []string        `json:"tagList"`
	CreatedAt      string          `json:"createdAt"`
	UpdatedAt      string          `json:"updatedAt"`
	Favorited      bool            `json:"favorited"`
	FavoritesCount int64           `json:"favoritesCount"`
	Author         profileResponse `json:"author"`
}

// buildArticles assembles API responses for the given articles with a fixed
// number of queries (authors, tags, favorite counts, viewer state).
func (s *Server) buildArticles(articles []Article, viewer *User, withBody bool) []articleResponse {
	out := make([]articleResponse, 0, len(articles))
	if len(articles) == 0 {
		return out
	}
	articleIDs := make([]uint, len(articles))
	authorIDs := make([]uint, 0, len(articles))
	for i, a := range articles {
		articleIDs[i] = a.ID
		authorIDs = append(authorIDs, a.AuthorID)
	}

	var authors []User
	s.db.Where("id IN ?", authorIDs).Find(&authors)
	authorByID := make(map[uint]*User, len(authors))
	for i := range authors {
		authorByID[authors[i].ID] = &authors[i]
	}

	var tagRows []struct {
		ArticleID uint
		Name      string
	}
	s.db.Table("article_tags").Select("article_tags.article_id, tags.name").
		Joins("JOIN tags ON tags.id = article_tags.tag_id").
		Where("article_tags.article_id IN ?", articleIDs).
		Order("article_tags.article_id, article_tags.position").Scan(&tagRows)
	tagsByArticle := make(map[uint][]string)
	for _, r := range tagRows {
		tagsByArticle[r.ArticleID] = append(tagsByArticle[r.ArticleID], r.Name)
	}

	var countRows []struct {
		ArticleID uint
		N         int64
	}
	s.db.Table("favorites").Select("article_id, COUNT(*) AS n").
		Where("article_id IN ?", articleIDs).Group("article_id").Scan(&countRows)
	favCount := make(map[uint]int64)
	for _, r := range countRows {
		favCount[r.ArticleID] = r.N
	}

	favorited := make(map[uint]bool)
	following := make(map[uint]bool)
	if viewer != nil {
		var ids []uint
		s.db.Model(&Favorite{}).Where("user_id = ? AND article_id IN ?", viewer.ID, articleIDs).Pluck("article_id", &ids)
		for _, id := range ids {
			favorited[id] = true
		}
		ids = nil
		s.db.Model(&Follow{}).Where("follower_id = ? AND followee_id IN ?", viewer.ID, authorIDs).Pluck("followee_id", &ids)
		for _, id := range ids {
			following[id] = true
		}
	}

	for _, a := range articles {
		tags := tagsByArticle[a.ID]
		if tags == nil {
			tags = []string{}
		}
		var author profileResponse
		if u := authorByID[a.AuthorID]; u != nil {
			author = toProfile(u, following[u.ID])
		}
		resp := articleResponse{
			Slug:           a.Slug,
			Title:          a.Title,
			Description:    a.Description,
			TagList:        tags,
			CreatedAt:      fmtTime(a.CreatedAt),
			UpdatedAt:      fmtTime(a.UpdatedAt),
			Favorited:      favorited[a.ID],
			FavoritesCount: favCount[a.ID],
			Author:         author,
		}
		if withBody {
			body := a.Body
			resp.Body = &body
		}
		out = append(out, resp)
	}
	return out
}

func (s *Server) respondArticle(c *gin.Context, status int, a *Article) {
	c.JSON(status, gin.H{"article": s.buildArticles([]Article{*a}, currentUser(c), true)[0]})
}

func pagination(c *gin.Context) (limit, offset int) {
	limit, offset = 20, 0
	if v, err := strconv.Atoi(c.Query("limit")); err == nil && v >= 0 {
		limit = v
	}
	if v, err := strconv.Atoi(c.Query("offset")); err == nil && v >= 0 {
		offset = v
	}
	return limit, offset
}

func (s *Server) respondArticleList(c *gin.Context, q *gorm.DB) {
	var total int64
	if err := q.Session(&gorm.Session{}).Model(&Article{}).Count(&total).Error; err != nil {
		abortErr(c, 500, "articles", "could not be loaded")
		return
	}
	limit, offset := pagination(c)
	var articles []Article
	q.Order("articles.created_at DESC, articles.id DESC").Limit(limit).Offset(offset).Find(&articles)
	c.JSON(200, gin.H{
		"articles":      s.buildArticles(articles, currentUser(c), false),
		"articlesCount": total,
	})
}

func (s *Server) listArticles(c *gin.Context) {
	q := s.db.Model(&Article{})
	if tag := c.Query("tag"); tag != "" {
		q = q.Where("articles.id IN (SELECT article_tags.article_id FROM article_tags JOIN tags ON tags.id = article_tags.tag_id WHERE tags.name = ?)", tag)
	}
	if author := c.Query("author"); author != "" {
		q = q.Where("articles.author_id IN (SELECT id FROM users WHERE username = ?)", author)
	}
	if fav := c.Query("favorited"); fav != "" {
		q = q.Where("articles.id IN (SELECT favorites.article_id FROM favorites JOIN users ON users.id = favorites.user_id WHERE users.username = ?)", fav)
	}
	s.respondArticleList(c, q)
}

func (s *Server) feed(c *gin.Context) {
	q := s.db.Model(&Article{}).
		Where("articles.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?)", currentUser(c).ID)
	s.respondArticleList(c, q)
}

func (s *Server) findArticle(c *gin.Context) (*Article, bool) {
	var a Article
	err := s.db.Where("slug = ?", c.Param("slug")).First(&a).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		abortErr(c, 404, "article", "not found")
		return nil, false
	} else if err != nil {
		abortErr(c, 500, "article", "could not be loaded")
		return nil, false
	}
	return &a, true
}

func (s *Server) getArticle(c *gin.Context) {
	if a, ok := s.findArticle(c); ok {
		s.respondArticle(c, 200, a)
	}
}

func slugify(title string) string {
	var b strings.Builder
	dash := false
	for _, r := range strings.ToLower(title) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
			dash = false
		} else if !dash && b.Len() > 0 {
			b.WriteByte('-')
			dash = true
		}
	}
	slug := strings.TrimRight(b.String(), "-")
	if slug == "" {
		slug = "article"
	}
	return slug
}

// uniqueSlug derives a slug from the title, appending a random suffix when
// the plain slug is already used by a different article.
func (s *Server) uniqueSlug(title string, exceptID uint) string {
	base := slugify(title)
	slug := base
	for {
		var n int64
		s.db.Model(&Article{}).Where("slug = ? AND id <> ?", slug, exceptID).Count(&n)
		if n == 0 {
			return slug
		}
		buf := make([]byte, 4)
		_, _ = rand.Read(buf)
		slug = base + "-" + hex.EncodeToString(buf)
	}
}

// parseTagList decodes a tagList value, trimming, dropping empties, and
// removing duplicates while preserving order.
func parseTagList(raw json.RawMessage) ([]string, bool) {
	var tags []string
	if err := json.Unmarshal(raw, &tags); err != nil {
		return nil, false
	}
	seen := make(map[string]bool, len(tags))
	out := make([]string, 0, len(tags))
	for _, t := range tags {
		t = strings.TrimSpace(t)
		if t != "" && !seen[t] {
			seen[t] = true
			out = append(out, t)
		}
	}
	return out, true
}

// setTags replaces the tag list of an article inside a transaction.
func setTags(tx *gorm.DB, articleID uint, tags []string) error {
	if err := tx.Where("article_id = ?", articleID).Delete(&ArticleTag{}).Error; err != nil {
		return err
	}
	for i, name := range tags {
		if err := tx.Exec("INSERT OR IGNORE INTO tags (name) VALUES (?)", name).Error; err != nil {
			return err
		}
		var tag Tag
		if err := tx.Where("name = ?", name).First(&tag).Error; err != nil {
			return err
		}
		if err := tx.Create(&ArticleTag{ArticleID: articleID, TagID: tag.ID, Position: i}).Error; err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) createArticle(c *gin.Context) {
	in, ok := bindEnvelope(c, "article")
	if !ok {
		return
	}
	a := Article{
		Title:       strings.TrimSpace(in.str("title")),
		Description: strings.TrimSpace(in.str("description")),
		Body:        in.str("body"),
		AuthorID:    currentUser(c).ID,
	}
	switch {
	case a.Title == "":
		abortErr(c, 422, "title", "can't be blank")
		return
	case a.Description == "":
		abortErr(c, 422, "description", "can't be blank")
		return
	case strings.TrimSpace(a.Body) == "":
		abortErr(c, 422, "body", "can't be blank")
		return
	}
	tags := []string{}
	if in.has("tagList") && !in.isNull("tagList") {
		var ok bool
		if tags, ok = parseTagList(in["tagList"]); !ok {
			abortErr(c, 422, "tagList", "must be an array of strings")
			return
		}
	}
	a.Slug = s.uniqueSlug(a.Title, 0)
	err := s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(&a).Error; err != nil {
			return err
		}
		return setTags(tx, a.ID, tags)
	})
	if err != nil {
		abortErr(c, 500, "article", "could not be created")
		return
	}
	s.respondArticle(c, 201, &a)
}

func (s *Server) updateArticle(c *gin.Context) {
	in, ok := bindEnvelope(c, "article")
	if !ok {
		return
	}
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if a.AuthorID != currentUser(c).ID {
		abortErr(c, 403, "article", "forbidden")
		return
	}
	for _, key := range []string{"title", "description", "body"} {
		if in.has(key) && strings.TrimSpace(in.str(key)) == "" {
			abortErr(c, 422, key, "can't be blank")
			return
		}
	}
	var tags []string
	if in.has("tagList") {
		var ok bool
		if in.isNull("tagList") {
			abortErr(c, 422, "tagList", "can't be null")
			return
		}
		if tags, ok = parseTagList(in["tagList"]); !ok {
			abortErr(c, 422, "tagList", "must be an array of strings")
			return
		}
	}
	if in.has("title") {
		title := strings.TrimSpace(in.str("title"))
		if title != a.Title {
			a.Title = title
			a.Slug = s.uniqueSlug(title, a.ID)
		}
	}
	if in.has("description") {
		a.Description = strings.TrimSpace(in.str("description"))
	}
	if in.has("body") {
		a.Body = in.str("body")
	}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(a).Select("Slug", "Title", "Description", "Body", "UpdatedAt").Updates(a).Error; err != nil {
			return err
		}
		if tags != nil {
			return setTags(tx, a.ID, tags)
		}
		return nil
	})
	if err != nil {
		abortErr(c, 500, "article", "could not be updated")
		return
	}
	s.respondArticle(c, 200, a)
}

func (s *Server) deleteArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if a.AuthorID != currentUser(c).ID {
		abortErr(c, 403, "article", "forbidden")
		return
	}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		for _, m := range []any{&ArticleTag{}, &Favorite{}, &Comment{}} {
			if err := tx.Where("article_id = ?", a.ID).Delete(m).Error; err != nil {
				return err
			}
		}
		return tx.Delete(&Article{}, a.ID).Error
	})
	if err != nil {
		abortErr(c, 500, "article", "could not be deleted")
		return
	}
	c.Status(204)
}

func (s *Server) favorite(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	s.db.Exec("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)", currentUser(c).ID, a.ID)
	s.respondArticle(c, 200, a)
}

func (s *Server) unfavorite(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	s.db.Where("user_id = ? AND article_id = ?", currentUser(c).ID, a.ID).Delete(&Favorite{})
	s.respondArticle(c, 200, a)
}

func (s *Server) listTags(c *gin.Context) {
	tags := []string{}
	s.db.Model(&Tag{}).
		Where("id IN (SELECT tag_id FROM article_tags)").
		Order("name").Pluck("name", &tags)
	c.JSON(200, gin.H{"tags": tags})
}
