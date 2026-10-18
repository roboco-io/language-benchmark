package main

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

type articleJSON struct {
	Slug           string      `json:"slug"`
	Title          string      `json:"title"`
	Description    string      `json:"description"`
	Body           string      `json:"body,omitempty"`
	TagList        []string    `json:"tagList"`
	CreatedAt      string      `json:"createdAt"`
	UpdatedAt      string      `json:"updatedAt"`
	Favorited      bool        `json:"favorited"`
	FavoritesCount int64       `json:"favoritesCount"`
	Author         profileJSON `json:"author"`
}

// render converts articles to their API form using a fixed number of queries
// regardless of list size. withBody controls whether the body is included.
func (s *Server) render(arts []Article, viewer uint, withBody bool) []articleJSON {
	out := make([]articleJSON, 0, len(arts))
	if len(arts) == 0 {
		return out
	}
	ids := make([]uint, len(arts))
	authorIDs := make([]uint, 0, len(arts))
	for i, a := range arts {
		ids[i] = a.ID
		authorIDs = append(authorIDs, a.AuthorID)
	}

	var authors []User
	s.db.Where("id IN ?", authorIDs).Find(&authors)
	authorByID := map[uint]*User{}
	for i := range authors {
		authorByID[authors[i].ID] = &authors[i]
	}

	var rows []struct {
		ArticleID uint
		Name      string
	}
	s.db.Table("article_tags").Select("article_tags.article_id, tags.name").
		Joins("JOIN tags ON tags.id = article_tags.tag_id").
		Where("article_tags.article_id IN ?", ids).Order("article_tags.id").Scan(&rows)
	tagsByArticle := map[uint][]string{}
	for _, r := range rows {
		tagsByArticle[r.ArticleID] = append(tagsByArticle[r.ArticleID], r.Name)
	}

	var counts []struct {
		ArticleID uint
		N         int64
	}
	s.db.Model(&Favorite{}).Select("article_id, COUNT(*) AS n").Where("article_id IN ?", ids).Group("article_id").Scan(&counts)
	countByArticle := map[uint]int64{}
	for _, c := range counts {
		countByArticle[c.ArticleID] = c.N
	}

	favorited := map[uint]bool{}
	following := map[uint]bool{}
	if viewer != 0 {
		var fav []uint
		s.db.Model(&Favorite{}).Where("user_id = ? AND article_id IN ?", viewer, ids).Pluck("article_id", &fav)
		for _, id := range fav {
			favorited[id] = true
		}
		var fol []uint
		s.db.Model(&Follow{}).Where("follower_id = ? AND followee_id IN ?", viewer, authorIDs).Pluck("followee_id", &fol)
		for _, id := range fol {
			following[id] = true
		}
	}

	for _, a := range arts {
		tags := tagsByArticle[a.ID]
		if tags == nil {
			tags = []string{}
		}
		j := articleJSON{
			Slug: a.Slug, Title: a.Title, Description: a.Description, TagList: tags,
			CreatedAt: fmtTime(a.CreatedAt), UpdatedAt: fmtTime(a.UpdatedAt),
			Favorited: favorited[a.ID], FavoritesCount: countByArticle[a.ID],
		}
		if withBody {
			j.Body = a.Body
		}
		if u := authorByID[a.AuthorID]; u != nil {
			j.Author = profileJSON{Username: u.Username, Bio: u.Bio, Image: u.Image, Following: following[u.ID]}
		}
		out = append(out, j)
	}
	return out
}

// renderFull renders a single article including its body. An empty body is
// emitted as "" rather than omitted.
func (s *Server) renderFull(a *Article, viewer uint) gin.H {
	j := s.render([]Article{*a}, viewer, true)[0]
	return gin.H{"article": gin.H{
		"slug": j.Slug, "title": j.Title, "description": j.Description, "body": a.Body,
		"tagList": j.TagList, "createdAt": j.CreatedAt, "updatedAt": j.UpdatedAt,
		"favorited": j.Favorited, "favoritesCount": j.FavoritesCount, "author": j.Author,
	}}
}

func (s *Server) findArticle(c *gin.Context) (*Article, bool) {
	var a Article
	if err := s.db.Where("slug = ?", c.Param("slug")).First(&a).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			fail(c, http.StatusNotFound, "article", "not found")
		} else {
			fail(c, http.StatusInternalServerError, "server", "error")
		}
		return nil, false
	}
	return &a, true
}

func intParam(c *gin.Context, key string, def, max int) int {
	v, err := strconv.Atoi(c.Query(key))
	if err != nil || v < 0 {
		return def
	}
	if max > 0 && v > max {
		return max
	}
	return v
}

func (s *Server) paged(c *gin.Context, q *gorm.DB) {
	var total int64
	if err := q.Count(&total).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	var arts []Article
	if err := q.Order("articles.id DESC").Limit(intParam(c, "limit", 20, 100)).Offset(intParam(c, "offset", 0, 0)).Find(&arts).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, gin.H{"articles": s.render(arts, viewerID(c), false), "articlesCount": total})
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
	s.paged(c, q)
}

func (s *Server) feed(c *gin.Context) {
	q := s.db.Model(&Article{}).Where("articles.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?)", viewerID(c))
	s.paged(c, q)
}

func parseTagList(raw json.RawMessage) ([]string, bool) {
	var tags []string
	if string(raw) == "null" || json.Unmarshal(raw, &tags) != nil || tags == nil {
		return nil, false
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(tags))
	for _, t := range tags {
		t = strings.TrimSpace(t)
		if t == "" || seen[t] {
			continue
		}
		seen[t] = true
		out = append(out, t)
	}
	return out, true
}

// setTags replaces the tags of an article inside the given transaction.
func setTags(tx *gorm.DB, articleID uint, names []string) error {
	if err := tx.Where("article_id = ?", articleID).Delete(&ArticleTag{}).Error; err != nil {
		return err
	}
	for _, n := range names {
		t := Tag{Name: n}
		if err := tx.Where("name = ?", n).FirstOrCreate(&t).Error; err != nil {
			return err
		}
		if err := tx.Create(&ArticleTag{ArticleID: articleID, TagID: t.ID}).Error; err != nil {
			return err
		}
	}
	return nil
}

func (s *Server) createArticle(c *gin.Context) {
	in, ok := bindWrapped(c, "article")
	if !ok {
		return
	}
	title, _, _, _ := strField(in, "title")
	desc, _, _, _ := strField(in, "description")
	body, _, _, _ := strField(in, "body")
	errs := map[string][]string{}
	if strings.TrimSpace(title) == "" {
		errs["title"] = []string{"can't be blank"}
	}
	if strings.TrimSpace(desc) == "" {
		errs["description"] = []string{"can't be blank"}
	}
	if strings.TrimSpace(body) == "" {
		errs["body"] = []string{"can't be blank"}
	}
	var tags []string
	if raw, present := in["tagList"]; present {
		t, valid := parseTagList(raw)
		if !valid {
			errs["tagList"] = []string{"is invalid"}
		}
		tags = t
	}
	if len(errs) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, errs)
		return
	}
	a := &Article{Slug: slugify(title), Title: title, Description: desc, Body: body, AuthorID: viewerID(c)}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(a).Error; err != nil {
			return err
		}
		return setTags(tx, a.ID, tags)
	})
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusCreated, s.renderFull(a, viewerID(c)))
}

func (s *Server) getArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	c.JSON(http.StatusOK, s.renderFull(a, viewerID(c)))
}

func (s *Server) updateArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if a.AuthorID != viewerID(c) {
		fail(c, http.StatusForbidden, "article", "forbidden")
		return
	}
	in, ok := bindWrapped(c, "article")
	if !ok {
		return
	}
	errs := map[string][]string{}
	updates := map[string]any{}
	for _, f := range []string{"title", "description", "body"} {
		v, present, isNull, valid := strField(in, f)
		if !present {
			continue
		}
		if isNull || !valid || strings.TrimSpace(v) == "" {
			errs[f] = []string{"can't be blank"}
			continue
		}
		updates[f] = v
	}
	var tags []string
	setTagList := false
	if raw, present := in["tagList"]; present {
		t, valid := parseTagList(raw)
		if !valid {
			errs["tagList"] = []string{"is invalid"}
		}
		tags, setTagList = t, true
	}
	if len(errs) > 0 {
		failMulti(c, http.StatusUnprocessableEntity, errs)
		return
	}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		// Always bump updated_at, even when only tags change.
		if err := tx.Model(a).Updates(updates).Error; err != nil {
			return err
		}
		if len(updates) == 0 {
			if err := tx.Model(a).UpdateColumn("updated_at", gorm.Expr("?", nowUTC())).Error; err != nil {
				return err
			}
		}
		if setTagList {
			return setTags(tx, a.ID, tags)
		}
		return nil
	})
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	s.db.First(a, a.ID)
	c.JSON(http.StatusOK, s.renderFull(a, viewerID(c)))
}

func (s *Server) deleteArticle(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	if a.AuthorID != viewerID(c) {
		fail(c, http.StatusForbidden, "article", "forbidden")
		return
	}
	err := s.db.Transaction(func(tx *gorm.DB) error {
		for _, m := range []any{&ArticleTag{}, &Favorite{}, &Comment{}} {
			if err := tx.Where("article_id = ?", a.ID).Delete(m).Error; err != nil {
				return err
			}
		}
		return tx.Delete(a).Error
	})
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) favorite(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	f := Favorite{UserID: viewerID(c), ArticleID: a.ID}
	if err := s.db.Where(f).FirstOrCreate(&f).Error; err != nil && !isUnique(err) {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, s.renderFull(a, viewerID(c)))
}

func (s *Server) unfavorite(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	s.db.Where("user_id = ? AND article_id = ?", viewerID(c), a.ID).Delete(&Favorite{})
	c.JSON(http.StatusOK, s.renderFull(a, viewerID(c)))
}

func (s *Server) listTags(c *gin.Context) {
	names := []string{}
	if err := s.db.Model(&Tag{}).Order("id").Pluck("name", &names).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, gin.H{"tags": names})
}
